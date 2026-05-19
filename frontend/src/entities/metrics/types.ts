/**
 * Metrics BC — entity layer.
 *
 * The wire DTO types live in `shared/api/metrics.ts` because the FSD
 * boundary rules in `.eslintrc.json` forbid `shared` from importing
 * `entities` (which would otherwise be a layering inversion). The
 * entity slice re-exports the types so widgets/features import them
 * through the proper FSD doorway:
 *
 *   widgets / features  →  entities  →  shared
 *
 * Field names mirror `docs/superpowers/specs/2026-05-19-m5-metrics-design.md`
 * §5 verbatim — see `shared/api/metrics.ts` for the inline annotations.
 *
 * Backend authority: ADR-15 (`docs/adr/15-m5-metrics.md`) — §9
 * (service health truth table), §12 (spark probe verdict), §17
 * (11-cell grid: 6 BCs + spark + 4 observability self-cells).
 */

export type {
  ContainerSummary,
  DashboardResponse,
  HostSummary,
  HttpRateSummary,
  JvmSummary,
  LogEntry,
  LogLevel,
  LogsResponse,
  MetricsResult,
  RangePreset,
  ServiceHealth,
  ServiceStatus,
  SparkGatewaySummary,
  TimeseriesPoint,
  TimeseriesResponse,
  TimeseriesSeries,
} from '@/shared/api/metrics';

export { DEFAULT_RANGE, RANGE_PRESETS, isRangePreset } from '@/shared/api/metrics';
