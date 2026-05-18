package com.playground.gateway.security;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Eagerly materializes the {@link CsrfToken} on every request so that
 * {@link org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository}
 * writes the {@code XSRF-TOKEN} cookie on GET responses too.
 *
 * <p>Without this filter the token cookie is set lazily — Spring's
 * {@code CsrfWebFilter} only validates the token on state-changing requests
 * (POST/PATCH/PUT/DELETE) and merely *attaches* a lazy {@code Mono<CsrfToken>}
 * to the exchange on GETs; the cookie repository's {@code saveToken} side-
 * effect is wired into the Mono's subscription, so without an explicit
 * subscribe the cookie is never written.
 *
 * <p>A user landing on {@code /docs/new}, typing for 1.5s, and letting the
 * auto-save loop fire would never have received the cookie — the browser
 * sends no {@code X-XSRF-TOKEN} header and the gateway responds 403
 * {@code An expected CSRF token cannot be found}, surfacing in the editor
 * as a {@code Save failed — retry} pill.
 *
 * <p>This filter is registered inside the Spring Security filter chain via
 * {@code http.addFilterAfter(this, SecurityWebFiltersOrder.CSRF)} so that
 * {@code CsrfWebFilter} has already attached the {@code CsrfToken} attribute
 * to the exchange by the time we run. A naive standalone {@code @Component}
 * with {@code @Order} doesn't work here: Spring Security's reactive chain is
 * a single mega-filter with its own internal ordering, so external orders
 * either run entirely before or entirely after the chain — never *inside*
 * the chain where the CSRF attribute is materialized.
 */
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
