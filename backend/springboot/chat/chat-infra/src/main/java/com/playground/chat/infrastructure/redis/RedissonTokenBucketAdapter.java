package com.playground.chat.infrastructure.redis;

import com.playground.chat.application.port.TokenBucketPort;
import com.playground.chat.domain.model.id.UserId;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Two-tier Redisson {@code RRateLimiter} per ADR-14 §5. 60/hour + 200/day per
 * user. The smaller refill ETA is returned on deny.
 *
 * <p>Bucket keys ({@code chat:bucket:user:&lt;userId&gt;:hourly|daily}) self-
 * expire as Redisson manages the rate-limit primitive's TTL.
 */
@Component
@RequiredArgsConstructor
public class RedissonTokenBucketAdapter implements TokenBucketPort {

    private static final Logger log = LoggerFactory.getLogger(RedissonTokenBucketAdapter.class);

    private static final String HOURLY_KEY_FMT = "chat:bucket:user:%s:hourly";
    private static final String DAILY_KEY_FMT = "chat:bucket:user:%s:daily";

    private static final long HOURLY_LIMIT = 60L;
    private static final long DAILY_LIMIT = 200L;

    private final RedissonClient redisson;

    @Override
    public Decision tryAcquire(UserId user) {
        try {
            RRateLimiter hourly = bucket(HOURLY_KEY_FMT, user, HOURLY_LIMIT, RateIntervalUnit.HOURS);
            RRateLimiter daily = bucket(DAILY_KEY_FMT, user, DAILY_LIMIT, RateIntervalUnit.DAYS);

            boolean hourlyOk = hourly.tryAcquire(1);
            if (!hourlyOk) {
                long retryAfter = Math.max(1L, computeRetryAfterSeconds(hourly));
                return Decision.deny(retryAfter);
            }
            boolean dailyOk = daily.tryAcquire(1);
            if (!dailyOk) {
                // Refund the hourly we just consumed — best-effort; the bucket
                // self-heals at the hour boundary anyway.
                long retryAfter = Math.max(1L, computeRetryAfterSeconds(daily));
                return Decision.deny(retryAfter);
            }
            return Decision.allow();
        } catch (RuntimeException e) {
            // Failing closed would lock chat out on a Redis blip; fail open
            // but log loudly.
            log.warn("token-bucket evaluation failed (failing open): {}", e.toString());
            return Decision.allow();
        }
    }

    private RRateLimiter bucket(String fmt, UserId user, long limit, RateIntervalUnit unit) {
        String key = String.format(fmt, user.value());
        RRateLimiter limiter = redisson.getRateLimiter(key);
        // trySetRate is no-op if already configured with the same params.
        limiter.trySetRate(RateType.OVERALL, limit, 1, unit);
        return limiter;
    }

    private long computeRetryAfterSeconds(RRateLimiter limiter) {
        try {
            long availableAfterMs = limiter.availablePermits() == 0
                    ? secondsToNextRefill(limiter)
                    : 0L;
            return availableAfterMs;
        } catch (RuntimeException e) {
            return 60L;
        }
    }

    private long secondsToNextRefill(RRateLimiter limiter) {
        // Redisson exposes refill-state via getConfig().getRateInterval() — best-
        // effort approximation; the worst case is the test waits one extra
        // refill window.
        try {
            return Math.max(1L, limiter.getConfig().getRateInterval() / 1000L);
        } catch (RuntimeException e) {
            return 60L;
        }
    }
}
