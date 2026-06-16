package com.playground.metrics.app.port;

import com.playground.metrics.domain.LogEntry;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Application port for the Loki adapter (ADR-15 §7 + §11). Implemented in
 * {@code metrics-infra} via a Spring {@code WebClient} against
 * {@code http://playground-loki:3100/loki/api/v1/query_range}. The adapter
 * carries a 15-second timeout (ADR-15 §7).
 *
 * <p>The use case decides the LogQL (via {@link com.playground.metrics.domain.LogQlTemplate})
 * and the {@code since} window.
 */
public interface LokiPort {

    /**
     * Run a Loki {@code /loki/api/v1/query_range} call and return at most
     * {@code limit} entries within the last {@code since} window.
     *
     * @param logql validated LogQL string (per {@link com.playground.metrics.domain.LogQlTemplate})
     * @param since lookback window (e.g., {@code PT15M})
     * @param limit maximum number of entries to return (clamped by the BC's call site)
     */
    Mono<List<LogEntry>> queryRange(String logql, Duration since, int limit);
}
