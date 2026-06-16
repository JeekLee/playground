package com.playground.gateway.bootstrap;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Wraps the gateway → identity-api {@code POST /users/bootstrap} call per
 * ADR-10 §4 — 1s timeout per attempt, 3 retries with exponential backoff and
 * jitter, Redis cache keyed by Google {@code sub} with 8h TTL.
 */
@Component
public class IdentityBootstrapClient {

    public static final String CACHE_KEY_PREFIX = "bootstrap:google-sub:";
    private static final Duration CACHE_TTL = Duration.ofHours(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);

    private final WebClient webClient;
    private final ReactiveStringRedisTemplate redis;

    public IdentityBootstrapClient(
            WebClient.Builder webClientBuilder,
            ReactiveStringRedisTemplate redis,
            @Value("${playground.identity.base-url:http://playground-backend-identity-api:18081}") String identityBaseUrl) {
        this.webClient = webClientBuilder.baseUrl(identityBaseUrl).build();
        this.redis = redis;
    }

    public Mono<String> resolveUserId(BootstrapRequest request) {
        String cacheKey = CACHE_KEY_PREFIX + request.googleSub();
        return redis.opsForValue()
                .get(cacheKey)
                .flatMap(cached -> redis.expire(cacheKey, CACHE_TTL).thenReturn(cached))
                .switchIfEmpty(callBootstrap(request)
                        .flatMap(userId -> redis.opsForValue()
                                .set(cacheKey, userId, CACHE_TTL)
                                .thenReturn(userId)));
    }

    private Mono<String> callBootstrap(BootstrapRequest request) {
        return webClient.post()
                .uri("/users/bootstrap")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(BootstrapResponse.class)
                .timeout(REQUEST_TIMEOUT)
                .retryWhen(Retry.backoff(3, Duration.ofMillis(100)).jitter(0.5d))
                .map(BootstrapResponse::id);
    }

    public record BootstrapRequest(String googleSub, String email, String displayName, String avatarUrl) {}

    public record BootstrapResponse(String id) {}
}
