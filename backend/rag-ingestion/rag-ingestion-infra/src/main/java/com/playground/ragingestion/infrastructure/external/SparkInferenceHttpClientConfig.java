package com.playground.ragingestion.infrastructure.external;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Force every Spring AI RestClient (used by the OpenAI starter for the
 * embedding sync call) onto Apache HttpClient5 with a buffered request
 * body so the wire request carries {@code Content-Length} instead of
 * {@code Transfer-Encoding: chunked}.
 *
 * <p>spark-inference-gateway's vLLM 0.19 backend (uvicorn) rejects chunked
 * POSTs to {@code /v1/embeddings} with {@code HTTP 400 - Invalid HTTP
 * request received.} HC5's streaming request mode (the default for
 * {@code HttpComponentsClientHttpRequestFactory} in Spring 6.1) emits
 * chunked encoding; {@link
 * HttpComponentsClientHttpRequestFactory#setBufferRequestBody(boolean)
 * setBufferRequestBody(true)} switches it to the non-streaming variant
 * that pre-buffers the entity and emits {@code Content-Length}.
 *
 * <p>The customizer is applied to every {@code RestClient.Builder} bean,
 * including the prototype Spring AI consumes via {@code ObjectProvider},
 * so the embedding sync path is guaranteed HC5 + Content-Length.
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
