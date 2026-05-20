package com.playground.metrics.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.playground.metrics.app.dto.ActuatorProbeResult;
import com.playground.metrics.app.port.ActuatorHealthPort;
import com.playground.metrics.domain.ServiceProbeTarget;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Per-service secondary-signal probe adapter per ADR-15 §9 + §17.
 *
 * <p>Switches its parse strategy on {@link ServiceProbeTarget#kind()}:
 *
 * <ul>
 *   <li>{@link ServiceProbeTarget.Kind#BC} — GET {@code /actuator/health},
 *       decode JSON, mark {@code up=true} iff body's {@code status} is
 *       {@code "UP"} (case-insensitive — Spring emits upper, defensive
 *       parser).</li>
 *   <li>{@link ServiceProbeTarget.Kind#OBSERVABILITY} — GET the readiness
 *       path. 2xx ⇒ {@code (reachable=true, up=true)}; non-2xx with a body
 *       (4xx/5xx-with-content from {@code WebClientResponseException}) ⇒
 *       {@code (reachable=true, up=false)}. Lower-level failures (timeout,
 *       connection refused) ⇒ {@code unreachable()}.</li>
 * </ul>
 *
 * <p>Probe timeout is 2 seconds — compose-internal hops are sub-50ms; longer
 * than 2s implies the target is wedged, not slow. The verdict treats wedged
 * as unreachable (which composes to {@code degraded} for clean-scrape
 * services per ADR-15 §9).
 *
 * <p>Per the port contract this adapter never emits an error — every failure
 * mode collapses into {@link ActuatorProbeResult#unreachable()} so
 * {@code BuildServicesUseCase}'s {@code Mono.zip} composition is always
 * defined.
 */
@Component
public class ActuatorHealthAdapter implements ActuatorHealthPort {

    /** 2s upper bound for compose-internal actuator / readiness probes. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public ActuatorHealthAdapter(WebClient.Builder builder) {
        // No baseUrl — each probe uses the absolute URL stamped on its target
        // (different ports per service). Keeps the adapter stateless across
        // the 10 cells it serves.
        this.webClient = builder.build();
    }

    @Override
    public Mono<ActuatorProbeResult> probe(ServiceProbeTarget target) {
        if (target == null || target.probeUrl() == null) {
            // SPARK target or unknown — caller should not route here, but be
            // defensive: treat as unreachable rather than NPE.
            return Mono.just(ActuatorProbeResult.unreachable());
        }
        return switch (target.kind()) {
            case BC -> probeActuator(target.probeUrl());
            case OBSERVABILITY -> probeReadiness(target.probeUrl());
            case SPARK -> Mono.just(ActuatorProbeResult.unreachable());
        };
    }

    /**
     * GET {@code /actuator/health} and parse the {@code status} field. A
     * 200 with body {@code "UP"} is {@code (true, true)}; a 200 with body
     * {@code "DOWN"} / {@code "OUT_OF_SERVICE"} is {@code (true, false)};
     * a 503 with body still parses (Spring returns the status field even
     * when the verdict is non-UP), but we treat any non-2xx as
     * {@code (true, false)} since the BC explicitly said it's not healthy.
     */
    private Mono<ActuatorProbeResult> probeActuator(String url) {
        return webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> {
                    // 2xx → parse body; non-2xx (4xx/5xx) → reachable but down.
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(JsonNode.class)
                                .map(ActuatorHealthAdapter::parseActuatorStatus)
                                // If body is empty / non-JSON, the BC answered
                                // 2xx so it's reachable; lacking the status
                                // field, default to "up" (consistent with the
                                // 2xx posture; 4xx/5xx would've routed
                                // elsewhere).
                                .defaultIfEmpty(ActuatorProbeResult.reachableUp());
                    }
                    // Drain the body so the connection returns to the pool,
                    // then collapse to reachable-but-down.
                    return response.releaseBody()
                            .thenReturn(ActuatorProbeResult.reachableDown());
                })
                .timeout(PROBE_TIMEOUT)
                .onErrorReturn(ActuatorProbeResult.unreachable());
    }

    /**
     * GET a native readiness endpoint. 2xx ⇒ up. Non-2xx (4xx/5xx) ⇒
     * reachable but down. Lower-level failures ⇒ unreachable.
     */
    private Mono<ActuatorProbeResult> probeReadiness(String url) {
        return webClient.get()
                .uri(url)
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(response.statusCode().is2xxSuccessful()
                                ? ActuatorProbeResult.reachableUp()
                                : ActuatorProbeResult.reachableDown()))
                .timeout(PROBE_TIMEOUT)
                .onErrorReturn(ActuatorProbeResult.unreachable());
    }

    /**
     * Reads {@code {"status":"UP", ...}} from a Spring Actuator response.
     * Case-insensitive on the status token for tolerance against shaded /
     * forked actuator emitters.
     */
    static ActuatorProbeResult parseActuatorStatus(JsonNode root) {
        if (root == null) {
            return ActuatorProbeResult.reachableUp();
        }
        String status = root.path("status").asText("");
        return "UP".equalsIgnoreCase(status)
                ? ActuatorProbeResult.reachableUp()
                : ActuatorProbeResult.reachableDown();
    }
}
