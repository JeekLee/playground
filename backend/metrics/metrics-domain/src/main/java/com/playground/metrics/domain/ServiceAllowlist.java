package com.playground.metrics.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The {@code <svc>} substitution allowlist per ADR-15 §10. PromQL template
 * binding consults this set before composing the final query string —
 * caller-supplied service identifiers that fail this check cause the
 * controller to return 400 ({@code METRICS-VALIDATION-001}) rather than
 * ever reaching the PromQL string.
 *
 * <p>P0 set: the six BCs plus the four observability containers (per ADR-15
 * §17, observability self-monitoring is in M5 P0). Adding a future BC
 * requires this set to be extended in the same PR.
 */
public final class ServiceAllowlist {

    private static final Set<String> ENTRIES = unmodifiable(
            // 6 BCs
            "gateway",
            "identity-api",
            "docs-api",
            "rag-ingestion-api",
            "rag-chat-api",
            "metrics-api",
            // 4 observability containers (self-monitoring per ADR-15 §17)
            "prometheus-playground",
            "loki-playground",
            "alloy-playground",
            "cadvisor-playground",
            // spark-inference-gateway (host process probed via HEAD /v1/models — §12)
            "spark-inference-gateway");

    private ServiceAllowlist() {
        // static
    }

    public static boolean contains(String svc) {
        return svc != null && ENTRIES.contains(svc);
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
