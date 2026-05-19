package com.playground.metrics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.app.dto.DashboardResponse.ServiceCell;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BuildServicesUseCaseTest {

    @Test
    void composesElevenCellsIncludingSpark() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        // Every up{service="..."} probe returns up=1
        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(Map.of(), 1_700_000_000L, 1.0))));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));

        BuildServicesUseCase useCase = new BuildServicesUseCase(prometheus, spark);
        List<ServiceCell> cells = useCase.execute().block();

        assertThat(cells).hasSize(11);
        assertThat(cells.stream().map(ServiceCell::name))
                .contains("gateway", "identity-api", "docs-api", "rag-ingestion-api",
                        "rag-chat-api", "metrics-api",
                        "prometheus-playground", "loki-playground",
                        "alloy-playground", "cadvisor-playground",
                        "spark-inference-gateway");
        // All should be up given mocks
        cells.forEach(c -> assertThat(c.status()).isEqualTo("up"));
    }

    @Test
    void downServiceMarkedDown() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        // up{} returns empty list → 0 of 2 scrapes → down
        when(prometheus.instantQuery(anyString())).thenReturn(Mono.just(List.of()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.down()));

        BuildServicesUseCase useCase = new BuildServicesUseCase(prometheus, spark);
        List<ServiceCell> cells = useCase.execute().block();

        cells.forEach(c -> assertThat(c.status()).isEqualTo("down"));
    }
}
