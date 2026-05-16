package com.playground.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Step 5 of ADR-10 §2: writes {@code X-User-Id / X-User-Email / X-User-Sub}
 * onto the forwarded request, sourced from the exchange attributes the
 * {@link UserBootstrapFilter} populated. On anonymous traffic the attributes
 * are absent and this filter no-ops, leaving the headers absent — per ADR-09
 * "no sentinel anonymous user id".
 */
@Component
public class UserHeaderInjectionFilter implements GlobalFilter, Ordered {

    /** Just before NettyRoutingFilter. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = exchange.getAttribute(UserBootstrapFilter.ATTR_USER_ID);
        if (userId == null) {
            return chain.filter(exchange);
        }
        String email = exchange.getAttribute(UserBootstrapFilter.ATTR_USER_EMAIL);
        String sub = exchange.getAttribute(UserBootstrapFilter.ATTR_USER_SUB);
        ServerWebExchange mutated = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(h -> {
                            h.set("X-User-Id", userId);
                            if (email != null) h.set("X-User-Email", email);
                            if (sub != null) h.set("X-User-Sub", sub);
                        })
                        .build())
                .build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
