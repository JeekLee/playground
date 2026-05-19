package com.playground.metrics.app;

import com.playground.metrics.app.dto.DashboardResponse.ServiceCell;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import com.playground.metrics.domain.HealthVerdict;
import com.playground.metrics.domain.PromQlTemplate;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Composes the {@code services[]} array per spec §5.2 + ADR-15 §9 + §17.
 * Eleven cells in total: six BCs + spark-inference-gateway + four
 * observability containers (§17 self-monitoring).
 *
 * <p>Slice 1: combines Prometheus {@code up{}} readings into the verdict
 * using {@link HealthVerdict#from(int, boolean, boolean)}. The actuator
 * probe is not yet wired in slice 1 — we pass {@code actuatorReachable=true,
 * actuatorUp=true} so the verdict reduces to "if scraped, up; if not
 * scraped, down". Slice 2 layers in the actuator probe.
 *
 * <p>{@code spark-inference-gateway} verdict comes from
 * {@link SparkGatewayProbePort} (HEAD {@code /v1/models}); no Prometheus
 * scrape exists for the host-process gateway.
 */
@Service
public class BuildServicesUseCase {

    /** Eleven service cells per ADR-15 §17. */
    public static final List<String> SERVICE_CELL_NAMES = List.of(
            "gateway",
            "identity-api",
            "docs-api",
            "rag-ingestion-api",
            "rag-chat-api",
            "metrics-api",
            "prometheus-playground",
            "loki-playground",
            "alloy-playground",
            "cadvisor-playground");

    private final PrometheusPort prometheus;
    private final SparkGatewayProbePort sparkProbe;

    public BuildServicesUseCase(PrometheusPort prometheus, SparkGatewayProbePort sparkProbe) {
        this.prometheus = prometheus;
        this.sparkProbe = sparkProbe;
    }

    public Mono<List<ServiceCell>> execute() {
        Flux<ServiceCell> scrapedCells = Flux.fromIterable(SERVICE_CELL_NAMES)
                .flatMap(this::cellFor);

        Mono<ServiceCell> sparkCell = sparkProbe.probe().map(BuildServicesUseCase::sparkCellOf);

        return Flux.concat(scrapedCells, sparkCell).collectList();
    }

    private Mono<ServiceCell> cellFor(String svc) {
        return prometheus.instantQuery(PromQlTemplate.serviceUp(svc))
                .map(samples -> verdictOf(svc, samples))
                .onErrorReturn(downCell(svc));
    }

    private static ServiceCell verdictOf(String svc, List<PrometheusSample> samples) {
        // Slice 1: a non-empty sample list with value 1.0 → up; anything else
        // (empty list, value 0.0) → down. Slice 2 will layer in the per-svc
        // /actuator/health probe per ADR-15 §9.
        int upCount = 0;
        for (PrometheusSample s : samples) {
            if (s.value() > 0.5) {
                upCount = 2; // synthesize "both of last 2 up" until slice 2
                break;
            }
        }
        HealthVerdict.Status status = HealthVerdict.from(upCount, true, true);
        return new ServiceCell(svc, status.token(), null, null, null, null, null);
    }

    private static ServiceCell downCell(String svc) {
        return new ServiceCell(svc, HealthVerdict.Status.DOWN.token(), null, null, null, null, null);
    }

    private static ServiceCell sparkCellOf(SparkProbeResult result) {
        HealthVerdict.Status status = HealthVerdict.fromSparkProbe(result.reachable(), result.ok());
        return new ServiceCell(
                "spark-inference-gateway", status.token(), null, null, null, null, null);
    }
}
