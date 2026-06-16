package com.playground.metrics.app;

import com.playground.metrics.app.dto.ActuatorProbeResult;
import com.playground.metrics.app.dto.DashboardResponse.ServiceCell;
import com.playground.metrics.app.dto.PrometheusSample;
import com.playground.metrics.app.dto.SparkProbeResult;
import com.playground.metrics.app.port.ActuatorHealthPort;
import com.playground.metrics.app.port.PrometheusPort;
import com.playground.metrics.app.port.SparkGatewayProbePort;
import com.playground.metrics.domain.HealthVerdict;
import com.playground.metrics.domain.PromQlTemplate;
import com.playground.metrics.domain.ServiceProbeTarget;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Composes the active {@code services[]} array per spec §5.2 + ADR-15
 * §9 + §17.
 *
 * <p>Cell catalog (canonical order, also drives the dashboard grid):
 * <ol>
 *   <li>gateway, identity-api, docs-api, chat-api, metrics-api
 *       (active BCs — {@code up{}} scrape + actuator/health)</li>
 *   <li>spark-inference-gateway (HEAD {@code /v1/models})</li>
 *   <li>playground-prometheus, playground-loki, playground-alloy,
 *       playground-cadvisor (native readiness endpoints + {@code up{}}
 *       self-scrape)</li>
 * </ol>
 *
 * <p>Per ADR-15 §9 each non-spark cell composes two parallel probes via
 * {@link Mono#zip}: {@code sum_over_time(up{service="x"}[12s])} (last-2-scrape
 * count, fed to {@link HealthVerdict#from(int, boolean, boolean)} as 0/1/2) +
 * {@link ActuatorHealthPort#probe(ServiceProbeTarget)}. The two-probe
 * fan-out keeps the per-cell wall-clock equal to the slower probe, not the
 * sum — supporting the 100 ms P95 target for {@code GET /api/metrics/services}
 * (ADR-15 §16 derivation pegs each probe at <50 ms typical on the compose
 * network).
 *
 * <p>Spark uses {@link SparkGatewayProbePort#probe()} only — {@code up{}}
 * doesn't apply (host process, no scrape job) and there is no actuator
 * endpoint (vLLM is non-Spring). ADR-15 §12 pins the rule:
 * 200 ⇒ up, non-2xx ⇒ degraded, timeout/refused ⇒ down.
 */
@Service
@RequiredArgsConstructor
public class BuildServicesUseCase {

    /** Convenience snapshot of the 10 scrape-monitored cell names (BCs + observability). */
    public static final List<String> SCRAPE_CELL_NAMES = ServiceProbeTarget.ALL.stream()
            .filter(ServiceProbeTarget::scrapeMonitored)
            .map(ServiceProbeTarget::name)
            .toList();

    /** Convenience snapshot of all 11 cell names in ADR-15 §17 order. */
    public static final List<String> SERVICE_CELL_NAMES = ServiceProbeTarget.ALL.stream()
            .map(ServiceProbeTarget::name)
            .toList();

    private final PrometheusPort prometheus;
    private final ActuatorHealthPort actuator;
    private final SparkGatewayProbePort sparkProbe;

    /**
     * Executes the service-probe fan-out. Result order matches
     * {@link ServiceProbeTarget#ALL} verbatim so the frontend sees a stable
     * cell ordering across polls.
     */
    public Mono<List<ServiceCell>> execute() {
        // Flux.flatMapSequential — 순서는 보존하면서 inner Mono들을 eagerly
        // subscribe해서 11개 셀의 probe를 병렬 실행. 이전 `concatMap`은 inner를
        // 하나씩 직렬 subscribe해서 11 × probe latency = wall-clock이 됐었음.
        // ADR-15 §16의 100ms P95 derivation은 fan-out 전제.
        return Flux.fromIterable(ServiceProbeTarget.ALL)
                .flatMapSequential(this::cellFor)
                .collectList();
    }

    private Mono<ServiceCell> cellFor(ServiceProbeTarget target) {
        return switch (target.kind()) {
            case SPARK -> sparkCell(target);
            case BC, OBSERVABILITY -> scrapeAndActuatorCell(target);
            case STACK -> stackCell(target);
        };
    }

    /**
     * Stack container verdict — cAdvisor {@code container_last_seen} age 단일
     * 시그널로 결정 (ADR-15 §13 amended). actuator도 없고 {@code up{}} scrape
     * 도 없는 컨테이너 (postgres / redis / kafka / opensearch / frontend).
     */
    private Mono<ServiceCell> stackCell(ServiceProbeTarget target) {
        return prometheus.instantQuery(PromQlTemplate.containerLastSeenAge(target.name()))
                .map(samples -> containerAgeSeconds(samples))
                .map(age -> {
                    HealthVerdict.Status status = HealthVerdict.fromContainerAge(age);
                    return new ServiceCell(target.name(), status.token(), null, null, null, null, null);
                })
                // PromQL 오류 → down (정보 없음)
                .onErrorReturn(downCell(target.name()));
    }

    /**
     * `time() - container_last_seen` 결과를 단일 age로 추출. 빈 결과 또는
     * NaN은 {@link Double#NaN} 반환 → {@link HealthVerdict#fromContainerAge}가
     * down으로 처리.
     */
    static double containerAgeSeconds(List<PrometheusSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return Double.NaN;
        }
        return samples.get(0).value();
    }

    /**
     * Combined verdict for scrape-monitored cells: parallelizes the
     * {@code up{}}-last-two-scrapes Prometheus query and the actuator /
     * readiness probe, then feeds both into the ADR-15 §9 truth table.
     */
    private Mono<ServiceCell> scrapeAndActuatorCell(ServiceProbeTarget target) {
        Mono<Integer> scrape = prometheus.instantQuery(
                        PromQlTemplate.serviceUpLastTwoScrapes(target.name()))
                .map(BuildServicesUseCase::lastTwoScrapeCount)
                // Scrape error is "scrape miss" — 0/2 — feeding §9 to "down"
                // (or "degraded" if actuator is up, which the table handles).
                .onErrorReturn(0);
        Mono<ActuatorProbeResult> probe = actuator.probe(target)
                // Defense-in-depth: the port contract says no errors, but a
                // misbehaving adapter must not bring down the dashboard.
                .onErrorReturn(ActuatorProbeResult.unreachable());

        return Mono.zip(scrape, probe)
                .map(t -> verdictCell(target.name(), t.getT1(), t.getT2()));
    }

    private static ServiceCell verdictCell(String svc, int scrapeUpCount, ActuatorProbeResult probe) {
        HealthVerdict.Status status = HealthVerdict.from(scrapeUpCount, probe.reachable(), probe.up());
        return new ServiceCell(svc, status.token(), null, null, null, null, null);
    }

    /**
     * spark-inference-gateway: HEAD {@code /v1/models} → {@code HealthVerdict.fromSparkProbe(...)}.
     * No Prometheus, no actuator.
     */
    private Mono<ServiceCell> sparkCell(ServiceProbeTarget target) {
        return sparkProbe.probe()
                .map(p -> sparkCellOf(target.name(), p))
                .onErrorReturn(downCell(target.name()));
    }

    /**
     * Converts a {@code sum_over_time(up[12s])} sample list into the
     * 0/1/2 input {@link HealthVerdict#from(int, boolean, boolean)} expects.
     *
     * <p>Prometheus emits one sample per timeseries; we take the latest. A
     * fractional value (e.g., one scrape interval boundary that captured 1.5
     * average) is clamped via integer truncation — Prometheus's
     * {@code sum_over_time} on a 0/1 series is always integer-valued in
     * practice, but the clamp protects against future scrape-cadence changes.
     */
    static int lastTwoScrapeCount(List<PrometheusSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        double v = samples.get(0).value();
        int n = (int) Math.floor(v);
        if (n < 0) {
            return 0;
        }
        return Math.min(n, 2);
    }

    private static ServiceCell sparkCellOf(String name, SparkProbeResult result) {
        HealthVerdict.Status status = HealthVerdict.fromSparkProbe(result.reachable(), result.ok());
        return new ServiceCell(name, status.token(), null, null, null, null, null);
    }

    private static ServiceCell downCell(String svc) {
        return new ServiceCell(svc, HealthVerdict.Status.DOWN.token(), null, null, null, null, null);
    }
}
