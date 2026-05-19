package com.playground.metrics.domain;

import java.time.Instant;

/**
 * Service health-grid cell value object — composed by
 * {@code BuildServicesUseCase} from the §9 verdict rule and emitted in the
 * dashboard payload's {@code services[]} array per spec §5.2.
 *
 * @param name service identifier (one of {@link ServiceAllowlist})
 * @param status {@link HealthVerdict.Status} token: {@code up} / {@code degraded} / {@code down}
 * @param since when the service most recently became reachable (nullable)
 * @param uptimeSec elapsed seconds since {@code since} (nullable when down)
 * @param image container image tag (nullable when unknown)
 * @param latencyP95Ms spark-inference-gateway only — p95 of recent calls (nullable)
 * @param note human-readable note (e.g., {@code "elevated latency"} for degraded spark)
 */
public record ServiceHealth(
        String name,
        String status,
        Instant since,
        Long uptimeSec,
        String image,
        Long latencyP95Ms,
        String note) {

    public static ServiceHealth simple(String name, HealthVerdict.Status status) {
        return new ServiceHealth(name, status.token(), null, null, null, null, null);
    }
}
