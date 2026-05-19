package com.playground.metrics.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The {@code <name>} substitution allowlist per ADR-15 §10. The same
 * fail-closed validation contract as {@link ServiceAllowlist} — caller-supplied
 * container names that fail this check cause the controller to return 400
 * ({@code METRICS-VALIDATION-001}) before any PromQL is composed.
 *
 * <p>P0 set: all infra containers + all BC service container names. Adding a
 * new container in a future milestone requires this set to be extended in
 * that milestone's PR.
 */
public final class ContainerAllowlist {

    private static final Set<String> ENTRIES = unmodifiable(
            // Infra
            "postgres-playground",
            "redis-playground",
            "kafka-playground",
            "opensearch-playground",
            // Observability stack (M5)
            "prometheus-playground",
            "loki-playground",
            "alloy-playground",
            "cadvisor-playground",
            // BC service container names
            "gateway",
            "identity-api",
            "docs-api",
            "rag-ingestion-api",
            "rag-chat-api",
            "metrics-api");

    private ContainerAllowlist() {
        // static
    }

    public static boolean contains(String name) {
        return name != null && ENTRIES.contains(name);
    }

    public static Set<String> all() {
        return ENTRIES;
    }

    private static Set<String> unmodifiable(String... values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String v : values) {
            set.add(v);
        }
        return Set.copyOf(set);
    }
}
