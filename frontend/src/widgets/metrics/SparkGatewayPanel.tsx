'use client';

import { useState } from 'react';
import { LineChart, Line, ResponsiveContainer, YAxis } from 'recharts';
import { CheckCircle2, AlertTriangle, OctagonAlert } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { color } from '@/shared/ui/tokens';
import { useTimeseries } from '@/features/metrics';
import type { RangePreset, ServiceStatus, SparkGatewaySummary } from '@/entities/metrics';
import { WidgetDegradeOverlay } from './WidgetDegradeOverlay';

/**
 * SparkGatewayPanel — two cards, side-by-side, anchored to the bottom of
 * the dashboard viewport per design context §1.5.
 *
 * - **Latency P95 (365 × 236):** title → big `340 ms` (`accent`,
 *   becomes `warning` when degraded) → 2-line legend → chart with one
 *   `<Line>` per endpoint URI. 2px stroke, type `monotone`, dot off
 *   (per design context §4.6 + ADR-15 §8).
 * - **Models loaded (175 × 236):** title → list of currently-loaded
 *   models (from `sparkGateway.modelsLoaded`, dynamic — backend pulls
 *   from `GET /v1/models`) → HOST + UPTIME footers.
 *
 * 2026-05-21 amendment: legend labels were hardcoded `BGE-M3`/`Qwen3-32B`
 * (design context mockup). Real backend emits series per `uri` label
 * (`/v1/embeddings`, `/v1/chat/completions`) — see `BuildTimeseriesUseCase.labelOf()`.
 * Now we identify lines by URI pattern and render friendly names without
 * locking to specific model identifiers (which churn — e.g., Qwen3-32B
 * → Qwen3-30B-A3B swap).
 */

/** Endpoint URI → friendly endpoint name + chart color. */
const ENDPOINT_PATTERNS: ReadonlyArray<{
  match: RegExp;
  label: string;
  series: 'a' | 'b';
}> = [
  { match: /embed/i, label: 'embedding', series: 'a' },
  { match: /chat|completion/i, label: 'chat', series: 'b' },
];

function endpointMeta(uriOrLabel: string): { label: string; series: 'a' | 'b' } {
  for (const pattern of ENDPOINT_PATTERNS) {
    if (pattern.match.test(uriOrLabel)) {
      return { label: pattern.label, series: pattern.series };
    }
  }
  // Fallback: unknown endpoint → show URI as-is on the "a" series.
  return { label: uriOrLabel, series: 'a' };
}

export interface SparkGatewayPanelProps {
  spark: SparkGatewaySummary | null;
  /** Uptime in seconds — pulled from the corresponding services row. */
  uptimeSec: number | null;
  range: RangePreset;
  pollKey: number | null;
}

export function SparkGatewayPanel({
  spark,
  uptimeSec,
  range,
  pollKey,
}: SparkGatewayPanelProps) {
  return (
    <div className="grid w-full grid-cols-1 gap-[12px] md:grid-cols-[2fr_1fr]">
      <SparkLatencyCard spark={spark} range={range} pollKey={pollKey} />
      <SparkModelsCard spark={spark} uptimeSec={uptimeSec} />
    </div>
  );
}

