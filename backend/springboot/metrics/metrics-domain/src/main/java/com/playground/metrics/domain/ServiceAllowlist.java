package com.playground.metrics.domain;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code <svc>} substitution allowlist per ADR-15 §10. PromQL template
 * binding consults this set before composing the final query string —
 * caller-supplied service identifiers that fail this check cause the
 * controller to return 400 ({@code METRICS-VALIDATION-001}) rather than
 * ever reaching the PromQL string.
 *
 * <p>2026-05-21 amendment: hardcoded set에서 정규식 prefix 화이트리스트 +
 * 명시적 known-set 하이브리드로 전환. {@link #contains(String)}는 정규식 기반
 * 이라 새 BC/stack 컨테이너가 prefix 규칙만 따르면 코드 변경 없이 인식.
 * {@link #all()}는 dashboard 렌더링을 위해 명시적 카탈로그 유지 (P0).
 * 진정한 런타임 발견 (Prometheus {@code label_values()} query)은 P1.
 *
 * <p>예외 1개: {@code spark-inference-gateway} — 별도 compose project이고
 * prefix 무관 (ADR-04, ADR-15 §12).
 */
public final class ServiceAllowlist {

    /**
     * Playground 명명 규칙. {@code playground-<segment>(-<segment>)*}.
     * Injection 방어: 메타문자 차단 ({@code [a-z0-9]+}만 허용).
     */
    private static final Pattern PLAYGROUND_PATTERN = Pattern.compile(
            "^playground-[a-z0-9]+(?:-[a-z0-9]+)*$");

    /** ADR-15 §12 — spark-inference-gateway는 별도 stack (prefix 무관). */
    private static final Set<String> EXTERNAL_WHITELIST = Set.of("spark-inference-gateway");

    /** 명시적 카탈로그 — dashboard 렌더링 + 가드 테스트용. */
    private static final Set<String> KNOWN_ENTRIES = unmodifiable(
            // 5 active BCs. rag-ingestion-api was retired in M6.1.
            "playground-backend-gateway",
            "playground-backend-identity-api",
            "playground-backend-docs-api",
            "playground-backend-chat-api",
            "playground-backend-metrics-api",
            // 4 observability containers (self-monitoring per ADR-15 §17)
            "playground-prometheus",
            "playground-loki",
            "playground-alloy",
            "playground-cadvisor",
            // spark-inference-gateway (host process probed via HEAD /v1/models — §12)
            "spark-inference-gateway",
            // 5 stack containers (cAdvisor container_last_seen age — ADR-15 §13 amended).
            // kafka-init은 init container로 exit 후 영구 stopped — verdict 무의미.
            "playground-frontend",
            "playground-postgres",
            "playground-redis",
            "playground-kafka-broker",
            "playground-opensearch");

    private ServiceAllowlist() {
        // static
    }

    /**
     * Returns true if {@code svc} matches the playground prefix pattern
     * OR is in the external whitelist (spark-inference-gateway).
     *
     * <p>이 메서드는 PromQL/LogQL 식별자 검증의 1차 게이트. 정규식 기반이라
     * 명시적 카탈로그({@link #all()})에 없는 새 컨테이너도 prefix만 맞으면 통과.
     * cAdvisor에 해당 이름의 컨테이너가 없으면 빈 결과 → 보안 risk 없음.
     */
    public static boolean contains(String svc) {
        if (svc == null) {
            return false;
        }
        if (EXTERNAL_WHITELIST.contains(svc)) {
            return true;
        }
        return PLAYGROUND_PATTERN.matcher(svc).matches();
    }

    /** 명시적 카탈로그 (dashboard 렌더링용). 동적 발견은 P1에서. */
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
