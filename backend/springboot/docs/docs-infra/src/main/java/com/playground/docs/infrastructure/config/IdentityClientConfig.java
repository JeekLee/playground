package com.playground.docs.infrastructure.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Wires a dedicated {@link WebClient} for the docs BC's calls into
 * {@code identity-api}'s {@code /internal/users/**} routes (ADR-12 §8).
 *
 * <p>Timeouts are tight enough to fail-closed quickly when identity-api is
 * unreachable — the docs API still serves DB-backed reads on a downed
 * identity, just without author display names.
 *
 * <p>Base URL comes from {@code IDENTITY_BASE_URL} (default
 * {@code http://identity-api:18081} per ADR-12 §15 compose spec).
 */
@Configuration(proxyBeanMethods = false)
public class IdentityClientConfig {

    @Bean
    @Qualifier("identityWebClient")
    public WebClient identityWebClient(
            @Value("${IDENTITY_BASE_URL:http://identity-api:18081}") String baseUrl) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(2));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
