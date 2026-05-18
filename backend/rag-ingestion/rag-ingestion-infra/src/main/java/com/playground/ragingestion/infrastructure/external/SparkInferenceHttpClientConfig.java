package com.playground.ragingestion.infrastructure.external;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Force every Spring AI RestClient (used by the OpenAI starter for the
 * embedding sync call) to use Apache HttpClient5 → HTTP/1.1.
 *
 * <p>Spring AI 1.0's OpenAI auto-config pulls a {@code RestClient.Builder}
 * from the application context, but on Spring Boot 3.3.5 the
 * auto-configured builder lands without a {@code ClientHttpRequestFactory}
 * pinned to HC5 even when {@code httpclient5} is on the classpath. The
 * resulting JDK-HttpClient fallback sends HTTP/2 h2c upgrades, which
 * spark-inference-gateway's vLLM 0.19 backend (uvicorn, HTTP/1.1-only)
 * rejects with {@code Invalid HTTP request received.} → HTTP 400.
 *
 * <p>This customizer is applied to every {@code RestClient.Builder} bean,
 * including the prototype Spring AI consumes, so the embedding sync path
 * is guaranteed HTTP/1.1.
 */
@Configuration
class SparkInferenceHttpClientConfig {

    @Bean
    RestClientCustomizer httpComponentsRestClientCustomizer() {
        return builder -> builder.requestFactory(new HttpComponentsClientHttpRequestFactory());
    }
}
