package com.playground.gateway.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Eagerly materializes the {@link CsrfToken} on every request so that
 * {@link org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository}
 * writes the {@code XSRF-TOKEN} cookie on GET responses too.
 *
 * <p>Without this filter the token cookie is set lazily — only when the
 * {@code CsrfWebFilter} actually validates a state-changing request (POST,
 * PATCH, etc.). A user landing on {@code /docs/new}, typing for 1.5s, and
 * letting the auto-save loop fire would never have received the cookie, so
 * the browser sends no {@code X-XSRF-TOKEN} header and the gateway responds
 * 403 {@code An expected CSRF token cannot be found}. That surfaces in the
 * editor as a {@code Save failed — retry} pill.
 *
 * <p>Subscribing to the {@code CsrfToken} Mono via {@code .doOnSuccess(...)}
 * triggers token generation + cookie write. Order is set just after the
 * default {@code CsrfWebFilter} (which itself sits at the standard Spring
 * Security position) so the token attribute is present in the exchange by
 * the time this filter runs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class CsrfTokenCookieMaterializingFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return chain.filter(exchange);
        }
        // .doOnSuccess subscribes; CookieServerCsrfTokenRepository writes the
        // XSRF-TOKEN cookie as a side-effect of token resolution.
        return token.doOnSuccess(t -> {}).then(chain.filter(exchange));
    }
}
