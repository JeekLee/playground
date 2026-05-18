package com.playground.ragingestion.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * RAG-Ingestion has no public HTTP surface (ADR-13 §A — actuator only).
 * Spring Security is on the classpath via the {@code bc-api} convention
 * plugin, so a filter chain is required to disable the default form-login
 * + http-basic auto-config that would otherwise gate {@code /actuator/**}.
 * The container is unreachable from outside the compose network anyway
 * (no host port mapping per ADR-08), so {@code permitAll} is safe.
 */
@Configuration(proxyBeanMethods = false)
public class RagIngestionSecurityConfig {

    @Bean
    public SecurityFilterChain ragFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
