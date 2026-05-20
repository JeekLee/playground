package com.playground.metrics.infra;

import com.playground.metrics.app.port.IpRateLimitPort;
import com.playground.metrics.app.port.UserRateLimitPort;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Rate-limit adapter wiring per ADR-15 §C + §18.
 *
 * <p>Two {@link IpRateLimitPort} candidates, evaluated in this order:
 *
 * <ol>
 *   <li>{@link #redissonIpRateLimitPort} — Redisson-backed adapter, registered
 *       only when a {@link RedissonClient} bean is on the context (prod path
 *       where {@code redisson-spring-boot-starter} auto-configures
 *       {@code RedissonClient} from {@code spring.data.redis.*}).</li>
 *   <li>{@link #noopIpRateLimitPort} — pass-through fallback, registered only
 *       when no {@link IpRateLimitPort} candidate is already present (test
 *       contexts that exclude {@code RedissonAutoConfigurationV2}, or local
 *       runs without Redis).</li>
 * </ol>
 *
 * <p>Putting the {@code @ConditionalOnBean(RedissonClient.class)} on a
 * {@code @Bean} method (rather than {@code @Component} on the adapter class)
 * is load-bearing: Spring evaluates {@code @ConditionalOnBean} on component-
 * scanned classes during the scan phase, BEFORE auto-configurations like
 * {@code RedissonAutoConfigurationV2} run. So a component-scanned
 * {@code @ConditionalOnBean(RedissonClient.class)} always sees "no
 * RedissonClient yet" and silently drops the bean. {@code @Bean} methods are
 * evaluated after auto-config, so the condition fires correctly.
 *
 * <p>The user-rate-limit port stays no-op until M5.1's logs UI lands — spec
 * §8.2 wants 60 req/min/user on {@code /api/metrics/logs}, but P0 has no UI
 * consuming the endpoint (only ad-hoc CLI), so we ship the port wired and
 * the adapter no-op. ADR-15 §C documents this temporary deferral; replace
 * this bean when M5.1 lands.
 */
@Configuration(proxyBeanMethods = false)
public class NoopRateLimitAdapters {

    @Bean
    @ConditionalOnBean(RedissonClient.class)
    public IpRateLimitPort redissonIpRateLimitPort(
            RedissonClient redisson,
            @Value("${playground.metrics.rate-limit.dashboard-per-min:${METRICS_DASHBOARD_RATE_LIMIT_PER_MIN:30}}")
                    long limitPerMinute) {
        return new RedissonIpRateLimiterAdapter(redisson, limitPerMinute);
    }

    @Bean
    @ConditionalOnMissingBean(IpRateLimitPort.class)
    public IpRateLimitPort noopIpRateLimitPort() {
        return ip -> Mono.just(true);
    }

    @Bean
    @ConditionalOnMissingBean(UserRateLimitPort.class)
    public UserRateLimitPort noopUserRateLimitPort() {
        return userId -> Mono.just(true);
    }
}
