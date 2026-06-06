package com.playground.metrics.infra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.PrometheusSeries;
import com.playground.metrics.domain.Range;
import com.playground.metrics.domain.Step;
import com.playground.metrics.infra.config.MetricsHttpProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class PrometheusAdapterTest {

    private WireMockServer wm;
    private PrometheusAdapter adapter;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        MetricsHttpProperties props = new MetricsHttpProperties(
                new MetricsHttpProperties.Prometheus(wm.baseUrl(), 8000),
                new MetricsHttpProperties.Loki(null, 0),
                new MetricsHttpProperties.SparkGateway(null, 0, null));
        adapter = new PrometheusAdapter(WebClient.builder(), props);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void instantQueryDecodesUpVector() {
        String json = """
                {
                  "status":"success",
                  "data":{
                    "resultType":"vector",
                    "result":[
                      {"metric":{"__name__":"up","service":"chat-api"},
                       "value":[1715763600, "1"]},
                      {"metric":{"__name__":"up","service":"docs-api"},
                       "value":[1715763600, "0"]}
                    ]
                  }
                }
                """;
        wm.stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        List<PrometheusSample> samples = adapter.instantQuery("up").block();

        assertThat(samples).hasSize(2);
        assertThat(samples.get(0).labels()).containsEntry("service", "chat-api");
        assertThat(samples.get(0).value()).isEqualTo(1.0);
        assertThat(samples.get(1).labels()).containsEntry("service", "docs-api");
        assertThat(samples.get(1).value()).isEqualTo(0.0);
    }

    @Test
    void rangeQueryDecodesMatrix() {
        String json = """
                {
                  "status":"success",
                  "data":{
                    "resultType":"matrix",
                    "result":[
                      {"metric":{"service":"chat-api"},
                       "values":[[1715763600, "380"], [1715763630, "392"]]}
                    ]
                  }
                }
                """;
        wm.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        List<PrometheusSeries> series = adapter.rangeQuery(
                "jvm_memory_used_bytes", Range.H_1, Step.parse("30s")).block();

        assertThat(series).hasSize(1);
        assertThat(series.get(0).labels()).containsEntry("service", "chat-api");
        assertThat(series.get(0).points()).hasSize(2);
        assertThat(series.get(0).points().get(0).ts()).isEqualTo(1715763600L);
        assertThat(series.get(0).points().get(0).value()).isEqualTo(380.0);
        assertThat(series.get(0).points().get(1).value()).isEqualTo(392.0);
    }

    @Test
    void instantQueryDecodesMatrixAsLatestPoint() {
        String json = """
                {
                  "status":"success",
                  "data":{
                    "resultType":"matrix",
                    "result":[
                      {"metric":{"service":"chat-api"},
                       "values":[[1715763600, "1"], [1715763630, "1"]]}
                    ]
                  }
                }
                """;
        wm.stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        List<PrometheusSample> samples = adapter.instantQuery("up").block();

        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).ts()).isEqualTo(1715763630L);
    }
}
