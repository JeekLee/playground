package com.playground.metrics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.app.dto.DashboardResponse;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import com.playground.metrics.domain.Range;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BuildDashboardUseCaseTest {

    @Test
    void composesDashboardShapePerSpec5_2() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(
                        Map.of("service", "rag-chat-api"), 1_700_000_000L, 42.0))));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));
        when(spark.listModels()).thenReturn(Mono.just(List.of("BGE-M3", "Qwen3-32B")));

        BuildServicesUseCase servicesUseCase = new BuildServicesUseCase(prometheus, spark);
        BuildDashboardUseCase useCase = new BuildDashboardUseCase(servicesUseCase, prometheus, spark);

        DashboardResponse response = useCase.execute(Range.H_1).block();

        assertThat(response).isNotNull();
        assertThat(response.fetchedAt()).isNotNull();
        assertThat(response.range()).isEqualTo("1h");
        // 11 service cells (6 BCs + 4 obs containers + spark)
        assertThat(response.services()).hasSize(11);
        // 14 container cells per ContainerAllowlist
        assertThat(response.containers()).isNotEmpty();
        // Host cell present with loadAvg array of 3
        assertThat(response.host()).isNotNull();
        assertThat(response.host().loadAvg()).hasSize(3);
        // Spark gateway present with url + modelsLoaded
        assertThat(response.sparkGateway()).isNotNull();
        assertThat(response.sparkGateway().url()).isEqualTo("host.docker.internal:10080");
        assertThat(response.sparkGateway().modelsLoaded()).contains("BGE-M3", "Qwen3-32B");
        // 6 JVM cells — every JVM-bearing service in the stack (5 BCs + gateway)
        assertThat(response.jvm()).hasSize(6);
        // 3 HTTP rate cells
        assertThat(response.httpRate()).hasSize(3);
    }

    @Test
    void survivesPrometheusErrorsPerWidget() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.error(new RuntimeException("prometheus down")));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.down()));
        when(spark.listModels()).thenReturn(Mono.just(List.of()));

        BuildServicesUseCase servicesUseCase = new BuildServicesUseCase(prometheus, spark);
        BuildDashboardUseCase useCase = new BuildDashboardUseCase(servicesUseCase, prometheus, spark);

        DashboardResponse response = useCase.execute(Range.H_1).block();

        assertThat(response).isNotNull();
        assertThat(response.range()).isEqualTo("1h");
        // Errors collapse to zero values per widget (slice 1 happy-path
        // baseline; slice 2 will flip "degraded": true).
        response.containers().forEach(c -> assertThat(c.cpuPct()).isEqualTo(0.0));
        response.services().forEach(c -> assertThat(c.status()).isEqualTo("down"));
    }
}
