package com.playground.metrics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.app.dto.ActuatorProbeResult;
import com.playground.metrics.app.dto.DashboardResponse.ServiceCell;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.ActuatorHealthPort;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import com.playground.metrics.domain.ServiceProbeTarget;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BuildServicesUseCaseTest {

    @Test
    void composesElevenCellsInCanonicalOrder() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        ActuatorHealthPort actuator = Mockito.mock(ActuatorHealthPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        // sum_over_time(up{}[12s]) = 2 → both scrapes up
        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(Map.of(), 1_700_000_000L, 2.0))));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.reachableUp()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));

        BuildServicesUseCase useCase = new BuildServicesUseCase(prometheus, actuator, spark);
        List<ServiceCell> cells = useCase.execute().block();

        assertThat(cells).hasSize(11);
        // ADR-15 §17 canonical order: 6 BCs, then spark, then 4 obs containers
        assertThat(cells.stream().map(ServiceCell::name).toList())
                .containsExactly(
                        "playground-backend-gateway",
                        "playground-backend-identity-api",
                        "playground-backend-docs-api",
                        "playground-backend-rag-ingestion-api",
                        "playground-backend-rag-chat-api",
                        "playground-backend-metrics-api",
                        "spark-inference-gateway",
                        "playground-prometheus",
                        "playground-loki",
                        "playground-alloy",
                        "playground-cadvisor");
        cells.forEach(c -> assertThat(c.status()).isEqualTo("up"));
    }

    @Test
    void allDownWhenScrapeMissesAndProbesFail() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        ActuatorHealthPort actuator = Mockito.mock(ActuatorHealthPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        // Scrape miss (empty samples → 0/2) + actuator unreachable → down per §9 row 1.
        when(prometheus.instantQuery(anyString())).thenReturn(Mono.just(List.of()));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.unreachable()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.down()));

        BuildServicesUseCase useCase = new BuildServicesUseCase(prometheus, actuator, spark);
        List<ServiceCell> cells = useCase.execute().block();

        cells.forEach(c -> assertThat(c.status()).isEqualTo("down"));
    }

    @Test
    void scrapeCleanButActuatorUnreachableMarksDegraded() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        ActuatorHealthPort actuator = Mockito.mock(ActuatorHealthPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(Map.of(), 1_700_000_000L, 2.0))));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.unreachable()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));

        BuildServicesUseCase useCase = new BuildServicesUseCase(prometheus, actuator, spark);
        List<ServiceCell> cells = useCase.execute().block();

        // The 10 scrape-monitored cells are degraded; spark stays up because
        // its verdict comes from the HEAD probe (which returned up()).
        cells.stream()
                .filter(c -> !c.name().equals("spark-inference-gateway"))
                .forEach(c -> assertThat(c.status()).as(c.name()).isEqualTo("degraded"));
        assertThat(cells.stream()
                        .filter(c -> c.name().equals("spark-inference-gateway"))
                        .findFirst()
                        .orElseThrow()
                        .status())
                .isEqualTo("up");
    }

    @Test
    void sparkVerdictIsolatedFromActuatorPath() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        ActuatorHealthPort actuator = Mockito.mock(ActuatorHealthPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(Map.of(), 1_700_000_000L, 2.0))));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.reachableUp()));
        // Spark HEAD reachable but non-2xx → degraded per ADR-15 §12.
        when(spark.probe()).thenReturn(Mono.just(new SparkProbeResult(true, false)));

        BuildServicesUseCase useCase = new BuildServicesUseCase(prometheus, actuator, spark);
        List<ServiceCell> cells = useCase.execute().block();

        ServiceCell sparkCell = cells.stream()
                .filter(c -> c.name().equals("spark-inference-gateway"))
                .findFirst()
                .orElseThrow();
        assertThat(sparkCell.status()).isEqualTo("degraded");
    }

    @Test
    void actuatorErrorDoesNotPropagateUpToZip() {
        // Defense-in-depth: even if the actuator adapter misbehaves and emits
        // an error, the use case must not collapse the entire response to
        // an error — it should treat the cell as unreachable and let §9
        // produce the verdict (degraded for clean scrape).
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        ActuatorHealthPort actuator = Mockito.mock(ActuatorHealthPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(Map.of(), 1_700_000_000L, 2.0))));
        when(actuator.probe(any())).thenReturn(Mono.error(new RuntimeException("adapter bug")));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));

        BuildServicesUseCase useCase = new BuildServicesUseCase(prometheus, actuator, spark);
        List<ServiceCell> cells = useCase.execute().block();

        assertThat(cells).hasSize(11);
        // The 10 scrape-monitored cells: scrape clean (2/2) + actuator
        // unreachable → degraded per §9. Spark stays up.
        cells.stream()
                .filter(c -> !c.name().equals("spark-inference-gateway"))
                .forEach(c -> assertThat(c.status()).isEqualTo("degraded"));
    }

    @Test
    void cellsOrderMatchesServiceProbeTargetAllVerbatim() {
        // Future BCs added to ServiceProbeTarget.ALL will land in
        // SERVICE_CELL_NAMES in the same order; this assertion will fail and
        // force a conscious decision about response ordering.
        assertThat(BuildServicesUseCase.SERVICE_CELL_NAMES)
                .isEqualTo(ServiceProbeTarget.ALL.stream()
                        .map(ServiceProbeTarget::name)
                        .toList());
    }
}
