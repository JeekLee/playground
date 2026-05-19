package com.playground.metrics.app.port;

import reactor.core.publisher.Mono;

/**
 * Per-IP rate limit on {@code /api/metrics/dashboard} per ADR-15 §C + spec
 * §8.2. Slice 1 (Task 6) stubs this as a no-op pass-through;
 * slice 2 (Task 8) wires Redisson {@code RRateLimiter} implementations in
 * {@code metrics-infra}.
 *
 * <p>The port stays in {@code metrics-app} so slice 2 can wire its adapter
 * without DI churn.
 */
public interface IpRateLimitPort {

    /**
     * Try to acquire one token for the given client IP. Returns {@code true}
     * if the request is allowed, {@code false} if the bucket is empty.
     *
     * <p>Slice 1: always returns {@code true} (no-op).
     */
    Mono<Boolean> tryAcquire(String clientIp);
}
