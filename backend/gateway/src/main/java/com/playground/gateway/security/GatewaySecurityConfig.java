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
                .csrf(csrf -> csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeExchange(ex -> ex
                        // System routes Spring Security owns.
                        .pathMatchers("/oauth2/**", "/login/**", "/logout").permitAll()
                        // Actuator probes (compose healthcheck, ops).
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Public SSR routes.
                        .pathMatchers(HttpMethod.GET, "/", "/docs/public/**", "/chat", "/metrics").permitAll()
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
                        // S1 ships only the GET /api/docs/{id} public allowance; the
                        // community feed (GET /api/docs), view counter
                        // (POST /api/docs/{id}/view), and public search
                        // (GET /api/docs/search?scope=public) land in M2 S2 / S3.
                        //
                        // The catch-all .pathMatchers("/api/docs/**").authenticated()
                        // below covers PATCH/DELETE/{id}/publish/{id}/unpublish; ordering
                        // ensures the UUID-only public read is matched first.
                        .pathMatchers(HttpMethod.GET, "/api/docs/{id:[0-9a-fA-F-]{36}}").permitAll()
                        .pathMatchers("/api/docs", "/api/docs/", "/api/docs/**").authenticated()
                        .pathMatchers(HttpMethod.POST, "/api/rag/chat/public").permitAll()
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
