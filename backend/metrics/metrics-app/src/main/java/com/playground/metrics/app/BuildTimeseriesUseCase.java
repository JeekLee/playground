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
public class BuildTimeseriesUseCase {

    private final PrometheusPort prometheus;

    public BuildTimeseriesUseCase(PrometheusPort prometheus) {
        this.prometheus = prometheus;
    }

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
        // Prefer a single descriptive label — service > name > __name__ > "value".
        if (series.labels().containsKey("service")) {
            return series.labels().get("service");
        }
        if (series.labels().containsKey("name")) {
            return series.labels().get("name");
        }
        if (series.labels().containsKey("__name__")) {
            return series.labels().get("__name__");
        }
        return "value";
    }

    private static Point toPoint(TimeseriesPoint p) {
        return new Point(p.ts(), p.value());
    }
}
