package com.playground.metrics.api.controller;

import com.playground.metrics.app.BuildTimeseriesUseCase;
import com.playground.metrics.app.dto.TimeseriesResponse;
import com.playground.metrics.domain.Range;
import com.playground.metrics.domain.Step;
import com.playground.metrics.domain.exception.MetricsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Reactive controller for {@code GET /api/metrics/timeseries} per spec §5.1.
 * Gateway routes {@code /api/metrics/**} with {@code StripPrefix=2}, so this
 * controller listens on {@code /timeseries}.
 *
 * <p>The {@code metric} query parameter is validated against the PromQL
 * whitelist + allowlist by {@link BuildTimeseriesUseCase}; unknown ids
 * surface as 400 ({@code METRICS-VALIDATION-001}).
 */
@RestController
@RequiredArgsConstructor
public class TimeseriesController {

    private final BuildTimeseriesUseCase useCase;

    @GetMapping(value = {"/timeseries", "/api/metrics/timeseries"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TimeseriesResponse> timeseries(
            @RequestParam("metric") String metric,
            @RequestParam(value = "range", required = false) String rangeToken,
            @RequestParam(value = "step", required = false) String stepToken) {
        Range range;
        try {
            range = Range.parseOrDefault(rangeToken);
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.of(MetricsErrorCode.INVALID_RANGE, rangeToken).build();
        }
        Step step;
        try {
            step = Step.parseOrDefault(stepToken);
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.of(MetricsErrorCode.INVALID_STEP, stepToken).build();
        }
        return useCase.execute(metric, range, step);
    }
}
