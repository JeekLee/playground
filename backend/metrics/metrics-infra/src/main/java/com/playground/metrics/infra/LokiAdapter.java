package com.playground.metrics.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.playground.metrics.app.port.LokiPort;
import com.playground.metrics.domain.LogEntry;
import com.playground.metrics.infra.config.MetricsHttpProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * WebClient adapter against {@code /loki/api/v1/query_range} on
 * loki-playground per ADR-15 §7 + §11. The timeout is 15 seconds.
 *
 * <p>Loki returns timestamps as nanosecond-since-epoch strings; we convert
 * to {@link Instant} via integer division (nanoseconds → seconds + nanos
 * adjusted). The {@code level} value is extracted from the per-line JSON
 * payload via Loki's {@code | json} stage (ADR-15 §11 — labels are
 * {@code container}, {@code service}, {@code source}; level is content).
 */
@Component
public class LokiAdapter implements LokiPort {

    private final WebClient webClient;
    private final Duration timeout;

    public LokiAdapter(WebClient.Builder builder, MetricsHttpProperties props) {
        this.webClient = builder.baseUrl(props.loki().baseUrl()).build();
        this.timeout = Duration.ofMillis(props.loki().timeoutMs());
    }

    @Override
    public Mono<List<LogEntry>> queryRange(String logql, Duration since, int limit) {
        Instant end = Instant.now();
        Instant start = end.minus(since);
        // LogQL contains literal `{`/`}` braces (label selectors); UriBuilder
        // would treat them as URI template placeholders. Hand-build the URI
        // with URL-encoded values to bypass template parsing entirely.
        String encoded = "/loki/api/v1/query_range"
                + "?query=" + urlEncode(logql)
                + "&start=" + (start.toEpochMilli() * 1_000_000L)
                + "&end=" + (end.toEpochMilli() * 1_000_000L)
                + "&limit=" + limit
                + "&direction=backward";
        return webClient.get()
                .uri(encoded)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout)
                .map(LokiAdapter::parse);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static List<LogEntry> parse(JsonNode root) {
        List<LogEntry> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) {
            return out;
        }
        for (JsonNode stream : result) {
            Map<String, String> stripped = readLabels(stream.path("stream"));
            String service = stripped.getOrDefault("service", stripped.getOrDefault("container", "unknown"));
            JsonNode values = stream.path("values");
            if (!values.isArray()) {
                continue;
            }
            for (JsonNode entry : values) {
                if (!entry.isArray() || entry.size() < 2) {
                    continue;
                }
                Instant ts = parseNanosecondTs(entry.get(0).asText("0"));
                String raw = entry.get(1).asText("");
                String level = extractLevel(raw, stripped.get("level"));
                out.add(new LogEntry(ts, service, level, raw));
            }
        }
        return out;
    }

    private static Map<String, String> readLabels(JsonNode stream) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        if (stream == null || !stream.isObject()) {
            return labels;
        }
        Iterator<Map.Entry<String, JsonNode>> it = stream.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            labels.put(e.getKey(), e.getValue().asText(""));
        }
        return labels;
    }

    private static Instant parseNanosecondTs(String s) {
        try {
            long nanos = Long.parseLong(s);
            long seconds = nanos / 1_000_000_000L;
            long nanoRemainder = nanos % 1_000_000_000L;
            return Instant.ofEpochSecond(seconds, nanoRemainder);
        } catch (NumberFormatException e) {
            return Instant.EPOCH;
        }
    }

    private static String extractLevel(String raw, String streamLevel) {
        if (streamLevel != null && !streamLevel.isBlank()) {
            return streamLevel.toUpperCase(java.util.Locale.ROOT);
        }
        // Lightweight scan for a `"level":"..."` token in the JSON body. The
        // canonical JSON pipeline emits the field; Loki's `| json` stage
        // doesn't surface it as a stream label by default per ADR-15 §11.
        int idx = raw.indexOf("\"level\"");
        if (idx >= 0) {
            int colon = raw.indexOf(':', idx);
            if (colon > 0) {
                int quote = raw.indexOf('"', colon + 1);
                if (quote > 0) {
                    int end = raw.indexOf('"', quote + 1);
                    if (end > quote) {
                        return raw.substring(quote + 1, end).toUpperCase(java.util.Locale.ROOT);
                    }
                }
            }
        }
        return "INFO";
    }
}
