package com.playground.metrics.domain;

import java.util.List;

/**
 * Probe metadata for a single service-health-grid cell per ADR-15 §17 + §9.
 * Captures the eleven cells the BC monitors and the secondary-signal URL
 * each cell uses.
 *
 * <ul>
 *   <li>{@link Kind#BC} — six Spring Boot apps. Secondary signal is Spring
 *       Actuator {@code /actuator/health}; the response body's {@code status}
 *       field is parsed (200 + body {@code "UP"} ⇒ healthy). The probe URL
 *       targets the compose-internal hostname:port, NOT the gateway, to
 *       avoid a self-referential loop on gateway health (ADR-15 §9).</li>
 *   <li>{@link Kind#OBSERVABILITY} — Prometheus, Loki, Alloy, cAdvisor.
 *       Secondary signal is the tool-native readiness endpoint
 *       ({@code /-/healthy}, {@code /ready}, {@code /-/ready}, {@code /healthz}).
 *       A 2xx response means healthy — no body parsing.</li>
 *   <li>{@link Kind#SPARK} — spark-inference-gateway. No Prometheus scrape
 *       (host process), no actuator. Verdict comes from the HEAD {@code /v1/models}
 *       probe via {@code SparkGatewayProbePort} per ADR-15 §12. The
 *       {@code probeUrl} is unused (returned for diagnostic completeness only).</li>
 * </ul>
 *
 * <p>Constants are listed in ADR-15 §17's canonical order: six BCs → spark →
 * four observability containers. {@code BuildServicesUseCase} consumes
 * {@link #ALL} verbatim so the response array order is stable.
 *
 * <p>2026-05-21 amendment (ADR-15 §G): name 필드는 container_name과 일치하는
 * {@code playground-*} prefix로 통일. probeUrl도 container_name 기반 hostname
 * 사용 — compose의 service key DNS와 container_name DNS 둘 다 살아있지만,
 * 라벨/이름이 모든 경로에서 일관되도록 container_name으로 통일.
 *
 * @param name service identifier matching {@link ServiceAllowlist}
 * @param kind probe shape selector (see {@link Kind})
 * @param probeUrl absolute URL of the secondary-signal endpoint, or
 *     {@code null} for {@link Kind#SPARK}
 * @param scrapeMonitored {@code true} when this service has a Prometheus
 *     scrape job (every BC and observability container; {@code false} for
 *     spark-inference-gateway)
 */
public record ServiceProbeTarget(String name, Kind kind, String probeUrl, boolean scrapeMonitored) {

    public enum Kind {
        /** Six Spring Boot BCs — parse {@code /actuator/health} JSON body. */
        BC,
        /** Four observability containers — 2xx on the tool-native readiness path. */
        OBSERVABILITY,
        /** spark-inference-gateway — HEAD probe via {@code SparkGatewayProbePort}. */
        SPARK,
        /**
         * Stack containers (postgres, redis, kafka, opensearch, frontend) —
         * no Spring actuator, no HTTP readiness. Verdict comes from cAdvisor
         * {@code container_last_seen} age per ADR-15 §13 (amended).
         */
        STACK
    }

    /**
     * 17 cells: 6 BCs → spark → 4 observability → 6 stack containers.
     * Order matches the dashboard's grid left-to-right / top-to-bottom
     * layout (frontend filters spark into the Inference section).
     */
    public static final List<ServiceProbeTarget> ALL = List.of(
            // 6 BCs — actuator/health on compose-internal port (NOT via gateway)
            new ServiceProbeTarget("playground-backend-gateway",           Kind.BC, "http://playground-backend-gateway:18080/actuator/health",           true),
            new ServiceProbeTarget("playground-backend-identity-api",      Kind.BC, "http://playground-backend-identity-api:18081/actuator/health",      true),
            new ServiceProbeTarget("playground-backend-docs-api",          Kind.BC, "http://playground-backend-docs-api:18082/actuator/health",          true),
            new ServiceProbeTarget("playground-backend-rag-ingestion-api", Kind.BC, "http://playground-backend-rag-ingestion-api:18083/actuator/health", true),
            new ServiceProbeTarget("playground-backend-rag-chat-api",      Kind.BC, "http://playground-backend-rag-chat-api:18084/actuator/health",      true),
            new ServiceProbeTarget("playground-backend-metrics-api",       Kind.BC, "http://playground-backend-metrics-api:18085/actuator/health",       true),
            // spark-inference-gateway — HEAD /v1/models per ADR-15 §12 (host process; no scrape, no actuator)
            new ServiceProbeTarget("spark-inference-gateway", Kind.SPARK, null, false),
            // 4 observability containers — native readiness endpoints per ADR-15 §17
            new ServiceProbeTarget("playground-prometheus", Kind.OBSERVABILITY, "http://playground-prometheus:9090/-/healthy", true),
            new ServiceProbeTarget("playground-loki",       Kind.OBSERVABILITY, "http://playground-loki:3100/ready",          true),
            new ServiceProbeTarget("playground-alloy",      Kind.OBSERVABILITY, "http://playground-alloy:12345/-/ready",      true),
            new ServiceProbeTarget("playground-cadvisor",   Kind.OBSERVABILITY, "http://playground-cadvisor:8080/healthz",    true),
            // 6 stack containers — cAdvisor container_last_seen age per ADR-15 §13 (amended).
            new ServiceProbeTarget("playground-frontend",     Kind.STACK, null, false),
            new ServiceProbeTarget("playground-postgres",     Kind.STACK, null, false),
            new ServiceProbeTarget("playground-redis",        Kind.STACK, null, false),
            new ServiceProbeTarget("playground-kafka-broker", Kind.STACK, null, false),
            new ServiceProbeTarget("playground-kafka-init",   Kind.STACK, null, false),
            new ServiceProbeTarget("playground-opensearch",   Kind.STACK, null, false));
}
