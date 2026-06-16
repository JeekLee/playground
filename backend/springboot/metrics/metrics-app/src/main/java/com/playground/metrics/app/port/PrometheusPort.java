package com.playground.metrics.app.port;

import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.PrometheusSeries;
import com.playground.metrics.domain.Range;
import com.playground.metrics.domain.Step;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Application port (DDD outbound port) per ADR-02 — implemented in
 * {@code metrics-infra} by a Spring {@code WebClient} adapter against
 * {@code http://playground-prometheus:9090} per ADR-15 §7 + §10.
 *
 * <p>The adapter handles JSON decoding and per-query 8s timeout (ADR-15 §7).
 * The use case decides which PromQL to run (via
 * {@link com.playground.metrics.domain.PromQlTemplate}).
 */
public interface PrometheusPort {

    /**
     * Run an instant query against {@code /api/v1/query}. Returns the single
     * latest sample per result series. Empty list = no data.
     */
    Mono<List<PrometheusSample>> instantQuery(String promql);

    /**
     * Run a range query against {@code /api/v1/query_range}. Returns one
     * {@link PrometheusSeries} per result label-set with all points in the
     * requested {@code [now-range, now]} window at the given {@code step}.
     */
    Mono<List<PrometheusSeries>> rangeQuery(String promql, Range range, Step step);
}
