package com.playground.ragchat.infrastructure.tool;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Lazily-allocates one Resilience4j {@link CircuitBreaker} per tool
 * descriptor per ADR-17 §5. The breaker name is
 * {@code tool-<descriptor-name>}; thresholds match the {@code spark-gateway}
 * breaker (ADR-14 §4): 50% / 60s window / 10 minimum calls / 30s OPEN /
 * 1 half-open probe.
 *
 * <p>4xx responses do NOT count against the breaker (ADR-14 §4 invariant —
 * 4xx is a code/data bug, not a gateway-health signal).
 *
 * <p>Per-tool isolation: the per-descriptor map ensures hammering one
 * tool's 5xx does not affect another tool's breaker state (PRD Story 6).
 */
@Component
public class ToolBreakerRegistry {

    private final CircuitBreakerRegistry registry;
    private final ConcurrentMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig perToolConfig;

    public ToolBreakerRegistry(CircuitBreakerRegistry registry) {
        this.registry = registry;
        this.perToolConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize(60)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(IOException.class, TimeoutException.class, WebClientResponseException.class)
                .ignoreExceptions(
                        WebClientResponseException.BadRequest.class,
                        WebClientResponseException.NotFound.class,
                        WebClientResponseException.UnprocessableEntity.class)
                .build();
    }

    /**
     * Returns the breaker for the given tool descriptor name, creating
     * (and registering) one lazily on first request.
     */
    public CircuitBreaker forTool(String descriptorName) {
        return breakers.computeIfAbsent(descriptorName, n ->
                registry.circuitBreaker("tool-" + n, perToolConfig));
    }
}
