package com.playground.ragchat.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Rag-Chat-api sits behind the gateway and trusts gateway-injected
 * {@code X-User-Id} / {@code X-User-Sub} headers per ADR-07 + ADR-14 §G.4.
 * Spring Security is set to {@code permitAll()} for every route + CSRF
 * disabled — the gateway is the security boundary. Consistent with ADR-08
 * ("backend services must not be host-exposed; the trust model breaks
 * otherwise") and the docs-api / rag-ingestion-api patterns.
 *
 * <p>WebFlux flavour ({@link SecurityWebFilterChain}) because the
 * {@code rag-chat-api} module is reactive end-to-end (ADR-14 §1).
 */
@Configuration(proxyBeanMethods = false)
public class RagChatSecurityConfig {

    @Bean
    public SecurityWebFilterChain ragChatFilterChain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }
}
