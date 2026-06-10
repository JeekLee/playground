package com.playground.metrics.app;

import com.playground.metrics.app.dto.PrometheusSeries;
import com.playground.metrics.app.dto.TimeseriesResponse;
import com.playground.metrics.app.dto.TimeseriesResponse.Point;
import com.playground.metrics.app.dto.TimeseriesResponse.Series;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.domain.PromQlTemplate;
import com.playground.metrics.domain.PromQlTemplate.Template;
import com.playground.metrics.domain.Range;
import com.playground.metrics.domain.Step;
import com.playground.metrics.domain.TimeseriesPoint;
import com.playground.metrics.domain.exception.MetricsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Single-chart timeseries query per spec §5.3. Looks up the PromQL template
 * for the requested {@code metric} id, runs the range query, and shapes the
 * response per spec §5.3.
 *
 * <p>Injection defense: {@link PromQlTemplate#resolve(String)} performs both
 * the metric-id lookup and the {@code <svc>} / {@code <name>} allowlist
 * check. An unknown id throws {@link IllegalArgumentException}; this use
 * case maps it to {@link MetricsErrorCode#UNKNOWN_METRIC} (400) per
 * ADR-15 §C.
 */
@Service
@RequiredArgsConstructor
public class BuildTimeseriesUseCase {

    private final PrometheusPort prometheus;

    public Mono<TimeseriesResponse> execute(String metricId, Range range, Step step) {
        String promql;
        Template template;
        try {
            promql = PromQlTemplate.resolve(metricId);
            template = PromQlTemplate.templateOf(metricId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown metric id: " + metricId));
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.of(MetricsErrorCode.UNKNOWN_METRIC, metricId).build();
        }

        return prometheus.rangeQuery(promql, range, step)
                .map(seriesList -> new TimeseriesResponse(
                        metricId,
                        range.token(),
                        step.token(),
                        toSeries(seriesList),
                        template.unit()));
    }

    private static List<Series> toSeries(List<PrometheusSeries> seriesList) {
        return seriesList.stream()
                .map(BuildTimeseriesUseCase::toSeries)
                .toList();
    }

    private static Series toSeries(PrometheusSeries series) {
        String label = labelOf(series);
        List<Point> points = series.points().stream().map(BuildTimeseriesUseCase::toPoint).toList();
        return new Series(label, points);
    }

    private static String labelOf(PrometheusSeries series) {
        // 우선순위: service > name > uri > __name__ > sorted-labels-join > "value".
        // `uri`는 spark-latency-p95처럼 `by (le, uri)`로 그룹핑된 시리즈에 의미 있는
        // 라벨 (예: `/v1/embeddings`, `/v1/chat/completions`). 이전엔 누락돼서
        // spark widget의 두 line이 항상 같은 "value" 라벨을 받아 차트가 그려지지
        // 않았음 (이전 리뷰 §2.6).
        if (series.labels().containsKey("service")) {
            return series.labels().get("service");
        }
        if (series.labels().containsKey("name")) {
            return series.labels().get("name");
        }
        if (series.labels().containsKey("uri")) {
            return series.labels().get("uri");
        }
        if (series.labels().containsKey("__name__")) {
            return series.labels().get("__name__");
        }
        // Fallback: 라벨 set 자체를 키로 (deterministic 정렬). 빈 라벨은 "value".
        if (series.labels().isEmpty()) {
            return "value";
        }
        return series.labels().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static Point toPoint(TimeseriesPoint p) {
        return new Point(p.ts(), p.value());
    }
}
