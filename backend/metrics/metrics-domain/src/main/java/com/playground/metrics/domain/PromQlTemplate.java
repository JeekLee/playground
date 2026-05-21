package com.playground.metrics.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for the metric-id → PromQL template mapping per
 * ADR-15 §10. The frontend never sees raw PromQL; callers send opaque
 * metric ids that this class translates after validating the substitution
 * value against {@link ServiceAllowlist} / {@link ContainerAllowlist}.
 *
 * <p>Injection defense (ADR-15 §10 + Story 9): the only point where
 * caller-supplied data enters the PromQL string is the {@code <svc>} /
 * {@code <name>} substitution. Both are gated by the allowlists. Unknown
 * metric ids return 400 before any PromQL is composed.
 */
public final class PromQlTemplate {

    /** Template kind tells callers which allowlist (if any) applies. */
    public enum SubstitutionKind {
        NONE, SERVICE, CONTAINER
    }

    public record Template(String prefix, String promql, SubstitutionKind kind, String unit) {
    }

    /**
     * Order matters for {@link #lookup} — entries are scanned in registration
     * order so the longest-matching prefix wins. We use a {@link LinkedHashMap}
     * with no-prefix bare templates first, then prefixed templates.
     */
    private static final Map<String, Template> TEMPLATES = buildTemplates();

    private PromQlTemplate() {
        // static
    }

    private static Map<String, Template> buildTemplates() {
        LinkedHashMap<String, Template> m = new LinkedHashMap<>();

        // Bare (no substitution) — direct id → template lookup
        m.put("host-cpu", new Template(
                "host-cpu",
                "100 - (avg(rate(node_cpu_seconds_total{mode=\"idle\"}[1m])) * 100)",
                SubstitutionKind.NONE, "%"));
        m.put("host-mem-used", new Template(
                "host-mem-used",
                "(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes) / 1073741824",
                SubstitutionKind.NONE, "GB"));
        m.put("host-mem-total", new Template(
                "host-mem-total",
                "node_memory_MemTotal_bytes / 1073741824",
                SubstitutionKind.NONE, "GB"));
        // Disk metrics aggregate via max() over ext4 filesystems because the
        // Alloy unix exporter runs inside a container and reports per-mount
        // rows for the container's mount namespace (e.g. /etc/alloy/...),
        // never the host root. All those rows back the same physical device,
        // so max(size) and max(avail) recover the underlying device totals.
        m.put("host-disk-used-pct", new Template(
                "host-disk-used-pct",
                "(1 - max(node_filesystem_avail_bytes{fstype=\"ext4\"})"
                        + " / max(node_filesystem_size_bytes{fstype=\"ext4\"})) * 100",
                SubstitutionKind.NONE, "%"));
        m.put("host-disk-used-gb", new Template(
                "host-disk-used-gb",
                "(max(node_filesystem_size_bytes{fstype=\"ext4\"})"
                        + " - max(node_filesystem_avail_bytes{fstype=\"ext4\"})) / 1073741824",
                SubstitutionKind.NONE, "GB"));
        m.put("host-disk-total-gb", new Template(
                "host-disk-total-gb",
                "max(node_filesystem_size_bytes{fstype=\"ext4\"}) / 1073741824",
                SubstitutionKind.NONE, "GB"));
        m.put("host-load-1m", new Template(
                "host-load-1m", "node_load1", SubstitutionKind.NONE, "n"));
        m.put("host-load-5m", new Template(
                "host-load-5m", "node_load5", SubstitutionKind.NONE, "n"));
        m.put("host-load-15m", new Template(
                "host-load-15m", "node_load15", SubstitutionKind.NONE, "n"));
        m.put("spark-latency-p95", new Template(
                "spark-latency-p95",
                "histogram_quantile(0.95, sum(rate(http_client_requests_seconds_bucket"
                        + "{target=\"spark-inference-gateway\"}[1m])) by (le, uri))",
                SubstitutionKind.NONE, "s"));

        // Prefixed (with substitution)
        m.put("jvm-heap", new Template(
                "jvm-heap",
                "jvm_memory_used_bytes{area=\"heap\",service=\"%s\"} / 1048576",
                SubstitutionKind.SERVICE, "MB"));
        m.put("jvm-heap-max", new Template(
                "jvm-heap-max",
                // `jvm_memory_max_bytes{area="heap"}` returns one row per G1
                // memory pool, and G1's dynamic pools (Eden, Survivor) report
                // -1 because their per-pool max is undefined. Only the Old
                // Gen row carries the real -Xmx value. Micrometer ships
                // `jvm_gc_max_data_size_bytes` as the single-value canonical
                // "max heap usable across all generations" — that's the
                // metric the dashboard wants.
                "jvm_gc_max_data_size_bytes{service=\"%s\"} / 1048576",
                SubstitutionKind.SERVICE, "MB"));
        m.put("jvm-nonheap", new Template(
                "jvm-nonheap",
                "jvm_memory_used_bytes{area=\"nonheap\",service=\"%s\"} / 1048576",
                SubstitutionKind.SERVICE, "MB"));
        m.put("jvm-gc-pause", new Template(
                "jvm-gc-pause",
                "rate(jvm_gc_pause_seconds_sum{service=\"%s\"}[5m]) "
                        + "/ rate(jvm_gc_pause_seconds_count{service=\"%s\"}[5m])",
                SubstitutionKind.SERVICE, "s"));
        m.put("jvm-threads", new Template(
                "jvm-threads",
                "jvm_threads_live_threads{service=\"%s\"}",
                SubstitutionKind.SERVICE, "count"));
        m.put("http-rate", new Template(
                "http-rate",
                "sum(rate(http_server_requests_seconds_count{service=\"%s\"}[1m])) by (service)",
                SubstitutionKind.SERVICE, "req/s"));
        m.put("http-error-rate", new Template(
                "http-error-rate",
                "sum(rate(http_server_requests_seconds_count{service=\"%s\",status=~\"5..\"}[1m]))"
                        + " / sum(rate(http_server_requests_seconds_count{service=\"%s\"}[1m]))",
                SubstitutionKind.SERVICE, "ratio"));
        m.put("http-latency-p95", new Template(
                "http-latency-p95",
                "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket"
                        + "{service=\"%s\"}[1m])) by (le))",
                SubstitutionKind.SERVICE, "s"));
        m.put("service-up", new Template(
                "service-up",
                "up{service=\"%s\"}",
                SubstitutionKind.SERVICE, "0/1"));
        m.put("container-cpu", new Template(
                "container-cpu",
                "rate(container_cpu_usage_seconds_total{name=\"%s\"}[1m]) * 100",
                SubstitutionKind.CONTAINER, "%"));
        m.put("container-mem", new Template(
                "container-mem",
                "container_memory_working_set_bytes{name=\"%s\"} / 1048576",
                SubstitutionKind.CONTAINER, "MB"));
        m.put("container-restart", new Template(
                "container-restart",
                "container_start_time_seconds{name=\"%s\"}",
                SubstitutionKind.CONTAINER, "s"));

        return Map.copyOf(m);
    }

