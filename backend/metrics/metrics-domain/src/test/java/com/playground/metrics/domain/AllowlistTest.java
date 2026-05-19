package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AllowlistTest {

    @Test
    void serviceAllowlistIncludesAllSixBCs() {
        assertThat(ServiceAllowlist.contains("gateway")).isTrue();
        assertThat(ServiceAllowlist.contains("identity-api")).isTrue();
        assertThat(ServiceAllowlist.contains("docs-api")).isTrue();
        assertThat(ServiceAllowlist.contains("rag-ingestion-api")).isTrue();
        assertThat(ServiceAllowlist.contains("rag-chat-api")).isTrue();
        assertThat(ServiceAllowlist.contains("metrics-api")).isTrue();
    }

    @Test
    void serviceAllowlistIncludesObservabilityContainers() {
        assertThat(ServiceAllowlist.contains("prometheus-playground")).isTrue();
        assertThat(ServiceAllowlist.contains("loki-playground")).isTrue();
        assertThat(ServiceAllowlist.contains("alloy-playground")).isTrue();
        assertThat(ServiceAllowlist.contains("cadvisor-playground")).isTrue();
    }

    @Test
    void serviceAllowlistRejectsUnknown() {
        assertThat(ServiceAllowlist.contains("foo-api")).isFalse();
        assertThat(ServiceAllowlist.contains(null)).isFalse();
        assertThat(ServiceAllowlist.contains("")).isFalse();
    }

    @Test
    void containerAllowlistIncludesInfra() {
        assertThat(ContainerAllowlist.contains("postgres-playground")).isTrue();
        assertThat(ContainerAllowlist.contains("redis-playground")).isTrue();
        assertThat(ContainerAllowlist.contains("kafka-playground")).isTrue();
        assertThat(ContainerAllowlist.contains("opensearch-playground")).isTrue();
    }

    @Test
    void containerAllowlistRejectsUnknown() {
        assertThat(ContainerAllowlist.contains("foo-container")).isFalse();
    }
}
