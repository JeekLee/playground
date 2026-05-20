package com.playground.metrics.app.port;

import com.playground.metrics.app.dto.ActuatorProbeResult;
import com.playground.metrics.domain.ServiceProbeTarget;
import reactor.core.publisher.Mono;

/**
 * Application port for the per-service secondary health signal per ADR-15
 * §9 + §17. Implemented in {@code metrics-infra} by a Spring {@code WebClient}
 * adapter that switches its parsing strategy on {@link ServiceProbeTarget#kind()}:
 *
 * <ul>
 *   <li>{@link ServiceProbeTarget.Kind#BC} — GET {@code /actuator/health},
 *       parse {@code {"status":"UP"}} JSON body.</li>
 *   <li>{@link ServiceProbeTarget.Kind#OBSERVABILITY} — GET the tool-native
 *       readiness path; 2xx ⇒ up.</li>
 * </ul>
 *
 * <p>For {@link ServiceProbeTarget.Kind#SPARK}, callers should NOT use this
 * port — spark health is via {@link SparkGatewayProbePort}. Implementations
 * may throw or return {@link ActuatorProbeResult#unreachable()} for spark
 * targets; {@code BuildServicesUseCase} avoids that path entirely.
 *
 * <p>The implementation MUST return {@link ActuatorProbeResult#unreachable()}
 * on timeout / connection refused / 5xx rather than emitting an error — the
 * verdict composition in {@code BuildServicesUseCase} relies on never seeing
 * an upstream exception bubble past this port (per ADR-15 §9's "degraded on
 * unreachable" rule + §16 partial-response posture).
 */
public interface ActuatorHealthPort {

    /**
     * Probes the secondary health signal for one service.
     *
     * @param target probe metadata; URL and parse strategy come from the
     *     {@code target.kind()} discriminator.
     * @return reactive result that never errors — failures collapse into
     *     {@link ActuatorProbeResult#unreachable()}.
     */
    Mono<ActuatorProbeResult> probe(ServiceProbeTarget target);
}
