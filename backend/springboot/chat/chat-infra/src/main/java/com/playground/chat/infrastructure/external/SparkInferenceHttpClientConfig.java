package com.playground.chat.infrastructure.external;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provide a {@link RestClient.Builder} bean for Spring AI's OpenAI starter
 * with the JDK HttpClient pinned to HTTP/1.1.
 *
 * <p>chat-api is a WebFlux app, so Spring Boot's
 * {@code RestClientAutoConfiguration} does not run (its
 * {@code NotReactiveWebApplicationCondition} excludes reactive apps) and
 * therefore no {@code RestClient.Builder} bean is in the context. Spring
 * AI's OpenAI auto-config falls back to {@code RestClient.builder()}
 * (Spring Framework default), which materializes a {@code RestClient}
 * backed by the JDK {@code java.net.http.HttpClient} — and that client
 * defaults to {@link HttpClient.Version#HTTP_2 HTTP_2}, attempting an h2c
 * upgrade on cleartext requests (carries {@code Connection: Upgrade,
 * HTTP2-Settings} + {@code Upgrade: h2c}).
 *
 * <p>spark-inference-gateway's uvicorn rejects the upgrade
 * ({@code Unsupported upgrade request.} → {@code Invalid HTTP request
 * received.}) and the chunked body that came in on the same request is
 * dropped before FastAPI can read it. Gateway's `model`-field routing
 * therefore sees an empty payload and responds {@code 400 - "model" field
 * required}.
 *
 * <p>Registering an explicit builder with {@link
 * HttpClient.Version#HTTP_1_1 HTTP_1_1} forces every Spring AI sync call
 * (embedding + auto-title) onto plain HTTP/1.1 — no upgrade headers, body
 * arrives intact, gateway routes correctly. The reactive streaming chat
 * path uses WebClient (Reactor Netty) and is unaffected.
 *
 * <p>rag-ingestion-api (servlet) does not need this workaround: its
 * {@code RestClientAutoConfiguration} runs and wires HC5 automatically.
 */
@Configuration
class SparkInferenceHttpClientConfig {

    @Bean
    RestClient.Builder restClientBuilder() {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(client));
    }
}
