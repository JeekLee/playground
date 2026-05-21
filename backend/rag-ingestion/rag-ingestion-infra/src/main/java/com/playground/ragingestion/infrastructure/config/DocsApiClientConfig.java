package com.playground.ragingestion.infrastructure.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * WebClient configured for docs-api's {@code /internal/**} routes per ADR-08
 * Exception 1 + ADR-13 §2 (body-fetch column) + §11 (buffered read).
 *
 * <ul>
 *   <li>Base URL: {@code DOCS_API_BASE_URL} (default
 *       {@code http://docs-api:18082} per ADR-13 §B compose spec).</li>
 *   <li>Response timeout: 5 s.</li>
 *   <li>Connect timeout: 2 s.</li>
 *   <li>{@code maxInMemorySize}: 12 MB. The M2 docs body cap was 1 MB (slack
 *       2 MB); M6 (ADR-16) widens the docs body cap to 10 MB so PDF text
 *       extraction can produce large Markdown bodies — bump the WebClient
 *       buffer to 12 MB to match (10 MB body + 2 MB slack for JSON
 *       envelope + checksum field). Bodies exceeding 12 MB throw
 *       {@code DataBufferLimitException}, classified as non-retryable by
 *       {@code DocsBodyFetchAdapter}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class DocsApiClientConfig {

    @Bean
    @Qualifier("docsWebClient")
    public WebClient docsWebClient(
            @Value("${DOCS_API_BASE_URL:http://docs-api:18082}") String baseUrl) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(5))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler(5, TimeUnit.SECONDS))
                        .addHandlerLast(new io.netty.handler.timeout.WriteTimeoutHandler(5, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(12 * 1024 * 1024))
                .build();
    }
}
