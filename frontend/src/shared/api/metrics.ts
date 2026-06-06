/**
 * Metrics BC API client (browser-safe).
 *
 * Same-origin relative paths through the gateway per ADR-07 +
 * ADR-15 §A. `/api/metrics/dashboard`, `/api/metrics/services`,
 * `/api/metrics/timeseries` are PUBLIC (no auth header required);
 * `/api/metrics/logs` is authenticated (gateway rejects 401 if
 * `X-User-Id` absent) — per ADR-09 amendment in ADR-15 §G.2.
 *
 * Each call accepts an optional `AbortSignal` so the polling hook
 * can cancel in-flight fetches on unmount or when the range changes.
 *
 * Wire shapes pinned in `docs/superpowers/specs/2026-05-19-m5-metrics-design.md` §5.
 * Field names are NOT renamed at the boundary so the wire-level JSON
 * pass-through stays obvious for diagnostics. The Spring Boot BC's
 * Java DTOs share these names verbatim.
 *
 * Per FSD layering (ADR-06 + `.eslintrc.json`): `shared` cannot import
 * from `entities`, so this module owns the wire DTO types. The
 * `entities/metrics` slice re-exports the types from here so the
 * widgets/features layers can pull them through the entity slice
 * without going through `shared/api/*` directly.
 */

// ============================================================
// DTOs — verbatim from spec §5 (`docs/superpowers/specs/2026-05-19-m5-metrics-design.md`).
// ============================================================

/**
 * Verdict per ADR-15 §9 truth table. Combines Prometheus `up{}` over
 * the last 2 scrape cycles with Spring Actuator `/actuator/health`;
 * for `spark-inference-gateway` it follows §12 (HEAD `/v1/models` probe).
 */
export type ServiceStatus = 'up' | 'degraded' | 'down';

/**
 * One row of the 11-cell service health grid (per ADR-15 §17 —
 * supersedes the spec §7.1 wireframe's 6-cell version).
 */
export interface ServiceHealth {
  /**
   * Canonical service slug. The 11 expected values are:
   *
   *   gateway / identity-api / docs-api / rag-ingestion / chat-api /
   *   metrics-api / spark-inference-gateway / prometheus-playground /
   *   loki-playground / alloy-playground / cadvisor-playground.
   */
  name: string;
  status: ServiceStatus;
  /** ISO-8601 timestamp when the service last became `up`. */
  since?: string;
  uptimeSec?: number;
  /** Docker image tag — e.g. `playground/gateway:0.4.1`. */
  image?: string;
  /** Spark only — current P95 over the last minute. */
  latencyP95Ms?: number;
  /** Free-form degradation reason, surfaced in the cell's detail line. */
  note?: string;
}

/** One container resource row (cAdvisor source). */
export interface ContainerSummary {
  name: string;
  cpuPct: number;
  memUsedMb: number;
  memLimitMb: number;
  restartCount: number;
}

/**
 * Host metrics (Alloy unix exporter source). `loadAvg` is a 3-tuple:
 * 1-minute / 5-minute / 15-minute averages.
 */
export interface HostSummary {
  cpuPct: number;
  memUsedGb: number;
  memTotalGb: number;
  diskUsedPct: number;
  diskUsedGb: number;
  diskTotalGb: number;
  loadAvg: [number, number, number];
}

/** spark-inference-gateway snapshot (ADR-15 §12). */
export interface SparkGatewaySummary {
  url: string;
  status: ServiceStatus;
  latencyP95Ms: number;
  modelsLoaded: string[];
}

/**
 * JVM heap snapshot per Spring Boot BC. The 4 rows mirror the 4
 * Spring Boot services scraped (NOT gateway or metrics-api itself).
 */
export interface JvmSummary {
  service: string;
  heapUsedMb: number;
  heapMaxMb: number;
  threads: number;
  gcPauseP95Ms: number;
}

/**
 * HTTP request rate snapshot per request-heavy BC (gateway,
 * chat-api, docs-api — 3 rows). `rag-ingestion` is Kafka-driven.
 */
