package com.playground.metrics.infra;

import com.playground.metrics.app.port.IpRateLimitPort;
import com.playground.metrics.app.port.UserRateLimitPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Fallback no-op rate-limit adapters per ADR-15 §C. The IP-rate-limit port
 * has a real Redisson-backed adapter ({@link RedissonIpRateLimiterAdapter},
 * conditional on {@code RedissonClient} being present); this configuration
 * supplies a no-op fallback for test / Redis-less contexts.
 *
 * <p>The user-rate-limit port stays no-op until M5.1's logs UI lands —
 * spec §8.2 wants 60 req/min/user on {@code /api/metrics/logs}, but P0
 * has no UI consuming the endpoint (only ad-hoc CLI), so we ship the
 * port wired and the adapter no-op. ADR-15 §C documents this temporary
 * deferral; replace this bean when M5.1 lands.
 *
 * <p>Both beans are {@code @ConditionalOnMissingBean} so the real adapter
 * takes precedence when present.
 */
@Configuration(proxyBeanMethods = false)
public class NoopRateLimitAdapters {

    @Bean
    @ConditionalOnMissingBean(IpRateLimitPort.class)
    public IpRateLimitPort ipRateLimitPort() {
        return ip -> Mono.just(true);
    }

    @Bean
    @ConditionalOnMissingBean(UserRateLimitPort.class)
    public UserRateLimitPort userRateLimitPort() {
        return userId -> Mono.just(true);
    }
}
