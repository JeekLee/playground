package com.playground.docs.infrastructure.redis;

import com.playground.docs.application.port.ViewClaimPort;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed implementation of {@link ViewClaimPort} per ADR-12 §10. The
 * primitive is {@code SET key value NX EX seconds} (Spring's
 * {@code ValueOperations.setIfAbsent(key, value, duration)} compiles to it).
 *
 * <p>Fail-soft: on a Redis outage, the {@code setIfAbsent} call returns
 * {@code null} (Spring's null-on-error semantic for misbehaving connections)
 * — the adapter coerces null to {@code false} so a degraded Redis can't
 * spuriously inflate view counters. The trade-off is "view counts under-
 * count during a Redis outage" which is the right side of the dedup
 * contract per spec §10 ("counter drift is informational").
 */
@Component
@RequiredArgsConstructor
public class RedisViewClaimAdapter implements ViewClaimPort {

    private static final Logger log = LoggerFactory.getLogger(RedisViewClaimAdapter.class);

    private final StringRedisTemplate redis;

    @Override
    public boolean claim(String key, Duration ttl) {
        try {
            Boolean firstClaim = redis.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.TRUE.equals(firstClaim);
        } catch (Exception e) {
            // Don't surface a 5xx to the caller — view increment is best-
            // effort; spec §10 accepts drift. Log at WARN so an operator
            // notices a sustained Redis outage.
            log.warn("Redis view-claim failed for key={}; treating as dedup hit", key, e);
            return false;
        }
    }
}
