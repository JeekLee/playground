package com.playground.metrics.domain;

/**
 * Opaque metric identifier carried in the {@code metric=} query parameter on
 * {@code GET /api/metrics/timeseries}. The id is validated against
 * {@link PromQlTemplate} before any PromQL substitution; an unknown id maps
 * to {@code METRICS-VALIDATION-001} (400) per ADR-15 §C.
 *
 * <p>Format: {@code <key>-<arg>} where {@code <key>} is one of the
 * fixed prefixes listed in ADR-15 §10 (e.g., {@code jvm-heap},
 * {@code http-rate}, {@code container-cpu}) and {@code <arg>} is the
 * substitution value (a service name or container name) validated against
 * {@link ServiceAllowlist} / {@link ContainerAllowlist}. Single-token ids
 * (e.g., {@code host-cpu}, {@code spark-latency}) carry no argument.
 */
public record MetricId(String raw) {

    public MetricId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("metric id must not be blank");
        }
    }
}
