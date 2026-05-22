package com.playground.metrics.app.dto;

/**
 * Result of the HEAD {@code /v1/models} probe against
 * spark-inference-gateway per ADR-15 §12.
 *
 * @param reachable HEAD completed without timeout / connection refused
 * @param ok HEAD returned 200 or 204
 */
public record SparkProbeResult(boolean reachable, boolean ok) {

    public static SparkProbeResult down() {
        return new SparkProbeResult(false, false);
    }

    public static SparkProbeResult up() {
        return new SparkProbeResult(true, true);
    }
}
