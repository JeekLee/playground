package com.playground.identity.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Identity-api sits behind the gateway and trusts gateway-injected
 * {@code X-User-*} headers per ADR-07. Spring Security is therefore set to
 * {@code permitAll()} for every route + CSRF disabled — the gateway is the
 * security boundary. This is consistent with ADR-08 ("backend services must
 * not be host-exposed; the trust model breaks otherwise").
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfig {

    @Bean
    public SecurityFilterChain identityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