export interface HttpRateSummary {
  service: string;
  rps: number;
  /** 0.0 .. 1.0 — fraction of 5xx over the last minute. */
  errorRate: number;
}

/** Composite payload returned by `GET /api/metrics/dashboard`. */
export interface DashboardResponse {
  fetchedAt: string;
  range: RangePreset;
  services: ServiceHealth[];
  containers: ContainerSummary[];
  host: HostSummary;
  sparkGateway: SparkGatewaySummary;
  jvm: JvmSummary[];
  httpRate: HttpRateSummary[];
  /** Optional — set by the BC when the PromQL budget was exceeded. */
  degraded?: boolean;
}

/** One series in a timeseries response — `[unixSec, value]` tuples. */
export interface TimeseriesSeries {
  label: string;
  points: Array<[number, number]>;
}

/** Type-level marker for a `[unixSec, value]` pair on the wire. */
export type TimeseriesPoint = [number, number];

/** Response payload of `GET /api/metrics/timeseries`. */
export interface TimeseriesResponse {
  /** Whitelisted metric id from PromQL mapping (spec §6 / ADR-15 §10). */
  metric: string;
  range: RangePreset;
  /** Per-bucket step, e.g. `30s`, `1m`, `5m`. */
  step: string;
  series: TimeseriesSeries[];
  /** Per spec §5.3: `MB`, `req/s`, `%`, `ms`, etc. */
  unit: string;
}

/** Log levels surfaced via the BC. */
export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL';

export interface LogEntry {
  /** ISO-8601 with millisecond precision per spec §5.4. */
  ts: string;
  service: string;
  level: LogLevel;
  message: string;
}

export interface LogsResponse {
  entries: LogEntry[];
  hasMore: boolean;
  /** Opaque cursor for pagination. */
  nextCursor: string | null;
}

// ============================================================
// Range preset (URL-shareable param).
// ============================================================

/**
 * 5 range presets pinned in spec §5.1 + §7.2. Default `1h` when the
 * URL param is absent.
 */
export type RangePreset = '15m' | '1h' | '6h' | '24h' | '7d';

export const RANGE_PRESETS: ReadonlyArray<RangePreset> = ['15m', '1h', '6h', '24h', '7d'];

export const DEFAULT_RANGE: RangePreset = '1h';

export function isRangePreset(value: string | null | undefined): value is RangePreset {
  return value !== null && value !== undefined && (RANGE_PRESETS as ReadonlyArray<string>).includes(value);
}

// ============================================================
// Result discriminated union.
// ============================================================

export type MetricsResult<T> =
  | { kind: 'ok'; value: T }
  | { kind: 'unauthorized' }
  | { kind: 'not-found' }
  | { kind: 'rate-limited'; retryAfter?: number }
  | { kind: 'service-unavailable' }
  | { kind: 'aborted' }
  | { kind: 'error'; status: number; message?: string };

async function parseMetricsResult<T>(res: Response): Promise<MetricsResult<T>> {
  if (res.status === 401) return { kind: 'unauthorized' };
  if (res.status === 404) return { kind: 'not-found' };
  if (res.status === 429) {
    const ra = res.headers.get('Retry-After');
    const retryAfter = ra ? Number(ra) : undefined;
    return {
      kind: 'rate-limited',
      retryAfter: Number.isFinite(retryAfter) ? retryAfter : undefined,
    };
  }
  if (res.status === 503) return { kind: 'service-unavailable' };
  if (res.ok) {
    if (res.status === 204) return { kind: 'ok', value: undefined as unknown as T };
    const value = (await res.json()) as T;
    return { kind: 'ok', value };
  }
  let message: string | undefined;
  try {
    const body = (await res.json()) as { message?: string };
    message = body.message;
  } catch {
    // ignore — server didn't emit a JSON error body
  }
  return { kind: 'error', status: res.status, message };
}

function isAbortError(error: unknown): boolean {
  return (
    error instanceof DOMException && error.name === 'AbortError'
  ) || (error as { name?: string } | null)?.name === 'AbortError';
}

