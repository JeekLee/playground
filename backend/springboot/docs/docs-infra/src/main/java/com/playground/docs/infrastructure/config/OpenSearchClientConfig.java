package com.playground.docs.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OpenSearch 2.x Java client used by the in-service projector +
 * search query path per ADR-12 §5.
 *
 * <p>Single endpoint URL pinned via {@code OPENSEARCH_BASE_URL} (default
 * {@code http://opensearch-playground:9200} per the ADR-12 compose spec).
 * Security plugin is disabled in dev (per the same ADR + ADR-05 amendment),
 * so no credentials are sent.
 *
 * <p>The Jackson mapper registers the JSR-310 module so {@link java.time.Instant}
 * round-trips as an ISO-8601 string — the {@code docs-v1} mapping uses
 * {@code "type": "date"} on every timestamp field.
 */
@Configuration(proxyBeanMethods = false)
public class OpenSearchClientConfig {

    @Bean
    public OpenSearchClient openSearchClient(
            @Value("${OPENSEARCH_BASE_URL:http://opensearch-playground:9200}") String baseUrl) {
        URI uri = URI.create(baseUrl);
        HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
                .builder(host)
                .setMapper(new JacksonJsonpMapper(objectMapper))
                .build();

        return new OpenSearchClient(transport);
    }
}
