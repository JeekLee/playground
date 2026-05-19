package com.playground.metrics.infra;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.playground.metrics.domain.LogEntry;
import com.playground.metrics.infra.config.MetricsHttpProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class LokiAdapterTest {

    private WireMockServer wm;
    private LokiAdapter adapter;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        MetricsHttpProperties props = new MetricsHttpProperties(
                new MetricsHttpProperties.Prometheus(null, 0),
                new MetricsHttpProperties.Loki(wm.baseUrl(), 15000),
                new MetricsHttpProperties.SparkGateway(null, 0));
        adapter = new LokiAdapter(WebClient.builder(), props);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void decodesStreamsIntoLogEntries() {
        String json = """
                {
                  "status":"success",
                  "data":{
                    "resultType":"streams",
                    "result":[
                      {
                        "stream":{"container":"rag-chat-api","service":"rag-chat-api","source":"docker"},
                        "values":[
                          ["1715763718234000000","{\\"level\\":\\"INFO\\",\\"msg\\":\\"hi\\"}"],
                          ["1715763717812000000","{\\"level\\":\\"WARN\\",\\"msg\\":\\"slow\\"}"]
                        ]
                      }
                    ]
                  }
                }
                """;
        wm.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        List<LogEntry> entries = adapter.queryRange(
                "{container=\"rag-chat-api\"} | json",
                Duration.ofMinutes(15), 200).block();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).service()).isEqualTo("rag-chat-api");
        assertThat(entries.get(0).level()).isEqualTo("INFO");
        assertThat(entries.get(1).level()).isEqualTo("WARN");
        assertThat(entries.get(0).message()).contains("hi");
    }

    @Test
    void emptyStreamsResultGivesEmptyList() {
        wm.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}")));

        List<LogEntry> entries = adapter.queryRange(
                "{container=\"rag-chat-api\"} | json",
                Duration.ofMinutes(15), 200).block();
        assertThat(entries).isEmpty();
    }
}
