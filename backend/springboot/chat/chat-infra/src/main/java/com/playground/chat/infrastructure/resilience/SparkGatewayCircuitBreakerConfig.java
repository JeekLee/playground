package com.playground.chat.infrastructure.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Resilience4j circuit breaker config per ADR-14 §4. One shared instance
 * named {@code spark-gateway} covers both the chat streaming and the embedding
 * adapters in this BC's JVM.
 *
 * <p>Pinned thresholds (per ADR-14 §4):
 * <ul>
 *   <li>Failure rate threshold: 50 %</li>
 *   <li>Sliding window: TIME_BASED, 60 seconds</li>
 *   <li>Minimum number of calls: 10</li>
 *   <li>OPEN duration: 30 seconds</li>
 *   <li>HALF_OPEN probe count: 1</li>
 *   <li>4xx counted as success (per ADR-13 §2.5 — 4xx is a code/data bug,
 *       not a gateway-health signal)</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SparkGatewayCircuitBreakerConfig {

    @Bean
    public CircuitBreaker sparkGatewayBreaker(CircuitBreakerRegistry registry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
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
        return registry.circuitBreaker("spark-gateway", config);
    }
}
