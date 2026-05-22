package com.playground.gateway.filter;

import com.playground.gateway.bootstrap.IdentityBootstrapClient;
import com.playground.gateway.bootstrap.IdentityBootstrapClient.BootstrapRequest;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Step 4 of ADR-10 §2: bootstrap-or-cache the gateway's view of "Google sub
 * → identity-managed userId". On a cache miss the gateway calls
 * {@code POST identity-api:18081/users/bootstrap}. Resolved values are
 * propagated to {@link UserHeaderInjectionFilter} via exchange attributes
 * (named constants below).
 *
 * <p>Anonymous traffic is a no-op: with no security context, we skip the
 * bootstrap altogether.
 */
@Component
public class UserBootstrapFilter implements GlobalFilter, Ordered {

    public static final String ATTR_USER_ID = "playground.user.id";
    public static final String ATTR_USER_EMAIL = "playground.user.email";
    public static final String ATTR_USER_SUB = "playground.user.sub";

    public static final int ORDER = -100; // After Spring Security auth, before NettyRoutingFilter.

    private final IdentityBootstrapClient bootstrap;

    public UserBootstrapFilter(IdentityBootstrapClient bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(Authentication::isAuthenticated)
                .flatMap(auth -> resolveAndContinue(exchange, chain, auth))
                .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Void> resolveAndContinue(ServerWebExchange exchange, GatewayFilterChain chain, Authentication auth) {
        if (!(auth.getPrincipal() instanceof OAuth2User principal)) {
            return chain.filter(exchange);
        }
        String googleSub = stringAttr(principal, "sub");
        if (googleSub == null) {
            return chain.filter(exchange);
        }
        String email = stringAttr(principal, "email");
        String displayName = firstNonNull(stringAttr(principal, "name"), email);
        String avatarUrl = stringAttr(principal, "picture");

        BootstrapRequest request = new BootstrapRequest(googleSub, email, displayName, avatarUrl);
        return bootstrap.resolveUserId(request)
                .map(userId -> {
                    exchange.getAttributes().put(ATTR_USER_ID, userId);
                    if (email != null) exchange.getAttributes().put(ATTR_USER_EMAIL, email);
                    exchange.getAttributes().put(ATTR_USER_SUB, googleSub);
                    return exchange;
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    private static String stringAttr(OAuth2User principal, String key) {
        Object value = principal.getAttributes().get(key);
        return value == null ? null : value.toString();
    }

    private static <T> T firstNonNull(T a, T b) {
        return a == null ? b : a;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