    /** Returns the bare template if {@code metricId} is one of the no-substitution ids. */
    public static Optional<Template> lookupBare(String metricId) {
        Template t = TEMPLATES.get(metricId);
        if (t != null && t.kind() == SubstitutionKind.NONE) {
            return Optional.of(t);
        }
        return Optional.empty();
    }

    /**
     * Resolves a metric id into the final PromQL string. Handles both bare ids
     * (no substitution) and {@code <prefix>-<arg>} ids (service / container
     * substitution gated by the appropriate allowlist).
     *
     * @return final PromQL string, never containing un-validated caller input.
     * @throws IllegalArgumentException if the metric id is unknown OR the
     *     substitution value fails the allowlist check.
     */
    public static String resolve(String metricId) {
        if (metricId == null || metricId.isBlank()) {
            throw new IllegalArgumentException("metric id must not be blank");
        }
        // 1. Try bare lookup
        Template bare = TEMPLATES.get(metricId);
        if (bare != null && bare.kind() == SubstitutionKind.NONE) {
            return bare.promql();
        }
        // 2. Try prefix match — longest-prefix-wins by scanning all entries with
        //    a substitution kind and selecting the one whose prefix is the
        //    longest match.
        Template winner = null;
        String arg = null;
        for (Map.Entry<String, Template> e : TEMPLATES.entrySet()) {
            Template t = e.getValue();
            if (t.kind() == SubstitutionKind.NONE) {
                continue;
            }
            String prefix = e.getKey() + "-";
            if (metricId.startsWith(prefix)
                    && (winner == null || prefix.length() > (winner.prefix().length() + 1))) {
                winner = t;
                arg = metricId.substring(prefix.length());
            }
        }
        if (winner == null || arg == null || arg.isEmpty()) {
            throw new IllegalArgumentException("Unknown metric id: " + metricId);
        }
        // 3. Allowlist check on the substitution value
        switch (winner.kind()) {
            case SERVICE -> {
                if (!ServiceAllowlist.contains(arg)) {
                    throw new IllegalArgumentException("Unknown service: " + arg);
                }
            }
            case CONTAINER -> {
                if (!ContainerAllowlist.contains(arg)) {
                    throw new IllegalArgumentException("Unknown container: " + arg);
                }
            }
            default -> throw new IllegalStateException("unreachable");
        }
        // 4. Format the template — caller-supplied data goes through a
        //    String.format("%s", arg) call ONLY after the allowlist check.
        return formatTemplate(winner.promql(), arg);
    }

