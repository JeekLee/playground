package com.playground.metrics.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Bundled dashboard payload per spec §5.2. Field names match the canonical
 * wire shape verbatim — the M5 frontend implementer is building against the
 * same spec; do not rename fields.
 *
 * <p>Time-series data is NOT included here — the frontend fetches each
 * chart's series separately via {@code GET /api/metrics/timeseries}
 * (parallelized + per-chart degradation).
 *
 * <p>Each widget cell carries a {@code degraded} boolean (default false /
 * omitted via {@link JsonInclude.Include#NON_DEFAULT}) per spec §7.3 + §8.2
 * + ADR-15 §C. When the {@code PromQlBudgetEnforcer} catches a per-widget
 * timeout the corresponding cell's {@code degraded} is set true and its
 * numeric fields are zeroed — the overall HTTP response stays 200.
 *
 * <p>The {@code pollIntervalSeconds} field echoes the backend-side
 * {@code METRICS_POLL_INTERVAL_S} env var per ADR-15 §18 so the frontend
 * polling cadence is configured server-side, not hardcoded in JS.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardResponse(
        Instant fetchedAt,
        String range,
        Integer pollIntervalSeconds,
        List<ServiceCell> services,
        List<ContainerCell> containers,
        HostCell host,
        SparkGatewayCell sparkGateway,
        List<JvmCell> jvm,
        List<HttpRateCell> httpRate) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceCell(
            String name,
            String status,
            Instant since,
            Long uptimeSec,
            String image,
            Long latencyP95Ms,
            String note,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean degraded) {

        public ServiceCell(String name, String status, Instant since, Long uptimeSec,
                String image, Long latencyP95Ms, String note) {
            this(name, status, since, uptimeSec, image, latencyP95Ms, note, false);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContainerCell(
            String name,
            Double cpuPct,
            Double memUsedMb,
            Double memLimitMb,
            Integer restartCount,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean degraded) {

        public ContainerCell(String name, Double cpuPct, Double memUsedMb,
                Double memLimitMb, Integer restartCount) {
            this(name, cpuPct, memUsedMb, memLimitMb, restartCount, false);
        }

        public static ContainerCell degraded(String name) {
            return new ContainerCell(name, 0.0, 0.0, null, 0, true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HostCell(
            Double cpuPct,
            Double memUsedGb,
            Double memTotalGb,
            Double diskUsedPct,
            Double diskUsedGb,
            Double diskTotalGb,
            List<Double> loadAvg,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean degraded) {

        public HostCell(Double cpuPct, Double memUsedGb, Double memTotalGb,
                Double diskUsedPct, Double diskUsedGb, Double diskTotalGb,
                List<Double> loadAvg) {
            this(cpuPct, memUsedGb, memTotalGb, diskUsedPct, diskUsedGb, diskTotalGb, loadAvg, false);
        }

        // Static factory renamed away from `degraded()` because Java records
        // reserve component-name slots for the auto-generated accessor — a
        // zero-arg static `degraded()` collides with the boolean component
        // accessor of the same name.
        public static HostCell degradedSentinel() {
            return new HostCell(0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    List.of(0.0, 0.0, 0.0), true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SparkGatewayCell(
            String url,
            String status,
            Long latencyP95Ms,
            List<String> modelsLoaded,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean degraded) {

        public SparkGatewayCell(String url, String status, Long latencyP95Ms,
                List<String> modelsLoaded) {
            this(url, status, latencyP95Ms, modelsLoaded, false);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JvmCell(
            String service,
            Double heapUsedMb,
            Double heapMaxMb,
            Integer threads,
            Double gcPauseP95Ms,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean degraded) {

        public JvmCell(String service, Double heapUsedMb, Double heapMaxMb,
                Integer threads, Double gcPauseP95Ms) {
            this(service, heapUsedMb, heapMaxMb, threads, gcPauseP95Ms, false);
        }

        public static JvmCell degraded(String service) {
            return new JvmCell(service, 0.0, 0.0, 0, 0.0, true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HttpRateCell(
            String service,
            Double rps,
            Double errorRate,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean degraded) {

        public HttpRateCell(String service, Double rps, Double errorRate) {
            this(service, rps, errorRate, false);
        }

        public static HttpRateCell degraded(String service) {
            return new HttpRateCell(service, 0.0, 0.0, true);
        }
    }
}
