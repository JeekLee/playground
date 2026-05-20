package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@link ServiceProbeTarget#ALL} catalog per ADR-15 §17 — eleven
 * cells in canonical order, with the per-cell probe metadata (URL + kind)
 * matching the ADR's mappings.
 */
class ServiceProbeTargetTest {

    @Test
    void allCatalogHasElevenCellsInCanonicalOrder() {
        assertThat(ServiceProbeTarget.ALL).hasSize(11);
        assertThat(ServiceProbeTarget.ALL.stream().map(ServiceProbeTarget::name).toList())
                .containsExactly(
                        "gateway",
                        "identity-api",
                        "docs-api",
                        "rag-ingestion-api",
                        "rag-chat-api",
                        "metrics-api",
                        "spark-inference-gateway",
                        "prometheus-playground",
                        "loki-playground",
                        "alloy-playground",
                        "cadvisor-playground");
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
        assertThat(probeOf("prometheus-playground").probeUrl())
                .isEqualTo("http://prometheus-playground:9090/-/healthy");
        assertThat(probeOf("loki-playground").probeUrl())
                .isEqualTo("http://loki-playground:3100/ready");
        assertThat(probeOf("alloy-playground").probeUrl())
                .isEqualTo("http://alloy-playground:12345/-/ready");
        assertThat(probeOf("cadvisor-playground").probeUrl())
                .isEqualTo("http://cadvisor-playground:8080/healthz");
    }

    @Test
    void bcUrlsTargetComposeInternalPortsNotGateway() {
        // ADR-15 §9 — probe URLs MUST be compose-internal (avoids
        // self-referential loops on gateway health).
        assertThat(probeOf("gateway").probeUrl())
                .isEqualTo("http://gateway:18080/actuator/health");
        assertThat(probeOf("identity-api").probeUrl())
                .isEqualTo("http://identity-api:18081/actuator/health");
        assertThat(probeOf("docs-api").probeUrl())
                .isEqualTo("http://docs-api:18082/actuator/health");
        assertThat(probeOf("rag-ingestion-api").probeUrl())
                .isEqualTo("http://rag-ingestion-api:18083/actuator/health");
        assertThat(probeOf("rag-chat-api").probeUrl())
                .isEqualTo("http://rag-chat-api:18084/actuator/health");
        assertThat(probeOf("metrics-api").probeUrl())
                .isEqualTo("http://metrics-api:18085/actuator/health");
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