function SparkLatencyCard({
  spark,
  range,
  pollKey,
}: {
  spark: SparkGatewaySummary | null;
  range: RangePreset;
  pollKey: number | null;
}) {
  const [retryNonce, setRetryNonce] = useState(0);
  // metric id는 PromQlTemplate의 `spark-latency-p95` (full ADR-15 §10 id).
  // 이전엔 `spark-latency`로 호출해서 항상 400 Unknown metric id → series.error
  // → 카드가 항상 danger(빨강)로 떨어짐.
  const series = useTimeseries('spark-latency-p95', range, {
    pollKey,
    retryNonce,
    enabled: true,
  });

  // Identify the two series by their endpoint URI (backend emits
  // `by (le, uri)` so series.label is the URI value). Fall back to
  // positional order when label can't be classified.
  const allSeries = series.data?.series ?? [];
  let seriesA = allSeries.find((s) => endpointMeta(s.label).series === 'a');
  let seriesB = allSeries.find((s) => endpointMeta(s.label).series === 'b');
  if (!seriesA && !seriesB) {
    seriesA = allSeries[0];
    seriesB = allSeries[1];
  }

  const labelA = seriesA ? endpointMeta(seriesA.label).label : 'embedding';
  const labelB = seriesB ? endpointMeta(seriesB.label).label : 'chat';

  // Merge the two series into a single chart dataset (recharts
  // expects one record per x).
  const dataMap: Map<number, { t: number; a?: number; b?: number }> = new Map();
  for (const [t, v] of seriesA?.points ?? []) {
    dataMap.set(t, { t, a: v });
  }
  for (const [t, v] of seriesB?.points ?? []) {
    const existing = dataMap.get(t);
    if (existing) {
      existing.b = v;
    } else {
      dataMap.set(t, { t, b: v });
    }
  }
  const data = Array.from(dataMap.values()).sort((a, b) => a.t - b.t);

  const isDegradedWidget = series.status === 'error';
  const isSparkDegraded = spark?.status === 'degraded';
  const isSparkDown = spark?.status === 'down';
  // Value color tracks the spark health, not the widget fetch result —
  // if the BC has reached spark and reports `degraded`, the value
  // should be visible in warning yellow (Frame 2 pattern).
  const valueColorClass = isDegradedWidget
    ? 'text-danger'
    : isSparkDegraded
      ? 'text-warning'
      : isSparkDown
        ? 'text-danger'
        : 'text-accent';

  const valueText = spark
    ? `${spark.latencyP95Ms.toLocaleString()} ms`
    : isDegradedWidget
      ? '— ms'
      : '— ms';

  const cardClass = isDegradedWidget
    ? 'border-danger bg-danger-soft'
    : 'border-border bg-surface';

  return (
    <div className={cn('flex h-[236px] flex-col gap-xs rounded-md border px-md py-md', cardClass)}>
      <span className="text-[12px] font-semibold text-text">Latency P95 (ms)</span>
      <span className={cn('text-[22px] font-bold leading-none', valueColorClass)}>
        {valueText}
      </span>
      <div className="flex items-center gap-md text-[11px] font-medium text-text-muted">
        <LegendDot color={color.accent} label={labelA} />
        <LegendDot color={color.khaki} label={labelB} />
      </div>
      <div className="mt-auto h-[144px] w-full overflow-hidden rounded-sm bg-surface-soft">
        {isDegradedWidget ? (
          <div className="flex h-full items-start px-[10px] py-[10px]">
            <WidgetDegradeOverlay onRetry={() => setRetryNonce((n) => n + 1)} />
          </div>
        ) : data.length > 0 ? (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 6, right: 6, bottom: 6, left: 6 }}>
              <YAxis hide domain={['dataMin', 'dataMax']} />
              <Line
                type="monotone"
                dataKey="a"
                stroke={color.accent}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
                connectNulls
              />
              <Line
                type="monotone"
                dataKey="b"
                stroke={color.khaki}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
                connectNulls
              />
            </LineChart>
          </ResponsiveContainer>
        ) : null}
      </div>
    </div>
  );
}

function LegendDot({ color: dotColor, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-[6px]">
      <span
        aria-hidden="true"
        className="inline-block h-[8px] w-[8px] rounded-pill"
        style={{ backgroundColor: dotColor }}
      />
      <span>{label}</span>
    </span>
  );
}

function SparkModelsCard({
  spark,
  uptimeSec,
}: {
  spark: SparkGatewaySummary | null;
  uptimeSec: number | null;
}) {
  const status: ServiceStatus = spark?.status ?? 'up';
  return (
    <div className="flex h-[236px] flex-col gap-md rounded-md border border-border bg-surface px-md py-md">
      <span className="text-[12px] font-semibold text-text">Models loaded</span>
      <ul className="flex flex-col gap-xs text-[13px] font-medium text-text">
        {(spark?.modelsLoaded ?? ['—', '—']).map((model, i) => (
          <li
            key={`${model}-${i}`}
            className="inline-flex items-center gap-sm"
          >
            <ModelGlyph status={status} />
            <span className={cn(spark ? 'text-text' : 'text-text-subtle')}>
              {model}
            </span>
          </li>
        ))}
      </ul>
      <div className="mt-auto flex flex-col gap-[6px]">
        <span className="text-eyebrow text-text-muted">Host</span>
        <span className="text-[11px] text-text-muted">
          {spark?.url ?? 'loading…'}
        </span>
        <span className="text-eyebrow text-text-muted">Uptime</span>
        <span className={cn('text-[14px] font-semibold', spark ? 'text-text' : 'text-text-subtle')}>
          {uptimeSec !== null ? formatUptime(uptimeSec) : '—'}
        </span>
      </div>
    </div>
  );
}

function ModelGlyph({ status }: { status: ServiceStatus }) {
  if (status === 'up') {
    return <CheckCircle2 size={13} aria-hidden="true" className="text-success" />;
  }
  if (status === 'degraded') {
    return <AlertTriangle size={13} aria-hidden="true" className="text-warning" />;
  }
  return <OctagonAlert size={13} aria-hidden="true" className="text-danger" />;
}

function formatUptime(seconds: number): string {
  const sec = Math.max(0, Math.floor(seconds));
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m`;
  const hr = Math.floor(min / 60);
  const remMin = min % 60;
  if (hr < 24) return `${hr}h ${remMin.toString().padStart(2, '0')}m`;
  const days = Math.floor(hr / 24);
  const remHr = hr % 24;
  return `${days}d ${remHr}h`;
}
