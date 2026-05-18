package com.playground.ragchat.infrastructure.external;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Force every Spring AI RestClient (used for the non-streaming chat
 * auto-title call + the query embedding sync call) to use Apache
 * HttpClient5 → HTTP/1.1.
 *
 * <p>Same workaround as rag-ingestion: on Spring Boot 3.3.5 the
 * auto-configured {@code RestClient.Builder} can resolve to JDK
 * HttpClient (HTTP/2 default) even when {@code httpclient5} is on the
 * classpath; vLLM 0.19's uvicorn rejects the h2c upgrade with
 * {@code Invalid HTTP request received.} → HTTP 400. Streaming
 * {@code .stream()} is unaffected — it goes through WebClient on
 * Reactor Netty.
 */
@Configuration
class SparkInferenceHttpClientConfig {

    @Bean
    RestClientCustomizer httpComponentsRestClientCustomizer() {
        return builder -> builder.requestFactory(new HttpComponentsClientHttpRequestFactory());
    }
}
