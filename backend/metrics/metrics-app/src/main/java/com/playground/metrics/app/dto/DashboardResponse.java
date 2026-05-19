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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardResponse(
        Instant fetchedAt,
        String range,
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
            String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContainerCell(
            String name,
            Double cpuPct,
            Double memUsedMb,
            Double memLimitMb,
            Integer restartCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HostCell(
            Double cpuPct,
            Double memUsedGb,
            Double memTotalGb,
            Double diskUsedPct,
            Double diskUsedGb,
            Double diskTotalGb,
            List<Double> loadAvg) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SparkGatewayCell(
            String url,
            String status,
            Long latencyP95Ms,
            List<String> modelsLoaded) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JvmCell(
            String service,
            Double heapUsedMb,
            Double heapMaxMb,
            Integer threads,
            Double gcPauseP95Ms) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HttpRateCell(
            String service,
            Double rps,
            Double errorRate) {
    }
}
