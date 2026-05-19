package com.playground.metrics.app;

import com.playground.metrics.app.dto.DashboardResponse;
import com.playground.metrics.app.dto.DashboardResponse.ContainerCell;
import com.playground.metrics.app.dto.DashboardResponse.HostCell;
import com.playground.metrics.app.dto.DashboardResponse.HttpRateCell;
import com.playground.metrics.app.dto.DashboardResponse.JvmCell;
import com.playground.metrics.app.dto.DashboardResponse.ServiceCell;
import com.playground.metrics.app.dto.DashboardResponse.SparkGatewayCell;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import com.playground.metrics.domain.ContainerAllowlist;
import com.playground.metrics.domain.HealthVerdict;
import com.playground.metrics.domain.PromQlTemplate;
import com.playground.metrics.domain.Range;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bundled dashboard payload composer per spec §5.2 + ADR-15 §2.
 * Orchestrates the ~19 PromQL queries via {@link Mono#zip} so the
 * slowest-single-query is the gating factor (not the sum). Per ADR-15 §16
 * the P95 latency target is 400 ms; slice 1 is the happy-path baseline,
 * slice 2 layers in {@code PromQlBudgetEnforcer} + {@code "degraded": true}.
 *
 * <p>JVM-bearing services in the {@code jvm[]} array: the four BCs Story
 * 6 wants (rag-chat-api / docs-api / identity-api / rag-ingestion-api).
 * The {@code httpRate[]} array covers gateway + rag-chat-api + docs-api.
 */
@Service
public class BuildDashboardUseCase {

    /**
     * Every JVM-bearing service in the stack — six Spring Boot apps total
     * (the BC quadruplets' -api modules + the gateway). Spec §5.2 listed
     * four originally; the dashboard amendment in design context §2.1
     * (post-slice-1) widens this to "all JVM-bearing services". Same
     * deterministic order across polls so frontend card positions are
     * stable.
     */
    private static final List<String> JVM_SERVICES = List.of(
            "gateway",
            "identity-api",
            "docs-api",
            "rag-ingestion-api",
            "rag-chat-api",
            "metrics-api");

    /** Three HTTP-bearing services per spec §5.2 httpRate[] array. */
    private static final List<String> HTTP_SERVICES = List.of(
            "gateway", "rag-chat-api", "docs-api");

    private static final String SPARK_GATEWAY_URL = "host.docker.internal:10080";

    private final BuildServicesUseCase servicesUseCase;
    private final PrometheusPort prometheus;
    private final SparkGatewayProbePort sparkProbe;

    public BuildDashboardUseCase(
            BuildServicesUseCase servicesUseCase,
            PrometheusPort prometheus,
            SparkGatewayProbePort sparkProbe) {
        this.servicesUseCase = servicesUseCase;
        this.prometheus = prometheus;
        this.sparkProbe = sparkProbe;
    }

    public Mono<DashboardResponse> execute(Range range) {
        Mono<List<ServiceCell>> services = servicesUseCase.execute();
        Mono<List<ContainerCell>> containers = containerCells();
        Mono<HostCell> host = hostCell();
        Mono<SparkGatewayCell> spark = sparkCell();
        Mono<List<JvmCell>> jvm = jvmCells();
        Mono<List<HttpRateCell>> httpRate = httpRateCells();

        return Mono.zip(services, containers, host, spark, jvm, httpRate)
                .map(t -> new DashboardResponse(
                        Instant.now(),
                        range.token(),
                        t.getT1(),
                        t.getT2(),
                        t.getT3(),
                        t.getT4(),
                        t.getT5(),
                        t.getT6()));
    }

    private Mono<List<ContainerCell>> containerCells() {
        List<String> names = new ArrayList<>(ContainerAllowlist.all());
        return Flux.fromIterable(names)
                .flatMap(this::containerCell)
                .collectList();
    }

    private Mono<ContainerCell> containerCell(String name) {
        Mono<Double> cpu = prometheus.instantQuery(PromQlTemplate.containerCpu(name))
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> mem = prometheus.instantQuery(PromQlTemplate.containerMem(name))
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        return Mono.zip(cpu, mem)
                .map(t -> new ContainerCell(name, t.getT1(), t.getT2(), null, 0));
    }

    private Mono<HostCell> hostCell() {
        Mono<Double> cpu = prometheus.instantQuery(PromQlTemplate.hostCpu())
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> memUsed = prometheus.instantQuery(PromQlTemplate.hostMemUsed())
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> memTotal = prometheus.instantQuery(PromQlTemplate.hostMemTotal())
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> diskPct = prometheus.instantQuery(PromQlTemplate.hostDiskUsedPct())
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> diskUsedGb = prometheus.instantQuery(PromQlTemplate.hostDiskUsedGb())
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> diskTotalGb = prometheus.instantQuery(PromQlTemplate.hostDiskTotalGb())
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<List<Double>> loads = Mono.zip(
                        prometheus.instantQuery(PromQlTemplate.hostLoad1m())
                                .map(BuildDashboardUseCase::firstValueOrZero)
                                .onErrorReturn(0.0),
                        prometheus.instantQuery(PromQlTemplate.hostLoad5m())
                                .map(BuildDashboardUseCase::firstValueOrZero)
                                .onErrorReturn(0.0),
                        prometheus.instantQuery(PromQlTemplate.hostLoad15m())
                                .map(BuildDashboardUseCase::firstValueOrZero)
                                .onErrorReturn(0.0))
                .map(t -> List.of(t.getT1(), t.getT2(), t.getT3()));

        return Mono.zip(cpu, memUsed, memTotal, diskPct, diskUsedGb, diskTotalGb, loads)
                .map(t -> new HostCell(
                        t.getT1(),
                        t.getT2(),
                        t.getT3(),
                        t.getT4(),
                        t.getT5(),
                        t.getT6(),
                        t.getT7()));
    }

    private Mono<SparkGatewayCell> sparkCell() {
        Mono<HealthVerdict.Status> verdict = sparkProbe.probe()
                .map(p -> HealthVerdict.fromSparkProbe(p.reachable(), p.ok()))
                .onErrorReturn(HealthVerdict.Status.DOWN);
        Mono<List<String>> models = sparkProbe.listModels().onErrorReturn(List.of());
        Mono<Double> latency = prometheus.instantQuery(PromQlTemplate.sparkLatencyP95())
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        return Mono.zip(verdict, models, latency)
                .map(t -> new SparkGatewayCell(
                        SPARK_GATEWAY_URL,
                        t.getT1().token(),
                        Math.round(t.getT3() * 1000.0),
                        t.getT2()));
    }

    private Mono<List<JvmCell>> jvmCells() {
        return Flux.fromIterable(JVM_SERVICES)
                .flatMap(this::jvmCell)
                .collectList();
    }

    private Mono<JvmCell> jvmCell(String svc) {
        Mono<Double> heap = prometheus.instantQuery(PromQlTemplate.jvmHeap(svc))
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> heapMax = prometheus.instantQuery(PromQlTemplate.jvmHeapMax(svc))
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> threads = prometheus.instantQuery(PromQlTemplate.jvmThreads(svc))
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> gcPause = prometheus.instantQuery(PromQlTemplate.jvmGcPause(svc))
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        return Mono.zip(heap, heapMax, threads, gcPause)
                .map(t -> new JvmCell(
                        svc,
                        t.getT1(),
                        t.getT2(),
                        (int) Math.round(t.getT3()),
                        t.getT4() * 1000.0));
    }

    private Mono<List<HttpRateCell>> httpRateCells() {
        return Flux.fromIterable(HTTP_SERVICES)
                .flatMap(this::httpRateCell)
                .collectList();
    }

    private Mono<HttpRateCell> httpRateCell(String svc) {
        Mono<Double> rps = prometheus.instantQuery(PromQlTemplate.httpRate(svc))
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        Mono<Double> errorRate = prometheus.instantQuery(PromQlTemplate.httpErrorRate(svc))
                .map(BuildDashboardUseCase::firstValueOrZero)
                .onErrorReturn(0.0);
        return Mono.zip(rps, errorRate)
                .map(t -> new HttpRateCell(svc, t.getT1(), t.getT2()));
    }

    private static double firstValueOrZero(List<PrometheusSample> samples) {
        return samples.stream()
                .max(Comparator.comparingLong(PrometheusSample::ts))
                .map(PrometheusSample::value)
                .orElse(0.0);
    }
}
