package com.playground.metrics.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.playground.metrics.app.dto.ActuatorProbeResult;
import com.playground.metrics.app.dto.DashboardResponse;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.ActuatorHealthPort;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import com.playground.metrics.domain.Range;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class BuildDashboardUseCaseTest {

    private static final Duration TEST_BUDGET = Duration.ofSeconds(2);

    @Test
    void composesDashboardShapePerSpec5_2() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        ActuatorHealthPort actuator = Mockito.mock(ActuatorHealthPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.just(List.of(new PrometheusSample(
                        Map.of("service", "chat-api"), 1_700_000_000L, 42.0))));
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.reachableUp()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));
        when(spark.listModels()).thenReturn(Mono.just(List.of("BGE-M3", "Qwen3-32B")));

        BuildServicesUseCase servicesUseCase = new BuildServicesUseCase(prometheus, actuator, spark);
        PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(TEST_BUDGET);
        BuildDashboardUseCase useCase = new BuildDashboardUseCase(
                servicesUseCase, prometheus, spark, enforcer, 15);

        DashboardResponse response = useCase.execute(Range.H_1).block();

        assertThat(response).isNotNull();
        assertThat(response.fetchedAt()).isNotNull();
        assertThat(response.range()).isEqualTo("1h");
        assertThat(response.pollIntervalSeconds()).isEqualTo(15);
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
        // No widget degraded on the happy path
        response.containers().forEach(c -> assertThat(c.degraded()).isFalse());
        response.jvm().forEach(c -> assertThat(c.degraded()).isFalse());
        response.httpRate().forEach(c -> assertThat(c.degraded()).isFalse());
        assertThat(response.host().degraded()).isFalse();
        assertThat(response.sparkGateway().degraded()).isFalse();
    }

    @Test
    void survivesPrometheusErrorsPerWidget() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        ActuatorHealthPort actuator = Mockito.mock(ActuatorHealthPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        when(prometheus.instantQuery(anyString()))
                .thenReturn(Mono.error(new RuntimeException("prometheus down")));
        // Whole observability stack down → actuator probe also fails. Each
        // §9 row resolves to either down (scrape miss + actuator unreachable)
        // or down (1 of 2 + actuator unreachable).
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.unreachable()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.down()));
        when(spark.listModels()).thenReturn(Mono.just(List.of()));

        BuildServicesUseCase servicesUseCase = new BuildServicesUseCase(prometheus, actuator, spark);
        PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(TEST_BUDGET);
        BuildDashboardUseCase useCase = new BuildDashboardUseCase(
                servicesUseCase, prometheus, spark, enforcer, 15);

        DashboardResponse response = useCase.execute(Range.H_1).block();

        assertThat(response).isNotNull();
        assertThat(response.range()).isEqualTo("1h");
        // Errors collapse to zero values per widget — these are NOT timeouts
        // (the per-widget onErrorReturn(0.0) in BuildDashboardUseCase catches
        // the prometheus 5xx before the budget enforcer sees it). degraded
        // stays false; the cell shows zero data, which is the slice-1 happy-
        // path "no data" rendering. degraded=true is reserved for the budget
        // overrun path, exercised in survivesSingleWidgetTimeout below.
        response.containers().forEach(c -> assertThat(c.cpuPct()).isEqualTo(0.0));
        response.services().forEach(c -> assertThat(c.status()).isEqualTo("down"));
    }

    @Test
    void survivesSingleWidgetTimeout() {
        PrometheusPort prometheus = Mockito.mock(PrometheusPort.class);
        ActuatorHealthPort actuator = Mockito.mock(ActuatorHealthPort.class);
        SparkGatewayProbePort spark = Mockito.mock(SparkGatewayProbePort.class);

        // host queries will hang past the budget; everything else returns
        // quickly. Distinguish "host" queries by their PromQL text — the host
        // templates start with "100 - (avg" (cpu), "(node_memory_..." (mem),
        // "(1 - max(node_filesystem..." (disk), or "node_load*". The simplest
        // discriminator: any query mentioning "node_" or "node_load" is host.
        when(prometheus.instantQuery(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0, String.class);
            if (q != null && (q.contains("node_") || q.startsWith("100 - (avg"))) {
                return Mono.<List<PrometheusSample>>never();
            }
            return Mono.just(List.of(new PrometheusSample(
                    Map.of(), 1_700_000_000L, 7.0)));
        });
        when(actuator.probe(any())).thenReturn(Mono.just(ActuatorProbeResult.reachableUp()));
        when(spark.probe()).thenReturn(Mono.just(SparkProbeResult.up()));
        when(spark.listModels()).thenReturn(Mono.just(List.of("BGE-M3")));

        BuildServicesUseCase servicesUseCase = new BuildServicesUseCase(prometheus, actuator, spark);
        // Short budget so the test stays fast.
        PromQlBudgetEnforcer enforcer = new PromQlBudgetEnforcer(Duration.ofMillis(200));
        BuildDashboardUseCase useCase = new BuildDashboardUseCase(
                servicesUseCase, prometheus, spark, enforcer, 15);

        DashboardResponse response = useCase.execute(Range.H_1).block(Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        // Host cell degraded
        assertThat(response.host()).isNotNull();
        assertThat(response.host().degraded()).isTrue();
        // JVM / httpRate cells are NOT host queries — they finish quickly and
        // are not degraded.
        response.jvm().forEach(c -> assertThat(c.degraded()).isFalse());
        response.httpRate().forEach(c -> assertThat(c.degraded()).isFalse());
        // Other widgets surfaced normally — dashboard returns 200 with partial
        // data, NOT 5xx (spec §8.2).
        assertThat(response.range()).isEqualTo("1h");
    }
}
