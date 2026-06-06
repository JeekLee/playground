package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromQlTemplateTest {

    @Test
    void resolveJvmHeapForKnownService() {
        String promql = PromQlTemplate.resolve("jvm-heap-playground-backend-chat-api");
        assertThat(promql).isEqualTo(
                "jvm_memory_used_bytes{area=\"heap\",service=\"playground-backend-chat-api\"} / 1048576");
    }

    @Test
    void resolveContainerCpuForKnownContainer() {
        String promql = PromQlTemplate.resolve("container-cpu-playground-postgres");
        assertThat(promql).isEqualTo(
                "rate(container_cpu_usage_seconds_total{name=\"playground-postgres\"}[1m]) * 100");
    }

    @Test
    void resolveBareHostCpu() {
        String promql = PromQlTemplate.resolve("host-cpu");
        assertThat(promql).contains("node_cpu_seconds_total");
    }

    @Test
    void resolveJvmGcPauseFillsRepeatedPlaceholder() {
        // Template uses %s twice; both must point to the same caller-supplied svc.
        String promql = PromQlTemplate.resolve("jvm-gc-pause-playground-backend-chat-api");
        assertThat(promql).isEqualTo(
                "rate(jvm_gc_pause_seconds_sum{service=\"playground-backend-chat-api\"}[5m]) "
                        + "/ rate(jvm_gc_pause_seconds_count{service=\"playground-backend-chat-api\"}[5m])");
    }

    @Test
    void unknownMetricIdThrows() {
        assertThatThrownBy(() -> PromQlTemplate.resolve("foo-bar-not-allowlisted"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    void unknownServiceInSubstitutionThrows() {
        // jvm-heap is a known prefix but "evil-svc" isn't in ServiceAllowlist
        assertThatThrownBy(() -> PromQlTemplate.resolve("jvm-heap-evil-svc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown service");
    }

    @Test
    void unknownContainerInSubstitutionThrows() {
        assertThatThrownBy(() -> PromQlTemplate.resolve("container-cpu-evil-container"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown container");
    }

    @Test
    void injectionAttemptViaMetricIdRejected() {
        // Story 9 attack: try to inject by tacking PromQL onto the metric id
        assertThatThrownBy(() -> PromQlTemplate.resolve(
                "jvm-heap-playground-backend-chat-api\"}|sum() OR x{"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyMetricIdThrows() {
        assertThatThrownBy(() -> PromQlTemplate.resolve(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromQlTemplate.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serviceUpForKnownService() {
        assertThat(PromQlTemplate.serviceUp("playground-backend-docs-api"))
                .isEqualTo("up{service=\"playground-backend-docs-api\"}");
    }

    @Test
    void serviceUpRejectsUnknown() {
        assertThatThrownBy(() -> PromQlTemplate.serviceUp("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void templateOfReturnsUnitForKnownMetric() {
        assertThat(PromQlTemplate.templateOf("jvm-heap-playground-backend-chat-api"))
                .isPresent()
                .get()
                .extracting(PromQlTemplate.Template::unit)
                .isEqualTo("MB");
    }

    @Test
    void serviceUpLastTwoScrapesForKnownService() {
        // max by (service) (sum_over_time(up[12s])) — multiple-job emit
        // (예: alloy가 prometheus job + observability_self job에서 동시 scrape)
        // 시 가장 큰 값으로 정규화. 0/1/2를 HealthVerdict.from에 feed.
        assertThat(PromQlTemplate.serviceUpLastTwoScrapes("playground-backend-docs-api"))
                .isEqualTo(
                        "max by (service) (sum_over_time("
                                + "up{service=\"playground-backend-docs-api\"}[12s]))");
    }

    @Test
    void serviceUpLastTwoScrapesRejectsUnknown() {
        assertThatThrownBy(() -> PromQlTemplate.serviceUpLastTwoScrapes("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
