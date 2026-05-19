'use client';

import { useState } from 'react';
import { LineChart, Line, ResponsiveContainer, YAxis } from 'recharts';
import { cn } from '@/shared/lib/cn';
import { color } from '@/shared/ui/tokens';
import { useTimeseries } from '@/features/metrics';
import type { JvmSummary, RangePreset } from '@/entities/metrics';
import { WidgetDegradeOverlay } from './WidgetDegradeOverlay';

/**
 * JvmHeapChart — one card per Spring Boot BC, rendering a compact line
 * chart of `jvm-heap-<svc>` over the selected range.
 *
 * Design context §2.1: 270 × 128 card, `surface` bg + `border` +
 * `radius.md`. Title (`text` 12/600) → value (`accent` 17/600) →
 * chart area 238 × 48 (`surface.soft` bg, `radius.sm`) with a 2px
 * `accent` line.
 *
 * Per-widget degrade (design context Frame 4 + §4.4): when this
 * widget's `/api/metrics/timeseries` call returns 5xx, the card flips
 * to `danger.soft` + `danger` border, the value text renders as
 * `— / 1024 MB` in `danger`, and the chart area is replaced by the
 * `WidgetDegradeOverlay` (`⚠ Failed to refresh` + `↻ Retry`).
 *
 * The `heapMaxMb` denominator comes from the dashboard payload (still
 * fresh during a single-widget failure per design context §2.4) so it
 * stays visible even when the timeseries fetch broke.
 */

export interface JvmHeapChartProps {
  service: string;
  jvm: JvmSummary | null;
  range: RangePreset;
  pollKey: number | null;
}

export function JvmHeapChart({ service, jvm, range, pollKey }: JvmHeapChartProps) {
  // The per-widget Retry button bumps this nonce, which the hook
  // listens to to force a refetch of just this chart.
  const [retryNonce, setRetryNonce] = useState(0);
  const series = useTimeseries(`jvm-heap-${service}`, range, {
    pollKey,
    retryNonce,
    // We can fetch the chart whenever we know the BC's slug, even if
    // the dashboard `jvm` row hasn't landed yet — the timeseries call
    // is independent of the dashboard payload.
    enabled: true,
  });

  // Spec §5.3 — series[0].label is `heapUsed`; series[1] (if present)
  // is `heapMax` (a constant line). For a compact chart we only render
  // the `heapUsed` line; the `heapMax` value comes from the dashboard
  // payload's `heapMaxMb`.
  const usedSeries = series.data?.series.find((s) => s.label === 'heapUsed')
    ?? series.data?.series[0];
  const points = usedSeries?.points ?? [];
  const data = points.map(([t, v]) => ({ t, v }));

  const isDegraded = series.status === 'error';
  const valueText = jvm
    ? `${jvm.heapUsedMb} / ${jvm.heapMaxMb} MB`
    : isDegraded
      ? '— / — MB'
      : '— MB';

  return (
    <div
      className={cn(
        'flex h-[128px] flex-col gap-[6px] rounded-md border px-md py-[10px]',
        isDegraded ? 'border-danger bg-danger-soft' : 'border-border bg-surface',
      )}
    >
      <div className="flex items-center justify-between">
        <span className="text-[12px] font-semibold text-text">{service}</span>
        <span className={cn(
          'text-[11px] font-normal text-text-muted',
          isDegraded && 'text-danger',
        )}>
          heap
        </span>
      </div>
      <span className={cn(
        'text-[17px] font-semibold leading-none',
        isDegraded ? 'text-danger' : 'text-accent',
      )}>
        {valueText}
      </span>
      <div className="mt-auto h-[48px] w-full overflow-hidden rounded-sm bg-surface-soft">
        {isDegraded ? (
          <div className="flex h-full items-start px-[8px] py-[6px]">
            <WidgetDegradeOverlay
              size="compact"
              onRetry={() => setRetryNonce((n) => n + 1)}
            />
          </div>
        ) : data.length > 0 ? (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart
              data={data}
              margin={{ top: 4, right: 4, bottom: 4, left: 4 }}
            >
              <YAxis hide domain={['dataMin', 'dataMax']} />
              <Line
                type="monotone"
                dataKey="v"
                stroke={color.accent}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        ) : null}
      </div>
    </div>
  );
}

/**
 * Composite that renders one JVM heap card per JVM-bearing service. The
 * service list comes from the dashboard payload's `jvm[]` (backend owns
 * the source of truth — design context M5-metrics.md §2.1). On lg the
 * row lays out as `grid-cols-3` (6 services pack into 3×2); on xl it
 * collapses to `grid-cols-6` for a single-row display when viewport
 * permits.
 */
export interface JvmHeapRowProps {
  jvm: JvmSummary[] | null;
  range: RangePreset;
  pollKey: number | null;
}

export function JvmHeapRow({ jvm, range, pollKey }: JvmHeapRowProps) {
  // Backend orders `jvm[]` deterministically (BuildDashboardUseCase.JVM_SERVICES).
  // Render in that order so card positions stay stable across polls.
  const services = jvm ?? [];

  return (
    <section aria-labelledby="metrics-jvm" className="flex flex-col gap-sm">
      <h2 id="metrics-jvm" className="text-eyebrow text-text-muted">
        JVM heap per BC
      </h2>
      <div className="grid grid-cols-1 gap-[12px] md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
        {services.map((entry) => (
          <JvmHeapChart
            key={entry.service}
            service={entry.service}
            jvm={entry}
            range={range}
            pollKey={pollKey}
          />
        ))}
      </div>
    </section>
  );
}
