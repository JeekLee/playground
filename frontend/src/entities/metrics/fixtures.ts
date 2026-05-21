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
    { name: 'playground-backend-gateway', status: 'up', since: '2026-05-19T04:10:02Z', uptimeSec: 12731, image: 'playground/backend-gateway:dev' },
    { name: 'playground-backend-identity-api', status: 'up', since: '2026-05-19T04:10:11Z', uptimeSec: 12722, image: 'playground/backend-identity-api:dev' },
    { name: 'playground-backend-docs-api', status: 'up', since: '2026-05-19T04:12:01Z', uptimeSec: 12612, image: 'playground/backend-docs-api:dev' },
    { name: 'playground-backend-rag-ingestion-api', status: 'up', since: '2026-05-19T04:12:30Z', uptimeSec: 12583, image: 'playground/backend-rag-ingestion-api:dev' },
    { name: 'playground-backend-rag-chat-api', status: 'up', since: '2026-05-19T05:28:14Z', uptimeSec: 8039, image: 'playground/backend-rag-chat-api:dev' },
    { name: 'playground-backend-metrics-api', status: 'up', since: '2026-05-19T06:40:00Z', uptimeSec: 3733, image: 'playground/backend-metrics-api:dev' },
    // Row 2 — spark + 4 observability self cells (ADR-15 §17 #7–#11).
    { name: 'spark-inference-gateway', status: 'up', latencyP95Ms: 340, note: '2 models' },
    { name: 'playground-prometheus', status: 'up', uptimeSec: 14000, note: '11 targets' },
    { name: 'playground-loki', status: 'up', uptimeSec: 14000, note: '3d retention' },
    { name: 'playground-alloy', status: 'up', uptimeSec: 14000, note: 'scraping 6 BCs' },
    { name: 'playground-cadvisor', status: 'up', uptimeSec: 14000, note: '15 containers' },
  ],
  containers: [
    { name: 'playground-postgres', cpuPct: 4.2, memUsedMb: 182, memLimitMb: 512, restartCount: 0 },
    { name: 'playground-redis', cpuPct: 0.8, memUsedMb: 42, memLimitMb: 256, restartCount: 0 },
    { name: 'playground-kafka-broker', cpuPct: 11.5, memUsedMb: 620, memLimitMb: 1024, restartCount: 0 },
    { name: 'playground-opensearch', cpuPct: 8.3, memUsedMb: 480, memLimitMb: 1024, restartCount: 0 },
    { name: 'playground-prometheus', cpuPct: 1.1, memUsedMb: 110, memLimitMb: 512, restartCount: 0 },
    { name: 'playground-loki', cpuPct: 0.9, memUsedMb: 95, memLimitMb: 512, restartCount: 0 },
    { name: 'playground-alloy', cpuPct: 2.4, memUsedMb: 86, memLimitMb: 256, restartCount: 0 },
    { name: 'playground-cadvisor', cpuPct: 1.8, memUsedMb: 64, memLimitMb: 256, restartCount: 0 },
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
    // 모델 이름은 backend가 `GET /v1/models`에서 가져온 그대로 (스왑 빈도가
    // 높아 frontend에서 고정하지 않음).
    modelsLoaded: ['bge-m3', 'qwen3-30b-a3b'],
  },
  jvm: [
    { service: 'playground-backend-rag-chat-api', heapUsedMb: 420, heapMaxMb: 1024, threads: 48, gcPauseP95Ms: 12 },
    { service: 'playground-backend-docs-api', heapUsedMb: 280, heapMaxMb: 1024, threads: 32, gcPauseP95Ms: 9 },
    { service: 'playground-backend-identity-api', heapUsedMb: 180, heapMaxMb: 512, threads: 24, gcPauseP95Ms: 7 },
    { service: 'playground-backend-rag-ingestion-api', heapUsedMb: 220, heapMaxMb: 1024, threads: 36, gcPauseP95Ms: 10 },
  ],
  httpRate: [
    { service: 'playground-backend-gateway', rps: 2.4, errorRate: 0.0 },
    { service: 'playground-backend-rag-chat-api', rps: 0.8, errorRate: 0.0 },
    { service: 'playground-backend-docs-api', rps: 1.6, errorRate: 0.01 },
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
  // spark-inference-gateway는 새 prefix가 적용된 도메인에서도 그대로
  // (별도 stack, ADR-04).
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
