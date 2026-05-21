package com.playground.metrics.domain;

/**
 * Pure domain logic that composes a Prometheus {@code up{}} reading and a
 * Spring Actuator {@code /actuator/health} reading into a single verdict per
 * ADR-15 §9.
 *
 * <p>The "service unhealthy" judgment is a function of both signals,
 * short-circuit favoring {@code DOWN}:
 *
 * <pre>
 * prom up (last 2 scrapes)  actuator reachable?  actuator body  →  verdict
 * --------------------------------------------------------------------
 * 0/2                       n/a                  n/a               down
 * 1/2                       yes                  UP                degraded
 * 1/2                       yes                  non-UP            down
 * 2/2                       yes                  UP                up
 * 2/2                       yes                  non-UP            degraded
 * 2/2                       no (5xx/timeout)     n/a               degraded
 * </pre>
 *
 * <p>{@code spark-inference-gateway} is a special case (no actuator endpoint)
 * — handled by {@link #fromSparkProbe(boolean, boolean)}.
 */
public final class HealthVerdict {

    public enum Status {
        UP("up"), DEGRADED("degraded"), DOWN("down");

        private final String token;

        Status(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    private HealthVerdict() {
        // static
    }

    /**
     * @param scrapeUpCountLast2 number of {@code up == 1} samples in the last
     *     2 scrape cycles (~10 s), so {@code 0}, {@code 1}, or {@code 2}.
     * @param actuatorReachable whether the {@code /actuator/health} call
     *     succeeded (HTTP 2xx or 4xx — 5xx / timeout means unreachable).
     * @param actuatorUp whether the body's {@code status} field was
     *     {@code "UP"} (false if the call wasn't reachable).
     */
    public static Status from(int scrapeUpCountLast2, boolean actuatorReachable, boolean actuatorUp) {
        if (scrapeUpCountLast2 <= 0) {
            return Status.DOWN;
        }
        if (scrapeUpCountLast2 == 1) {
            // intermittent reachability
            if (actuatorReachable && actuatorUp) {
                return Status.DEGRADED;
            }
            return Status.DOWN;
        }
        // scrape clean: 2/2 up
        if (!actuatorReachable) {
            return Status.DEGRADED;
        }
        return actuatorUp ? Status.UP : Status.DEGRADED;
    }

    /**
     * Verdict for spark-inference-gateway from a HEAD {@code /v1/models}
     * probe per ADR-15 §12.
     *
     * @param reachable HEAD succeeded (no timeout / connection refused)
     * @param ok HEAD returned 200 / 204
     */
    public static Status fromSparkProbe(boolean reachable, boolean ok) {
        if (!reachable) {
            return Status.DOWN;
        }
        return ok ? Status.UP : Status.DEGRADED;
    }

    /**
     * Verdict for stack containers (postgres, redis, kafka, opensearch,
     * frontend) per ADR-15 §13 (amended 2026-05-21).
     *
     * <p>원래 §13은 {@code container_last_seen} age + {@code container_tasks_state}
     * 두 시그널을 조합했지만 — cgroup v2 + systemd cgroup driver 환경에서
     * {@code container_tasks_state{state="running"}}이 0으로 emit되어 verdict가
     * 모두 down으로 떨어짐. 운영 시 unreliable이라 single-signal로 단순화:
     *
     * <pre>
     * cAdvisor container_last_seen age (s)  →  verdict
     * --------------------------------------------------
     * (empty — no metric)                      down
     * &lt; 30 s                                   up
     * &gt;= 30 s                                  degraded
     * </pre>
     *
     * @param ageSeconds {@code time() - container_last_seen{name=...}} 결과 (초).
     *     음수 또는 {@link Double#NaN}는 down (메트릭 없음 또는 invalid)으로 처리.
     */
    public static Status fromContainerAge(double ageSeconds) {
        if (Double.isNaN(ageSeconds) || ageSeconds < 0) {
            return Status.DOWN;
        }
        return ageSeconds < 30.0 ? Status.UP : Status.DEGRADED;
    }
}
