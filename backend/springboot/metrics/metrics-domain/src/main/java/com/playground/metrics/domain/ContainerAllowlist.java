package com.playground.metrics.domain;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code <name>} substitution allowlist per ADR-15 §10. Same fail-closed
 * validation contract as {@link ServiceAllowlist}.
 *
 * <p>2026-05-21 amendment: hardcoded set에서 정규식 prefix 화이트리스트 +
 * 명시적 known-set 하이브리드로 전환. cAdvisor가 emit하는
 * {@code container_*{name=...}} 메트릭의 {@code name} 라벨은 docker
 * {@code container_name}을 그대로 따라가므로, compose의 container_name이
 * {@code playground-*} prefix를 따르면 자동 인식.
 *
 * <p>spark-inference-* 컨테이너는 별도 compose project이고 prefix 무관 —
 * monitoring 안 함. Allowlist에서도 제외 (PromQL이 빈 결과 반환, dashboard에
 * 카드 없음).
 */
public final class ContainerAllowlist {

    /** Same pattern as {@link ServiceAllowlist}. */
    private static final Pattern PLAYGROUND_PATTERN = Pattern.compile(
            "^playground-[a-z0-9]+(?:-[a-z0-9]+)*$");

    /**
     * 명시적 카탈로그 — dashboard 컨테이너 카드 렌더링용. kafka-init은 init
     * container라 dashboard 카드에서 제외 (정상 exit 후 cAdvisor 메트릭 끊김).
     * PromQL 호출 시 prefix regex는 통과하므로 metric query 자체는 가능.
     */
    private static final Set<String> KNOWN_ENTRIES = unmodifiable(
            // Infra
            "playground-postgres",
            "playground-redis",
            "playground-kafka-broker",
            "playground-opensearch",
            // Observability stack (M5)
            "playground-prometheus",
            "playground-loki",
            "playground-alloy",
            "playground-cadvisor",
            // BC service container names
            "playground-backend-gateway",
            "playground-backend-identity-api",
            "playground-backend-docs-api",
            "playground-backend-rag-ingestion-api",
            "playground-backend-chat-api",
            "playground-backend-metrics-api",
            // Frontend
            "playground-frontend");

    private ContainerAllowlist() {
        // static
    }

    public static boolean contains(String name) {
        if (name == null) {
            return false;
        }
        return PLAYGROUND_PATTERN.matcher(name).matches();
    }

    /** 명시적 카탈로그 (dashboard 컨테이너 카드 렌더링용). */
    public static Set<String> all() {
        return KNOWN_ENTRIES;
    }

    private static Set<String> unmodifiable(String... values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String v : values) {
            set.add(v);
        }
        return Set.copyOf(set);
    }
}
