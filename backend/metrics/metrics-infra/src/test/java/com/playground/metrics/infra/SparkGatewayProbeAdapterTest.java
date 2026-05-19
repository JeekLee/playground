package com.playground.metrics.infra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.infra.config.MetricsHttpProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class SparkGatewayProbeAdapterTest {

    private WireMockServer wm;
    private SparkGatewayProbeAdapter adapter;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        MetricsHttpProperties props = new MetricsHttpProperties(
                new MetricsHttpProperties.Prometheus(null, 0),
                new MetricsHttpProperties.Loki(null, 0),
                new MetricsHttpProperties.SparkGateway(wm.baseUrl(), 15));
        adapter = new SparkGatewayProbeAdapter(WebClient.builder(), props);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void probeReturnsUpFor200() {
        wm.stubFor(head(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200)));

        SparkProbeResult result = adapter.probe().block();
        assertThat(result).isNotNull();
        assertThat(result.reachable()).isTrue();
        assertThat(result.ok()).isTrue();
    }

    @Test
    void probeReturnsDownOnConnectionFailure() {
        wm.stop(); // simulate connection refused
        SparkProbeResult result = adapter.probe().block();
        assertThat(result).isNotNull();
        assertThat(result.reachable()).isFalse();
        assertThat(result.ok()).isFalse();
    }

    @Test
    void listModelsDecodesIds() {
        String json = """
                {"object":"list","data":[
                  {"id":"BGE-M3","object":"model"},
                  {"id":"Qwen3-32B","object":"model"}
                ]}
                """;
        wm.stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        List<String> models = adapter.listModels().block();
        assertThat(models).containsExactly("BGE-M3", "Qwen3-32B");
    }
}
