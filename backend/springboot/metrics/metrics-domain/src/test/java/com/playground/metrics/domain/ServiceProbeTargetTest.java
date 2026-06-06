package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@link ServiceProbeTarget#ALL} catalog per ADR-15 §17 — eleven
 * cells in canonical order, with the per-cell probe metadata (URL + kind)
 * matching the ADR's mappings.
 *
 * <p>2026-05-21: name + probeUrl이 container_name prefix 규칙 (ADR-15 §G)로
 * 통일된 후 테스트 갱신.
 */
class ServiceProbeTargetTest {

    @Test
    void allCatalogHasSixteenCellsInCanonicalOrder() {
        assertThat(ServiceProbeTarget.ALL).hasSize(16);
        assertThat(ServiceProbeTarget.ALL.stream().map(ServiceProbeTarget::name).toList())
                .containsExactly(
                        "playground-backend-gateway",
                        "playground-backend-identity-api",
                        "playground-backend-docs-api",
                        "playground-backend-rag-ingestion-api",
                        "playground-backend-chat-api",
                        "playground-backend-metrics-api",
                        "spark-inference-gateway",
                        "playground-prometheus",
                        "playground-loki",
                        "playground-alloy",
                        "playground-cadvisor",
                        "playground-frontend",
                        "playground-postgres",
                        "playground-redis",
                        "playground-kafka-broker",
                        "playground-opensearch");
    }

    @Test
    void stackCatalogHasFiveCellsWithNoProbeUrl() {
        long stack = ServiceProbeTarget.ALL.stream()
                .filter(t -> t.kind() == ServiceProbeTarget.Kind.STACK).count();
        assertThat(stack).isEqualTo(5);
        ServiceProbeTarget.ALL.stream()
                .filter(t -> t.kind() == ServiceProbeTarget.Kind.STACK)
                .forEach(t -> {
                    assertThat(t.probeUrl()).as(t.name()).isNull();
                    assertThat(t.scrapeMonitored()).as(t.name()).isFalse();
                });
    }

    @Test
    void sixBcsAreKindBcWithActuatorPath() {
        ServiceProbeTarget.ALL.stream()
                .filter(t -> t.kind() == ServiceProbeTarget.Kind.BC)
                .forEach(t -> {
                    assertThat(t.probeUrl()).as(t.name()).endsWith("/actuator/health");
                    assertThat(t.scrapeMonitored()).as(t.name()).isTrue();
                });
        long bcs = ServiceProbeTarget.ALL.stream()
                .filter(t -> t.kind() == ServiceProbeTarget.Kind.BC).count();
        assertThat(bcs).isEqualTo(6);
    }

    @Test
    void sparkIsKindSparkWithNoScrapeOrProbeUrl() {
        ServiceProbeTarget spark = ServiceProbeTarget.ALL.stream()
                .filter(t -> t.name().equals("spark-inference-gateway"))
                .findFirst().orElseThrow();
        assertThat(spark.kind()).isEqualTo(ServiceProbeTarget.Kind.SPARK);
        assertThat(spark.probeUrl()).isNull();
        assertThat(spark.scrapeMonitored()).isFalse();
    }

    @Test
    void observabilityContainersUseNativeReadinessPaths() {
        // ADR-15 §17: Prometheus /-/healthy, Loki /ready, Alloy /-/ready, cAdvisor /healthz.
        assertThat(probeOf("playground-prometheus").probeUrl())
                .isEqualTo("http://playground-prometheus:9090/-/healthy");
        assertThat(probeOf("playground-loki").probeUrl())
                .isEqualTo("http://playground-loki:3100/ready");
        assertThat(probeOf("playground-alloy").probeUrl())
                .isEqualTo("http://playground-alloy:12345/-/ready");
        assertThat(probeOf("playground-cadvisor").probeUrl())
                .isEqualTo("http://playground-cadvisor:8080/healthz");
    }

    @Test
    void bcUrlsTargetComposeInternalPortsNotGateway() {
        // ADR-15 §9 — probe URLs MUST be compose-internal (avoids
        // self-referential loops on gateway health).
        assertThat(probeOf("playground-backend-gateway").probeUrl())
                .isEqualTo("http://playground-backend-gateway:18080/actuator/health");
        assertThat(probeOf("playground-backend-identity-api").probeUrl())
                .isEqualTo("http://playground-backend-identity-api:18081/actuator/health");
        assertThat(probeOf("playground-backend-docs-api").probeUrl())
                .isEqualTo("http://playground-backend-docs-api:18082/actuator/health");
        assertThat(probeOf("playground-backend-rag-ingestion-api").probeUrl())
                .isEqualTo("http://playground-backend-rag-ingestion-api:18083/actuator/health");
        assertThat(probeOf("playground-backend-chat-api").probeUrl())
                .isEqualTo("http://playground-backend-chat-api:18084/actuator/health");
        assertThat(probeOf("playground-backend-metrics-api").probeUrl())
                .isEqualTo("http://playground-backend-metrics-api:18085/actuator/health");
    }

    @Test
    void everyTargetNameIsInServiceAllowlist() {
        // Guardrail: the probe catalog stays a subset of the PromQL allowlist
        // so `up{service="x"}` never queries an unknown label value.
        ServiceProbeTarget.ALL.forEach(t -> assertThat(ServiceAllowlist.contains(t.name()))
                .as("ServiceAllowlist missing " + t.name())
                .isTrue());
    }

    private static ServiceProbeTarget probeOf(String name) {
        return ServiceProbeTarget.ALL.stream()
                .filter(t -> t.name().equals(name))
                .findFirst().orElseThrow();
    }
}
