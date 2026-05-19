/**
 * Fixture payloads for `/metrics` widgets.
 *
 * The base fixture mirrors spec §5.2's example verbatim where shapes
 * overlap, and extends it to cover all 11 service health cells per
 * ADR-15 §17 (6 BCs + spark + 4 observability self-cells). The values
 * are also the on-screen numbers in design context M5-metrics.md
 * Frame 1, so a smoke render of <DashboardPage data={fixture} /> matches
 * the canonical "loaded, all healthy" Figma frame visually.
 *
 * Used by Vitest snapshot smoke tests and dev-mode service-worker stubs.
 * Not shipped to production code paths.
 */

import type {
  DashboardResponse,
  TimeseriesResponse,
} from './types';

/**
 * "Loaded, all healthy" — every cell up; every chart populated.
 * Matches design context Frame 1 (node `74:598`).
 */
export const dashboardFixtureLoaded: DashboardResponse = {
  fetchedAt: '2026-05-19T07:42:13Z',
  range: '1h',
  services: [
    // Row 1 — 6 BC cells (ADR-15 §17 #1–#6).
    { name: 'gateway', status: 'up', since: '2026-05-19T04:10:02Z', uptimeSec: 12731, image: 'playground/gateway:0.4.1' },
    { name: 'identity-api', status: 'up', since: '2026-05-19T04:10:11Z', uptimeSec: 12722, image: 'playground/identity-api:0.4.1' },
    { name: 'docs-api', status: 'up', since: '2026-05-19T04:12:01Z', uptimeSec: 12612, image: 'playground/docs-api:0.4.1' },
    { name: 'rag-ingestion', status: 'up', since: '2026-05-19T04:12:30Z', uptimeSec: 12583, image: 'playground/rag-ingestion:0.4.1' },
    { name: 'rag-chat-api', status: 'up', since: '2026-05-19T05:28:14Z', uptimeSec: 8039, image: 'playground/rag-chat-api:0.4.1' },
    { name: 'metrics-api', status: 'up', since: '2026-05-19T06:40:00Z', uptimeSec: 3733, image: 'playground/metrics-api:0.4.1' },
    // Row 2 — spark + 4 observability self cells (ADR-15 §17 #7–#11).
    { name: 'spark-inference-gateway', status: 'up', latencyP95Ms: 340, note: '2 models' },
    { name: 'prometheus-playground', status: 'up', uptimeSec: 14000, note: '11 targets' },
    { name: 'loki-playground', status: 'up', uptimeSec: 14000, note: '3d retention' },
    { name: 'alloy-playground', status: 'up', uptimeSec: 14000, note: 'scraping 5 BCs' },
    { name: 'cadvisor-playground', status: 'up', uptimeSec: 14000, note: '14 containers' },
  ],
  containers: [
    { name: 'postgres-playground', cpuPct: 4.2, memUsedMb: 182, memLimitMb: 512, restartCount: 0 },
    { name: 'redis-playground', cpuPct: 0.8, memUsedMb: 42, memLimitMb: 256, restartCount: 0 },
    { name: 'kafka-playground', cpuPct: 11.5, memUsedMb: 620, memLimitMb: 1024, restartCount: 0 },
    { name: 'opensearch-playground', cpuPct: 8.3, memUsedMb: 480, memLimitMb: 1024, restartCount: 0 },
    { name: 'prometheus-playground', cpuPct: 1.1, memUsedMb: 110, memLimitMb: 512, restartCount: 0 },
    { name: 'loki-playground', cpuPct: 0.9, memUsedMb: 95, memLimitMb: 512, restartCount: 0 },
    { name: 'alloy-playground', cpuPct: 2.4, memUsedMb: 86, memLimitMb: 256, restartCount: 0 },
    { name: 'cadvisor-playground', cpuPct: 1.8, memUsedMb: 64, memLimitMb: 256, restartCount: 0 },
  ],
  host: {
    cpuPct: 18.2,
    memUsedGb: 12.4,
    memTotalGb: 64,
    diskUsedPct: 42,
    diskUsedGb: 420,
    diskTotalGb: 1000,
    loadAvg: [1.2, 0.8, 0.6],
  },
  sparkGateway: {
    url: 'host.docker.internal:10080',
    status: 'up',
    latencyP95Ms: 340,
    modelsLoaded: ['BGE-M3', 'Qwen3-32B'],
  },
  jvm: [
    { service: 'rag-chat-api', heapUsedMb: 420, heapMaxMb: 1024, threads: 48, gcPauseP95Ms: 12 },
    { service: 'docs-api', heapUsedMb: 280, heapMaxMb: 1024, threads: 32, gcPauseP95Ms: 9 },
    { service: 'identity-api', heapUsedMb: 180, heapMaxMb: 512, threads: 24, gcPauseP95Ms: 7 },
    { service: 'rag-ingestion', heapUsedMb: 220, heapMaxMb: 1024, threads: 36, gcPauseP95Ms: 10 },
  ],
  httpRate: [
    { service: 'gateway', rps: 2.4, errorRate: 0.0 },
    { service: 'rag-chat-api', rps: 0.8, errorRate: 0.0 },
    { service: 'docs-api', rps: 1.6, errorRate: 0.01 },
  ],
};

/**
 * "spark-inference-gateway degraded" — Frame 2. Same shape, spark cell + spark
 * latency value flipped to degraded values per design context §2.2.
 */
export const dashboardFixtureSparkDegraded: DashboardResponse = {
  ...dashboardFixtureLoaded,
  services: dashboardFixtureLoaded.services.map((s) =>
    s.name === 'spark-inference-gateway'
      ? { ...s, status: 'degraded' as const, latencyP95Ms: 3400, note: 'p95 3.4 s' }
      : s,
  ),
  sparkGateway: {
    ...dashboardFixtureLoaded.sparkGateway,
    status: 'degraded',
    latencyP95Ms: 3400,
  },
};

/**
 * Synthetic timeseries — a 60-point sample for a 1h range with 1m step.
 * Used by the smoke-render path when no live BC is available.
 */
export function buildTimeseriesFixture(
  metric: string,
  unit = 'MB',
  baseline = 400,
  jitter = 60,
): TimeseriesResponse {
  const now = Math.floor(Date.now() / 1000);
  const points: Array<[number, number]> = [];
  for (let i = 59; i >= 0; i--) {
    const t = now - i * 60;
    // Deterministic jitter so snapshot tests stay stable across runs.
    const wave = Math.sin(i * 0.4) * jitter * 0.4 + (i % 7) * jitter * 0.08;
    const v = Math.max(0, baseline + wave);
    points.push([t, Number(v.toFixed(2))]);
  }
  return {
    metric,
    range: '1h',
    step: '60s',
    unit,
    series: [{ label: 'value', points }],
  };
}
