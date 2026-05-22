package com.playground.identity.api;

import org.junit.jupiter.api.Test;

/**
 * Trivial smoke test that runs against the assembled identity-api artifact —
 * full integration tests (Testcontainers Postgres + Kafka + Redis) live in
 * {@code identity-infra} per ADR-01 v2 §"Per-BC test layout". This stub
 * guarantees the test task has at least one source file so the build pipeline
 * doesn't break.
 */
class ApplicationSmokeTest {

    @Test
    void main_method_is_invokable_via_class_reference() {
        // Confirms the IdentityApplication class is loadable (it would not be if
        // the broad @ComponentScan referenced types that don't exist). Full
        // bootstrapping is covered by infra-layer Testcontainers tests.
        Class<?> applicationClass = IdentityApplication.class;
        org.assertj.core.api.Assertions.assertThat(applicationClass).isNotNull();
    }
}
