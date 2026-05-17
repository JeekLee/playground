package com.playground.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Step 1 of ADR-10 §2: removes any client-supplied {@code X-User-Id /
 * X-User-Email / X-User-Sub} headers before any auth or routing logic runs.
 * Applies to all requests, public and authenticated.
 *
 * <p>Uses {@link ServerHttpRequestDecorator} rather than
 * {@code request().mutate().headers(...)} because in Spring Framework 6.1 /
 * Spring Cloud Gateway 4.1 the mutated builder's headers can surface as a
 * read-only view (Netty-backed), causing {@code remove(...)} to throw
 * {@link UnsupportedOperationException}. A decorator that overrides
 * {@code getHeaders()} sidesteps that.
 */
@Component
public class StripUserHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest original = exchange.getRequest();
        HttpHeaders incoming = original.getHeaders();
        if (!incoming.containsKey("X-User-Id")
                && !incoming.containsKey("X-User-Email")
                && !incoming.containsKey("X-User-Sub")) {
            return chain.filter(exchange);
        }
        HttpHeaders cleaned = new HttpHeaders();
        incoming.forEach((name, values) -> {
            if (!"X-User-Id".equalsIgnoreCase(name)
                    && !"X-User-Email".equalsIgnoreCase(name)
                    && !"X-User-Sub".equalsIgnoreCase(name)) {
                cleaned.addAll(name, values);
            }
        });
        ServerHttpRequest mutated = new ServerHttpRequestDecorator(original) {
            @Override
            public HttpHeaders getHeaders() {
                return cleaned;
            }
        };
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