    public static Optional<Template> templateOf(String metricId) {
        if (metricId == null) {
            return Optional.empty();
        }
        Template bare = TEMPLATES.get(metricId);
        if (bare != null) {
            return Optional.of(bare);
        }
        for (Map.Entry<String, Template> e : TEMPLATES.entrySet()) {
            Template t = e.getValue();
            if (t.kind() == SubstitutionKind.NONE) {
                continue;
            }
            String prefix = e.getKey() + "-";
            if (metricId.startsWith(prefix) && metricId.length() > prefix.length()) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * Builds a PromQL string for the {@code service-up} template — used by
     * {@code BuildServicesUseCase} for the per-service {@code up{}} probes.
     */
    public static String serviceUp(String svc) {
        if (!ServiceAllowlist.contains(svc)) {
            throw new IllegalArgumentException("Unknown service: " + svc);
        }
        return formatTemplate(TEMPLATES.get("service-up").promql(), svc);
    }

    /**
     * Counts {@code up == 1} samples in the last 2 scrape cycles (~10s at
     * the 5s scrape cadence per ADR-15 §5). Returns 0/1/2 — the exact input
     * shape {@link HealthVerdict#from(int, boolean, boolean)} expects per
     * ADR-15 §9.
     *
     * <p>Window is 12 seconds (2 × 5s with 2s margin) so a scrape that lands
     * right at the boundary still counts. Adjust if scrape cadence changes.
     */
    public static String serviceUpLastTwoScrapes(String svc) {
        if (!ServiceAllowlist.contains(svc)) {
            throw new IllegalArgumentException("Unknown service: " + svc);
        }
        // `max by (service)`로 multiple-job emit을 정규화 — 예: alloy가
        // prometheus.yml의 `job=alloy` (15s scrape, [12s] window에서 0~1개만
        // 잡힘) + alloy 자체 `observability_self` (5s scrape, 2개 잡힘)에서
        // 동시에 emit. samples.get(0)만 보던 호출자가 첫 job 값에 좌우되어
        // false-positive `degraded`로 떨어지던 버그 fix. job별 sample의 가장
        // 큰 값을 service에 대해 단일화.
        return String.format(
                "max by (service) (sum_over_time(up{service=\"%s\"}[12s]))", svc);
    }

    /**
     * 스택 컨테이너의 verdict 시그널 — cAdvisor가 마지막으로 메트릭을 emit한 후
     * 흐른 초. {@code HealthVerdict#fromContainerAge}가 이 값을 받아 ADR-15 §13
     * (amended) 룰로 verdict 결정.
     *
     * <p>cAdvisor는 같은 컨테이너에 대해 cgroup level별로 여러 row를 emit하므로
     * {@code max by (name)}으로 가장 최신 emit 시점을 단일화.
     */
    public static String containerLastSeenAge(String name) {
        if (!ContainerAllowlist.contains(name)) {
            throw new IllegalArgumentException("Unknown container: " + name);
        }
        return String.format(
                "time() - max by (name) (container_last_seen{name=\"%s\"})", name);
    }

    public static String jvmHeap(String svc) {
        return resolve("jvm-heap-" + svc);
    }

    public static String jvmHeapMax(String svc) {
        return resolve("jvm-heap-max-" + svc);
    }

    public static String jvmNonheap(String svc) {
        return resolve("jvm-nonheap-" + svc);
    }

    public static String jvmGcPause(String svc) {
        return resolve("jvm-gc-pause-" + svc);
    }

    public static String jvmThreads(String svc) {
        return resolve("jvm-threads-" + svc);
    }

    public static String httpRate(String svc) {
        return resolve("http-rate-" + svc);
    }

    public static String httpErrorRate(String svc) {
        return resolve("http-error-rate-" + svc);
    }

    public static String httpLatencyP95(String svc) {
        return resolve("http-latency-p95-" + svc);
    }

    public static String containerCpu(String name) {
        return resolve("container-cpu-" + name);
    }

    public static String containerMem(String name) {
        return resolve("container-mem-" + name);
    }

    public static String containerRestart(String name) {
        return resolve("container-restart-" + name);
    }

    public static String hostCpu() {
        return TEMPLATES.get("host-cpu").promql();
    }

    public static String hostMemUsed() {
        return TEMPLATES.get("host-mem-used").promql();
    }

    public static String hostMemTotal() {
        return TEMPLATES.get("host-mem-total").promql();
    }

    public static String hostDiskUsedPct() {
        return TEMPLATES.get("host-disk-used-pct").promql();
    }

    public static String hostDiskUsedGb() {
        return TEMPLATES.get("host-disk-used-gb").promql();
    }

    public static String hostDiskTotalGb() {
        return TEMPLATES.get("host-disk-total-gb").promql();
    }

    public static String hostLoad1m() {
        return TEMPLATES.get("host-load-1m").promql();
    }

    public static String hostLoad5m() {
        return TEMPLATES.get("host-load-5m").promql();
    }

    public static String hostLoad15m() {
        return TEMPLATES.get("host-load-15m").promql();
    }

    public static String sparkLatencyP95() {
        return TEMPLATES.get("spark-latency-p95").promql();
    }

    private static String formatTemplate(String template, String arg) {
        // Templates use %s placeholders for repeated substitution; the same arg
        // fills every placeholder (e.g., jvm-gc-pause has two).
        int placeholders = countPlaceholders(template);
        Object[] args = new Object[placeholders];
        for (int i = 0; i < placeholders; i++) {
            args[i] = arg;
        }
        return String.format(template, args);
    }

    private static int countPlaceholders(String template) {
        int count = 0;
        int idx = 0;
        while ((idx = template.indexOf("%s", idx)) != -1) {
            count++;
            idx += 2;
        }
        return count;
    }
}