// ============================================================
// HTTP calls.
// ============================================================

/**
 * `GET /api/metrics/dashboard?range=Xh` — the composite payload.
 *
 * Public (anon-OK). Rate-limited 30/min/IP (M5 s2 lands the limiter
 * + the matching 429 banner UX; s1 surfaces `rate-limited` so the
 * eventual banner has a contract).
 */
export async function fetchDashboard(
  range: RangePreset,
  signal?: AbortSignal,
): Promise<MetricsResult<DashboardResponse>> {
  try {
    const res = await fetch(
      `/api/metrics/dashboard?range=${encodeURIComponent(range)}`,
      { method: 'GET', credentials: 'same-origin', signal, headers: { accept: 'application/json' } },
    );
    return parseMetricsResult<DashboardResponse>(res);
  } catch (err) {
    if (isAbortError(err)) return { kind: 'aborted' };
    return { kind: 'error', status: 0, message: (err as Error).message };
  }
}

/**
 * `GET /api/metrics/services` — health grid only, cached aggressively
 * by the BC. Public. Not used in M5 s1 (the dashboard payload already
 * carries the services array) but ships for parity with the spec.
 */
export async function fetchServices(
  signal?: AbortSignal,
): Promise<MetricsResult<{ services: ServiceHealth[] }>> {
  try {
    const res = await fetch('/api/metrics/services', {
      method: 'GET',
      credentials: 'same-origin',
      signal,
      headers: { accept: 'application/json' },
    });
    return parseMetricsResult<{ services: ServiceHealth[] }>(res);
  } catch (err) {
    if (isAbortError(err)) return { kind: 'aborted' };
    return { kind: 'error', status: 0, message: (err as Error).message };
  }
}

/**
 * `GET /api/metrics/timeseries?metric=&range=&step=` — one chart's series.
 * Called in parallel by the `useTimeseries` hook so the dashboard
 * hydrates without a waterfall.
 */
export async function fetchTimeseries(
  metricId: string,
  range: RangePreset,
  options: { step?: string; signal?: AbortSignal } = {},
): Promise<MetricsResult<TimeseriesResponse>> {
  const params = new URLSearchParams({ metric: metricId, range });
  if (options.step) params.set('step', options.step);
  try {
    const res = await fetch(`/api/metrics/timeseries?${params.toString()}`, {
      method: 'GET',
      credentials: 'same-origin',
      signal: options.signal,
      headers: { accept: 'application/json' },
    });
    return parseMetricsResult<TimeseriesResponse>(res);
  } catch (err) {
    if (isAbortError(err)) return { kind: 'aborted' };
    return { kind: 'error', status: 0, message: (err as Error).message };
  }
}

/**
 * `GET /api/metrics/logs?service=&since=&search=&limit=` — authenticated
 * Loki query. M5 P0 ships no UI for this endpoint (operator-only via
 * `tools/metrics-logs.sh` per ADR-15 §19); the typed client lives here
 * so the M5.1 logs tab can pick it up.
 */
export interface LogsQuery {
  service: string;
  /** e.g. `15m`, `1h`, `24h`. */
  since: string;
  search?: string;
  limit?: number;
  level?: LogLevel;
  signal?: AbortSignal;
}

export async function fetchLogs(query: LogsQuery): Promise<MetricsResult<LogsResponse>> {
  const params = new URLSearchParams({ service: query.service, since: query.since });
  if (query.search) params.set('search', query.search);
  if (query.limit !== undefined) params.set('limit', String(query.limit));
  if (query.level) params.set('level', query.level);
  try {
    const res = await fetch(`/api/metrics/logs?${params.toString()}`, {
      method: 'GET',
      credentials: 'same-origin',
      signal: query.signal,
      headers: { accept: 'application/json' },
    });
    return parseMetricsResult<LogsResponse>(res);
  } catch (err) {
    if (isAbortError(err)) return { kind: 'aborted' };
    return { kind: 'error', status: 0, message: (err as Error).message };
  }
}
