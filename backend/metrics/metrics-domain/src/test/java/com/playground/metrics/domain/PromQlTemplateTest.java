package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PromQlTemplateTest {

    @Test
    void resolveJvmHeapForKnownService() {
        String promql = PromQlTemplate.resolve("jvm-heap-rag-chat-api");
        assertThat(promql).isEqualTo(
                "jvm_memory_used_bytes{area=\"heap\",service=\"rag-chat-api\"} / 1048576");
    }

    @Test
    void resolveContainerCpuForKnownContainer() {
        String promql = PromQlTemplate.resolve("container-cpu-postgres-playground");
        assertThat(promql).isEqualTo(
                "rate(container_cpu_usage_seconds_total{name=\"postgres-playground\"}[1m]) * 100");
    }

    @Test
    void resolveBareHostCpu() {
        String promql = PromQlTemplate.resolve("host-cpu");
        assertThat(promql).contains("node_cpu_seconds_total");
    }

    @Test
    void resolveJvmGcPauseFillsRepeatedPlaceholder() {
        // Template uses %s twice; both must point to the same caller-supplied svc.
        String promql = PromQlTemplate.resolve("jvm-gc-pause-rag-chat-api");
        assertThat(promql).isEqualTo(
                "rate(jvm_gc_pause_seconds_sum{service=\"rag-chat-api\"}[5m]) "
                        + "/ rate(jvm_gc_pause_seconds_count{service=\"rag-chat-api\"}[5m])");
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
                "jvm-heap-rag-chat-api\"}|sum() OR x{"))
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
        assertThat(PromQlTemplate.serviceUp("docs-api"))
                .isEqualTo("up{service=\"docs-api\"}");
    }

    @Test
    void serviceUpRejectsUnknown() {
        assertThatThrownBy(() -> PromQlTemplate.serviceUp("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void templateOfReturnsUnitForKnownMetric() {
        assertThat(PromQlTemplate.templateOf("jvm-heap-rag-chat-api"))
                .isPresent()
                .get()
                .extracting(PromQlTemplate.Template::unit)
                .isEqualTo("MB");
    }

    @Test
    void serviceUpLastTwoScrapesForKnownService() {
        // sum_over_time(up[12s]) — feeds 0/1/2 directly into HealthVerdict.from
        assertThat(PromQlTemplate.serviceUpLastTwoScrapes("docs-api"))
                .isEqualTo("sum_over_time(up{service=\"docs-api\"}[12s])");
    }

    @Test
    void serviceUpLastTwoScrapesRejectsUnknown() {
        assertThatThrownBy(() -> PromQlTemplate.serviceUpLastTwoScrapes("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
