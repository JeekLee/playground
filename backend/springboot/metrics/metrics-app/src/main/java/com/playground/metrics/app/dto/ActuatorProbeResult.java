package com.playground.metrics.app.dto;

/**
 * Result of a secondary-signal health probe per ADR-15 §9 + §17.
 *
 * <p>Two-bit shape that feeds directly into
 * {@link com.playground.metrics.domain.HealthVerdict#from(int, boolean, boolean)}:
 *
 * <ul>
 *   <li>{@code reachable} — the HTTP call completed without timeout / connection
 *       refused. A 5xx counts as <em>unreachable</em> for verdict purposes (the
 *       service is up enough to refuse but its health system is broken — ADR-15
 *       §9 buckets that as "actuator unreachable, degraded").</li>
 *   <li>{@code up} — for BC actuator probes, the response body's
 *       {@code status} field is {@code "UP"}; for observability containers a
 *       2xx response is sufficient ({@code reachable=true, up=true}).</li>
 * </ul>
 */
public record ActuatorProbeResult(boolean reachable, boolean up) {

    public static ActuatorProbeResult unreachable() {
        return new ActuatorProbeResult(false, false);
    }

    public static ActuatorProbeResult reachableUp() {
        return new ActuatorProbeResult(true, true);
    }

    public static ActuatorProbeResult reachableDown() {
        return new ActuatorProbeResult(true, false);
    }
}
