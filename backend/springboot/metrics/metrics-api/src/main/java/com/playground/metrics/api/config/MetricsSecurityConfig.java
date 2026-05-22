package com.playground.metrics.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * WebFlux security config per ADR-15 §C + §G.2.
 *
 * <ul>
 *   <li>{@code permitAll()} on {@code /api/metrics/dashboard},
 *       {@code /api/metrics/services}, {@code /api/metrics/timeseries},
 *       and {@code /actuator/**}.</li>
 *   <li>{@code authenticated()} on {@code /api/metrics/logs/**} — enforced by
 *       checking the gateway-injected {@code X-User-Id} header (mirrors the
 *       M2 docs-api / M4 rag-chat-api pattern; the gateway is the security
 *       boundary per ADR-08, the BC trusts the header presence per ADR-07).</li>
 * </ul>
 *
 * <p>Slice-1 note: this BC sits behind the gateway and the gateway strips
 * the {@code /api/metrics} prefix per ADR-07 forwarding map. The controllers
 * therefore listen on {@code /dashboard}, {@code /services}, etc.; the
 * security rules below match the un-prefixed paths.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class MetricsSecurityConfig {

    public static final String USER_ID_HEADER = "X-User-Id";

    @Bean
    public SecurityWebFilterChain metricsFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/**").permitAll()
                        // Logs endpoint — header presence rule
                        .pathMatchers(HttpMethod.GET, "/logs", "/logs/**", "/api/metrics/logs", "/api/metrics/logs/**")
                        .access(MetricsSecurityConfig::requireUserIdHeader)
                        // Public reads (gateway also permits these per ADR-09)
                        .pathMatchers(HttpMethod.GET, "/dashboard", "/services", "/timeseries",
                                "/api/metrics/dashboard", "/api/metrics/services", "/api/metrics/timeseries")
                        .permitAll()
                        // Per ADR-09 + ADR-15 §G.2 most-specific-match wins — anything else
                        // under /api/metrics/** stays public (catch-all dashboard subroutes).
                        .anyExchange().permitAll())
                // Return a plain 401 when the X-User-Id check fails; the
                // gateway is the security boundary per ADR-08 + ADR-07 (real
                // 401's with the error envelope come from the gateway when the
                // session is missing). BC-side 401 is a defense-in-depth backstop.
                .exceptionHandling(eh -> eh.authenticationEntryPoint(
                        new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    private static Mono<AuthorizationDecision> requireUserIdHeader(
            Mono<org.springframework.security.core.Authentication> authentication,
            AuthorizationContext ctx) {
        ServerWebExchange exchange = ctx.getExchange();
        String userId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);
        boolean allowed = userId != null && !userId.isBlank();
        return Mono.just(new AuthorizationDecision(allowed));
    }
}
