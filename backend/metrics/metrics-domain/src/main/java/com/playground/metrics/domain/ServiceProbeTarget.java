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
        SPARK
    }

    /**
     * Eleven cells in ADR-15 §17 canonical order: 6 BCs, then spark, then 4
     * observability containers. The order matches the dashboard's grid
     * left-to-right / top-to-bottom layout.
     */
    public static final List<ServiceProbeTarget> ALL = List.of(
            // 6 BCs — actuator/health on compose-internal port (NOT via gateway)
            new ServiceProbeTarget("gateway",            Kind.BC, "http://gateway:18080/actuator/health",          true),
            new ServiceProbeTarget("identity-api",       Kind.BC, "http://identity-api:18081/actuator/health",     true),
            new ServiceProbeTarget("docs-api",           Kind.BC, "http://docs-api:18082/actuator/health",         true),
            new ServiceProbeTarget("rag-ingestion-api",  Kind.BC, "http://rag-ingestion-api:18083/actuator/health",true),
            new ServiceProbeTarget("rag-chat-api",       Kind.BC, "http://rag-chat-api:18084/actuator/health",     true),
            new ServiceProbeTarget("metrics-api",        Kind.BC, "http://metrics-api:18085/actuator/health",      true),
            // spark-inference-gateway — HEAD /v1/models per ADR-15 §12 (host process; no scrape, no actuator)
            new ServiceProbeTarget("spark-inference-gateway", Kind.SPARK, null, false),
            // 4 observability containers — native readiness endpoints per ADR-15 §17
            new ServiceProbeTarget("prometheus-playground", Kind.OBSERVABILITY, "http://prometheus-playground:9090/-/healthy", true),
            new ServiceProbeTarget("loki-playground",       Kind.OBSERVABILITY, "http://loki-playground:3100/ready",          true),
            new ServiceProbeTarget("alloy-playground",      Kind.OBSERVABILITY, "http://alloy-playground:12345/-/ready",      true),
            new ServiceProbeTarget("cadvisor-playground",   Kind.OBSERVABILITY, "http://cadvisor-playground:8080/healthz",    true));
}
