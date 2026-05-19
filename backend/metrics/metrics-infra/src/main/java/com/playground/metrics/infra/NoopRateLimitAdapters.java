package com.playground.metrics.infra;

import com.playground.metrics.app.port.IpRateLimitPort;
import com.playground.metrics.app.port.UserRateLimitPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Slice-1 no-op rate-limit adapters. Slice 2 (Task 8) replaces these with
 * Redisson {@code RRateLimiter}-backed adapters per ADR-15 §C + §18.
 *
 * <p>Both beans are {@code @ConditionalOnMissingBean} so the slice-2 PR can
 * register a more-specific adapter and step these aside without DI churn.
 */
@Configuration(proxyBeanMethods = false)
public class NoopRateLimitAdapters {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(IpRateLimitPort.class)
    public IpRateLimitPort ipRateLimitPort() {
        return ip -> Mono.just(true);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(UserRateLimitPort.class)
    public UserRateLimitPort userRateLimitPort() {
        return userId -> Mono.just(true);
    }
}
