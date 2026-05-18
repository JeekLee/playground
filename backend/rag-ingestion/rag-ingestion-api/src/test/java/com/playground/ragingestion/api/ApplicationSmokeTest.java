package com.playground.ragingestion.api;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Trivial smoke test that runs against the assembled rag-ingestion-api
 * artifact — full integration tests (Testcontainers Postgres + Kafka + Redis
 * + WireMock) live in {@code rag-ingestion-infra} per ADR-01 v2 §"Per-BC
 * test layout". This stub guarantees the test task has at least one source
 * file so the build pipeline doesn't break, mirrors docs-api's
 * {@code ApplicationSmokeTest}.
 */
class ApplicationSmokeTest {

    @Test
    void main_method_is_invokable_via_class_reference() {
        Class<?> applicationClass = RagIngestionApplication.class;
        Assertions.assertThat(applicationClass).isNotNull();
    }
}
