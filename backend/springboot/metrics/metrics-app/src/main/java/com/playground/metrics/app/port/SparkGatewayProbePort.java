package com.playground.metrics.app.port;

import com.playground.metrics.app.dto.SparkProbeResult;
import reactor.core.publisher.Mono;

/**
 * Application port for the spark-inference-gateway HEAD probe per ADR-15 §12.
 * Implemented in {@code metrics-infra} via a Spring {@code WebClient}
 * issuing HEAD against {@code http://host.docker.internal:10080/v1/models}
 * with a 2-second timeout and a 15-second in-process Caffeine cache.
 */
public interface SparkGatewayProbePort {

    /**
     * Returns the cached probe result (HEAD {@code /v1/models}). Cache TTL
     * is 15 seconds (matches dashboard polling interval).
     */
    Mono<SparkProbeResult> probe();

    /**
     * Returns the list of models the gateway reports as loaded (via GET
     * {@code /v1/models} body). Cached for 15 seconds. Empty list on failure.
     */
    Mono<java.util.List<String>> listModels();
}
