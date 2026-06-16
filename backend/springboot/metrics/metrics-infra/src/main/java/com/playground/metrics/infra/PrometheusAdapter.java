package com.playground.metrics.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.PrometheusSeries;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.domain.Range;
import com.playground.metrics.domain.Step;
import com.playground.metrics.domain.TimeseriesPoint;
import com.playground.metrics.infra.config.MetricsHttpProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * WebClient adapter against {@code /api/v1/query} +
 * {@code /api/v1/query_range} on playground-prometheus per ADR-15 §7. The
 * per-query timeout is 8 seconds (carrying the ADR-15 §7 budget); the
 * shared HttpClient is the Reactor Netty default.
 */
@Component
public class PrometheusAdapter implements PrometheusPort {

    private final WebClient webClient;
    private final String baseUrl;
    private final Duration timeout;

    public PrometheusAdapter(WebClient.Builder builder, MetricsHttpProperties props) {
        this.baseUrl = props.prometheus().baseUrl();
        this.webClient = builder.baseUrl(this.baseUrl).build();
        this.timeout = Duration.ofMillis(props.prometheus().timeoutMs());
    }

    @Override
    public Mono<List<PrometheusSample>> instantQuery(String promql) {
        // PromQL contains literal `{`/`}` braces (label selectors). Build a
        // fully-encoded URI and feed it to WebClient as a `URI` (not a
        // `String`) so Spring does not re-process it as a URI template.
        URI uri = URI.create(baseUrl + "/api/v1/query?query=" + urlEncode(promql));
        return webClient.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(PrometheusAdapter::parseInstant);
    }

    @Override
    public Mono<List<PrometheusSeries>> rangeQuery(String promql, Range range, Step step) {
        Instant end = Instant.now();
        Instant start = end.minus(range.duration());
        URI uri = URI.create(baseUrl + "/api/v1/query_range"
                + "?query=" + urlEncode(promql)
                + "&start=" + start.getEpochSecond()
                + "&end=" + end.getEpochSecond()
                + "&step=" + urlEncode(step.duration().getSeconds() + "s"));
        return webClient.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(PrometheusAdapter::parseRange);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static List<PrometheusSample> parseInstant(JsonNode root) {
        List<PrometheusSample> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode()) {
            return out;
        }
        String resultType = data.path("resultType").asText("");
        JsonNode result = data.path("result");
        if (!result.isArray()) {
            return out;
        }
        for (JsonNode item : result) {
            Map<String, String> labels = readLabels(item.path("metric"));
            if ("vector".equals(resultType)) {
                JsonNode val = item.path("value");
                if (val.isArray() && val.size() == 2) {
                    long ts = val.get(0).asLong();
                    double v = parseDouble(val.get(1).asText("NaN"));
                    if (!Double.isNaN(v)) {
                        out.add(new PrometheusSample(labels, ts, v));
                    }
                }
            } else if ("matrix".equals(resultType)) {
                JsonNode values = item.path("values");
                if (values.isArray() && values.size() > 0) {
                    JsonNode last = values.get(values.size() - 1);
                    if (last.isArray() && last.size() == 2) {
                        long ts = last.get(0).asLong();
                        double v = parseDouble(last.get(1).asText("NaN"));
                        if (!Double.isNaN(v)) {
                            out.add(new PrometheusSample(labels, ts, v));
                        }
                    }
                }
            }
        }
        return out;
    }

    static List<PrometheusSeries> parseRange(JsonNode root) {
        List<PrometheusSeries> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) {
            return out;
        }
        for (JsonNode item : result) {
            Map<String, String> labels = readLabels(item.path("metric"));
            List<TimeseriesPoint> points = new ArrayList<>();
            JsonNode values = item.path("values");
            if (values.isArray()) {
                for (JsonNode pair : values) {
                    if (pair.isArray() && pair.size() == 2) {
                        long ts = pair.get(0).asLong();
                        double v = parseDouble(pair.get(1).asText("NaN"));
                        if (!Double.isNaN(v)) {
                            points.add(new TimeseriesPoint(ts, v));
                        }
                    }
                }
            }
            out.add(new PrometheusSeries(labels, points));
        }
        return out;
    }

    private static Map<String, String> readLabels(JsonNode metric) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (metric == null || !metric.isObject()) {
            return labels;
        }
        Iterator<Map.Entry<String, JsonNode>> it = metric.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            labels.put(e.getKey(), e.getValue().asText(""));
        }
        return labels;
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank() || "NaN".equalsIgnoreCase(s) || "+Inf".equalsIgnoreCase(s)
                || "-Inf".equalsIgnoreCase(s)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
