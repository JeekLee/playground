package com.playground.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint;
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint.DelegateEntry;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import java.util.List;

/**
 * Gateway security per ADR-10 §3. Public allowlist matches ADR-09; every
 * authenticated route requires a session; CSRF is enabled on state-changing
 * methods (cookie-readable token per Spring Security default). The
 * authentication entry point branches: {@code /api/**} returns 401 JSON;
 * everything else 302-redirects to Spring's OAuth2 login flow.
 */
@Configuration(proxyBeanMethods = false)
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        DelegatingServerAuthenticationEntryPoint entryPoint =
                new DelegatingServerAuthenticationEntryPoint(List.of(
                        new DelegateEntry(
                                ServerWebExchangeMatchers.pathMatchers("/api/**"),
                                new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)),
                        new DelegateEntry(
                                ServerWebExchangeMatchers.anyExchange(),
                                new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/google"))));

        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        // Spring Security 6.x defaults to XorServerCsrfTokenRequestAttributeHandler
                        // which XOR-encodes the token in the request attribute (BREACH-attack mitigation
                        // for templates that render the raw token into HTML). For our SPA + JS-readable
                        // cookie + double-submit pattern that doesn't fit: the frontend reads the raw
                        // cookie value and echoes it as the X-XSRF-TOKEN header, but the Xor handler
                        // tries to XOR-decode the header (expecting a different encoding) → mismatch
                        // → "Invalid CSRF Token" 403. Force the plain handler so the header is compared
                        // verbatim against the cookie value.
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
                // Eagerly materialize the CsrfToken Mono so the XSRF-TOKEN cookie
                // is written on GET responses too (CookieServerCsrfTokenRepository
                // is lazy by default). See CsrfTokenCookieMaterializingFilter.
                .addFilterAfter(new CsrfTokenCookieMaterializingFilter(),
                        org.springframework.security.config.web.server.SecurityWebFiltersOrder.CSRF)
                .authorizeExchange(ex -> ex
                        // System routes Spring Security owns.
                        .pathMatchers("/oauth2/**", "/login/**", "/logout").permitAll()
                        // Actuator probes (compose healthcheck, ops).
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Public SSR routes. The frontend itself gates auth-required pages
                        // (`/docs/mine`, `/docs/new`) via SSR redirect to `/login` — the
                        // gateway must let the request through so the Next.js handler runs.
                        // Per M2 spec v5 §6.2 the legacy `/docs/public/**` prefix is gone;
                        // the unified `/docs` namespace lives at:
                        //   /docs                 community feed (auth optional)
                        //   /docs/{uuid}          single doc (auth optional, frontend renders)
                        //   /docs/search          search results page (auth optional, scope-aware)
                        //   /docs/mine            caller's docs (frontend redirects to /login)
                        //   /docs/new             new doc editor (frontend redirects to /login)
                        .pathMatchers(HttpMethod.GET, "/", "/docs", "/docs/**", "/chat", "/metrics").permitAll()
                        // Public API routes per ADR-09 allowlist.
                        // ADR-12 amendment to ADR-09: the legacy /api/docs/public/** prefix is
                        // removed; the unified namespace gates per-doc visibility inside docs-api.
                        // Per M2 spec §6.1 + §6.2 the only S1 use of `GET /api/docs` is the
                        // authenticated `?scope=mine` shape — the community feed (bare
                        // `GET /api/docs`) lands in M2 S2, so the gateway treats every
                        // `GET /api/docs` (no UUID suffix) as authenticated. The
                        // `?scope=mine` requirement is enforced inside docs-api so a bare
                        // `GET /api/docs` from an authenticated client surfaces as 400
                        // (not 403/401).
                        //
                        // M2 S2 adds the community feed, owner endpoint, and
                        // public-scope search to the allowlist (ADR-12 amendment
                        // to ADR-09). The order below is load-bearing:
                        //   1. /api/docs/owner — static segment beats /{id} regex.
                        //   2. /api/docs/search — same.
                        //   3. /api/docs/{uuid} — single-doc anonymous read.
                        //   4. GET /api/docs — community feed (no scope=mine).
                        //   5. catch-all /api/docs/** → authenticated.
                        //
                        // The {scope=mine, scope=public} distinction on search
                        // is enforced inside docs-api: it 401's mine-scope when
                        // X-User-Id is absent. Gateway treats the route as
                        // permitAll() so the public-scope path stays anonymous.
                        .pathMatchers(HttpMethod.GET, "/api/docs/owner").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/docs/search").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/docs/{id:[0-9a-fA-F-]{36}}").permitAll()
                        // M2 S3 / ADR-12 §10: POST /api/docs/{uuid}/view is anonymous-OK
                        // (view-counter endpoint dedups via PLAYGROUND_ANON cookie). The
                        // {id}/like routes (POST + DELETE) remain authenticated and fall
                        // through to the catch-all below.
                        .pathMatchers(HttpMethod.POST, "/api/docs/{id:[0-9a-fA-F-]{36}}/view").permitAll()
                        // GET /api/docs (community feed + per-author feed via ?author=);
                        // POST /api/docs (create) still requires auth — explicit HttpMethod.GET.
                        .pathMatchers(HttpMethod.GET, "/api/docs", "/api/docs/").permitAll()
                        .pathMatchers("/api/docs", "/api/docs/", "/api/docs/**").authenticated()
                        // ADR-14 §G.4 — the M4 cycle removes the legacy
                        // /api/rag/chat/public anonymous allowlist; the entire
                        // /api/rag/chat/** surface is auth-only. Gateway 401s
                        // anonymous callers via the default authenticated()
                        // catch-all below.
                        .pathMatchers(HttpMethod.GET, "/api/metrics/**").permitAll()
                        // Next.js static assets.
                        .pathMatchers("/_next/**", "/favicon.ico", "/static/**").permitAll()
                        // Default: authenticated.
                        .anyExchange().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .logout(logout -> logout.logoutUrl("/logout"))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(entryPoint))
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("https://playground.jeeklee.com", "http://localhost:*"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
