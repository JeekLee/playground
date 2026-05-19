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
 *   becomes `warning` when degraded) → 2-line legend (`● BGE-M3` in
 *   `accent`, `● Qwen3-32B` in `khaki`) → chart area with two
 *   `<Line>`s. Per design context §4.6 + ADR-15 §8: type `monotone`,
 *   2px stroke, dot off.
 * - **Models loaded (175 × 236):** title → `✅ BGE-M3` / `✅ Qwen3-32B`
 *   rows → HOST eyebrow + URL → UPTIME eyebrow + value.
 */

const SERIES_LABEL_BGE = 'BGE-M3';
const SERIES_LABEL_QWEN = 'Qwen3-32B';

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
  const series = useTimeseries('spark-latency', range, {
    pollKey,
    retryNonce,
    enabled: true,
  });

  const bge = series.data?.series.find((s) => s.label === SERIES_LABEL_BGE)
    ?? series.data?.series[0];
  const qwen = series.data?.series.find((s) => s.label === SERIES_LABEL_QWEN)
    ?? series.data?.series[1];

  // Merge the two series into a single chart dataset (recharts
  // expects one record per x).
  const dataMap: Map<number, { t: number; bge?: number; qwen?: number }> = new Map();
  for (const [t, v] of bge?.points ?? []) {
    dataMap.set(t, { t, bge: v });
  }
  for (const [t, v] of qwen?.points ?? []) {
    const existing = dataMap.get(t);
    if (existing) {
      existing.qwen = v;
    } else {
      dataMap.set(t, { t, qwen: v });
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
        <LegendDot color={color.accent} label={SERIES_LABEL_BGE} />
        <LegendDot color={color.khaki} label={SERIES_LABEL_QWEN} />
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
                dataKey="bge"
                stroke={color.accent}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
                connectNulls
              />
              <Line
                type="monotone"
                dataKey="qwen"
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
