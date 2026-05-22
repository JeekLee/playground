package com.playground.gateway.filter;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Step 2 of ADR-10 §2: PLAYGROUND_ANON cookie management. Sets the cookie on
 * first public-page visit, refreshes Max-Age on subsequent hits, and never
 * sets it on authenticated routes (where Spring Security may redirect).
 */
@Component
public class AnonCookieFilter implements WebFilter, Ordered {

    public static final String COOKIE_NAME = "PLAYGROUND_ANON";
    private static final Duration LIFETIME = Duration.ofDays(30);

    private final PublicRouteMatcher publicRoutes;
    private final boolean secureCookies;

    public AnonCookieFilter(
            PublicRouteMatcher publicRoutes,
            @Value("${playground.cookies.secure:true}") boolean secureCookies) {
        this.publicRoutes = publicRoutes;
        this.secureCookies = secureCookies;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpCookie existing = exchange.getRequest().getCookies().getFirst(COOKIE_NAME);
        if (existing != null && !existing.getValue().isBlank()) {
            // Refresh Max-Age (rolling 30d).
            exchange.getResponse().addCookie(buildCookie(existing.getValue()));
        } else if (publicRoutes.isPublic(exchange)) {
            exchange.getResponse().addCookie(buildCookie(UUID.randomUUID().toString()));
        }
        return chain.filter(exchange);
    }

    private ResponseCookie buildCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/")
                .maxAge(LIFETIME)
                .build();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
