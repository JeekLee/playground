package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AllowlistTest {

    @Test
    void serviceAllowlistIncludesAllActiveBCs() {
        assertThat(ServiceAllowlist.contains("playground-backend-gateway")).isTrue();
        assertThat(ServiceAllowlist.contains("playground-backend-identity-api")).isTrue();
        assertThat(ServiceAllowlist.contains("playground-backend-docs-api")).isTrue();
        assertThat(ServiceAllowlist.contains("playground-backend-chat-api")).isTrue();
        assertThat(ServiceAllowlist.contains("playground-backend-metrics-api")).isTrue();
    }

    @Test
    void serviceAllowlistIncludesObservabilityContainers() {
        assertThat(ServiceAllowlist.contains("playground-prometheus")).isTrue();
        assertThat(ServiceAllowlist.contains("playground-loki")).isTrue();
        assertThat(ServiceAllowlist.contains("playground-alloy")).isTrue();
        assertThat(ServiceAllowlist.contains("playground-cadvisor")).isTrue();
    }

    @Test
    void serviceAllowlistIncludesSparkExternalWhitelist() {
        // ADR-15 §12 — spark-inference-gateway는 별도 stack (prefix 없음).
        assertThat(ServiceAllowlist.contains("spark-inference-gateway")).isTrue();
    }

    @Test
    void serviceAllowlistAcceptsNewPrefixedNames() {
        // 정규식 화이트리스트 → 새 BC가 prefix 규칙만 따르면 자동 인식 (P0).
        assertThat(ServiceAllowlist.contains("playground-backend-new-bc-api")).isTrue();
        assertThat(ServiceAllowlist.contains("playground-postgres-replica")).isTrue();
    }

    @Test
    void serviceAllowlistRejectsUnknown() {
        assertThat(ServiceAllowlist.contains("foo-api")).isFalse();
        assertThat(ServiceAllowlist.contains(null)).isFalse();
        assertThat(ServiceAllowlist.contains("")).isFalse();
        // 옛 이름 (prefix 없음)도 거부 — 새 schema로 강제 마이그레이션.
        assertThat(ServiceAllowlist.contains("gateway")).isFalse();
        assertThat(ServiceAllowlist.contains("postgres-playground")).isFalse();
    }

    @Test
    void serviceAllowlistRejectsInjectionAttempts() {
        // PromQL/LogQL injection 방어 — 메타문자 차단.
        assertThat(ServiceAllowlist.contains("playground-evil\"-or-1=1")).isFalse();
        assertThat(ServiceAllowlist.contains("playground-foo,bar")).isFalse();
        assertThat(ServiceAllowlist.contains("playground-foo{bar=baz}")).isFalse();
    }

    @Test
    void containerAllowlistIncludesInfra() {
        assertThat(ContainerAllowlist.contains("playground-postgres")).isTrue();
        assertThat(ContainerAllowlist.contains("playground-redis")).isTrue();
        assertThat(ContainerAllowlist.contains("playground-kafka-broker")).isTrue();
        assertThat(ContainerAllowlist.contains("playground-opensearch")).isTrue();
        // kafka-init은 KNOWN_ENTRIES에선 빠졌지만 prefix regex는 통과
        // (PromQL 쿼리 자체는 가능). dashboard 카드는 표시 안 됨.
        assertThat(ContainerAllowlist.contains("playground-kafka-init")).isTrue();
    }

    @Test
    void containerAllowlistIncludesFrontend() {
        assertThat(ContainerAllowlist.contains("playground-frontend")).isTrue();
    }

    @Test
    void containerAllowlistRejectsUnknown() {
        assertThat(ContainerAllowlist.contains("foo-container")).isFalse();
        // 옛 이름 거부.
        assertThat(ContainerAllowlist.contains("postgres-playground")).isFalse();
    }
}
