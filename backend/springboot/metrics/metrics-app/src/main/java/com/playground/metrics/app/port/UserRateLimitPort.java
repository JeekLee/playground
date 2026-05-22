package com.playground.metrics.app.port;

import reactor.core.publisher.Mono;

/**
 * Per-user rate limit on {@code /api/metrics/logs} per ADR-15 §C + spec §8.2.
 * Slice 1 stubs this as a no-op pass-through; slice 2 wires Redisson
 * {@code RRateLimiter} adapters in {@code metrics-infra}.
 */
public interface UserRateLimitPort {

    /**
     * Try to acquire one token for the given user id. Returns {@code true}
     * if the request is allowed, {@code false} if the bucket is empty.
     *
     * <p>Slice 1: always returns {@code true} (no-op).
     */
    Mono<Boolean> tryAcquire(String userId);
}
