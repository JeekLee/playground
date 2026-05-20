package com.playground.metrics.infra;

import com.playground.metrics.app.port.IpRateLimitPort;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Per-IP Redisson {@code RRateLimiter} for {@code /api/metrics/dashboard}
 * per ADR-15 §C + §18 + spec §8.2 (Story 19).
 *
 * <p>Reuses the M3/M4 {@code RRateLimiter} pattern (see
 * {@code com.playground.ragchat.infrastructure.redis.RedissonTokenBucketAdapter})
 * but keyed by IP (anonymous-OK route) rather than user id.
 *
 * <p>Bucket key namespace: {@code metrics:bucket:ip:{ip}:dashboard}. Redisson
 * configures the bucket's rate via {@link RRateLimiter#trySetRate} which is a
 * no-op when the key is already configured — safe to call on every request.
 *
 * <p>Failure mode: a Redis blip causes {@code tryAcquire} to throw; we fail
 * open (return {@code true}) and log loudly. Failing closed would block the
 * dashboard's anonymous read surface on a transient Redis hiccup, which is
 * the wrong trade for a polling-only public page.
 *
 * <p>The {@code Mono} wrapper uses {@link Schedulers#boundedElastic()} because
 * Redisson's sync API is blocking (Lettuce under the hood is non-blocking but
 * the {@code RRateLimiter} facade is sync). Per-call latency is sub-millisecond
 * on a healthy Redis; the bounded-elastic dispatch keeps the WebFlux event
 * loop free even on the rare slow call.
 *
 * <p>The bean only registers when {@link RedissonClient} is on the context;
 * unit tests that bypass Redis (e.g.,
 * {@link com.playground.metrics.api.controller.DashboardControllerTest}'s
 * {@code TestConfig}) supply their own no-op {@link IpRateLimitPort} bean.
 */
@Component
@ConditionalOnBean(RedissonClient.class)
public class RedissonIpRateLimiterAdapter implements IpRateLimitPort {

    private static final Logger log = LoggerFactory.getLogger(RedissonIpRateLimiterAdapter.class);

    static final String KEY_FMT = "metrics:bucket:ip:%s:dashboard";

    private final RedissonClient redisson;
    private final long limitPerMinute;

    public RedissonIpRateLimiterAdapter(
            RedissonClient redisson,
            @Value("${playground.metrics.rate-limit.dashboard-per-min:${METRICS_DASHBOARD_RATE_LIMIT_PER_MIN:30}}")
                    long limitPerMinute) {
        this.redisson = redisson;
        this.limitPerMinute = limitPerMinute <= 0 ? 30L : limitPerMinute;
    }

    /** Test-only ctor for explicit limit injection. */
    RedissonIpRateLimiterAdapter(RedissonClient redisson, long limitPerMinute, boolean test) {
        this.redisson = redisson;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    public Mono<Boolean> tryAcquire(String clientIp) {
        String key = String.format(KEY_FMT, clientIp == null ? "unknown" : clientIp);
        return Mono.fromCallable(() -> doTryAcquire(key))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private boolean doTryAcquire(String key) {
        try {
            RRateLimiter limiter = redisson.getRateLimiter(key);
            // trySetRate is no-op when already configured with these params;
            // Redisson reuses the existing bucket state across invocations.
            limiter.trySetRate(RateType.OVERALL, limitPerMinute, 1, RateIntervalUnit.MINUTES);
            return limiter.tryAcquire(1);
        } catch (RuntimeException e) {
            log.warn("metrics IP rate-limit evaluation failed (failing open): key={} err={}",
                    key, e.toString());
            return true;
        }
    }

    public long limitPerMinute() {
        return limitPerMinute;
    }
}
