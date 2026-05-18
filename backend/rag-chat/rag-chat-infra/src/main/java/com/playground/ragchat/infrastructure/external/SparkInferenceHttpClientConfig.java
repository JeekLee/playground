package com.playground.ragchat.infrastructure.external;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Force every Spring AI RestClient (used for the non-streaming chat
 * auto-title call + the query embedding sync call) onto Apache
 * HttpClient5 with a buffered request body so the wire request carries
 * {@code Content-Length} instead of {@code Transfer-Encoding: chunked}.
 *
 * <p>Same workaround as rag-ingestion: vLLM 0.19's uvicorn rejects
 * chunked POSTs with {@code HTTP 400 - Invalid HTTP request received.};
 * {@link
 * HttpComponentsClientHttpRequestFactory#setBufferRequestBody(boolean)
 * setBufferRequestBody(true)} pre-buffers the entity and emits
 * {@code Content-Length}. Streaming {@code .stream()} is unaffected — it
 * goes through WebClient on Reactor Netty, which already negotiates
 * HTTP/1.1 with chunked responses (not chunked requests).
 */
@Configuration
class SparkInferenceHttpClientConfig {

    @Bean
    RestClientCustomizer httpComponentsRestClientCustomizer() {
        return builder -> builder.requestFactory(bufferingHttpComponentsFactory());
    }

    @SuppressWarnings("deprecation")
    private static HttpComponentsClientHttpRequestFactory bufferingHttpComponentsFactory() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setBufferRequestBody(true);
        return factory;
    }
}
