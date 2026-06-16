'use client';

import { useState } from 'react';
import { LineChart, Line, ResponsiveContainer, YAxis } from 'recharts';
import { cn } from '@/shared/lib/cn';
import { displayName } from '@/shared/lib/serviceLabel';
import { color } from '@/shared/ui/tokens';
import { useTimeseries } from '@/features/metrics';
import type { HttpRateSummary, RangePreset } from '@/entities/metrics';
import { WidgetDegradeOverlay } from './WidgetDegradeOverlay';

/**
 * HttpRateChart — portrait-shaped card (175 × 236) showing the
 * `http-rate-<svc>` line plus a big req/s value and error-rate sub.
 *
 * Per design context §2.1 (HTTP request rate row):
 *  - Title `text` 12/600 → big rps `accent` 18/700 → small
 *    `error 0%` (or 1.0%) `text.muted` 11/400 → chart area 151 × 144
 *    `surface.soft` + 2px `accent` line.
 *
 * Active Spring Boot widgets per metrics API dashboard payload.
 */

export interface HttpRateChartProps {
  service: string;
  http: HttpRateSummary | null;
  range: RangePreset;
  pollKey: number | null;
}

export function HttpRateChart({ service, http, range, pollKey }: HttpRateChartProps) {
  const [retryNonce, setRetryNonce] = useState(0);
  const series = useTimeseries(`http-rate-${service}`, range, {
    pollKey,
    retryNonce,
    enabled: true,
  });
  const points = series.data?.series[0]?.points ?? [];
  const data = points.map(([t, v]) => ({ t, v }));

  const isDegraded = series.status === 'error';
  const rpsText = http ? `${http.rps.toFixed(1)} rps` : isDegraded ? '— rps' : '— rps';
  const errorText = http
    ? http.errorRate === 0
      ? 'error 0%'
      : `error ${(http.errorRate * 100).toFixed(1)}%`
    : 'error —';

  return (
    <div
      className={cn(
        'flex h-[236px] w-full flex-col gap-xs rounded-md border px-md py-md',
        isDegraded ? 'border-danger bg-danger-soft' : 'border-border bg-surface',
      )}
    >
      <span className="text-[12px] font-semibold text-text">{displayName(service)}</span>
      <span
        className={cn(
          'text-[18px] font-bold leading-none',
          isDegraded ? 'text-danger' : 'text-accent',
        )}
      >
        {rpsText}
      </span>
      <span
        className={cn(
          'text-[11px] font-normal',
          http && http.errorRate > 0.005 ? 'text-warning' : 'text-text-muted',
        )}
      >
        {errorText}
      </span>
      <div className="mt-auto h-[144px] w-full overflow-hidden rounded-sm bg-surface-soft">
        {isDegraded ? (
          <div className="flex h-full items-start px-[10px] py-[10px]">
            <WidgetDegradeOverlay onRetry={() => setRetryNonce((n) => n + 1)} />
          </div>
        ) : data.length > 0 ? (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 6, right: 4, bottom: 6, left: 4 }}>
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

// 백엔드의 `BuildDashboardUseCase.HTTP_SERVICES`와 동일 순서.
const HTTP_ORDER: ReadonlyArray<string> = [
  'playground-backend-gateway',
  'playground-backend-identity-api',
  'playground-backend-docs-api',
  'playground-backend-chat-api',
  'playground-backend-metrics-api',
];

export interface HttpRateRowProps {
  http: HttpRateSummary[] | null;
  range: RangePreset;
  pollKey: number | null;
}

/**
 * Note: `HttpRateRow` is not used directly — `/metrics` lays out HTTP
 * rate cards alongside the spark widgets in a mixed-width row per
 * design context §2.1 + §1.5 ("HTTP rate cards … mixed-width row
 * anchored to the bottom of the viewport"). Exported so it can stand
 * alone in narrow viewports / future M5.1 mobile reflow.
 */
export function HttpRateRow({ http, range, pollKey }: HttpRateRowProps) {
  const bySlug: Map<string, HttpRateSummary> = http
    ? new Map(http.map((h) => [h.service, h]))
    : new Map();

  return (
    <div className="grid grid-cols-1 gap-[12px] md:grid-cols-2 lg:grid-cols-4">
      {HTTP_ORDER.map((slug) => (
        <HttpRateChart
          key={slug}
          service={slug}
          http={bySlug.get(slug) ?? null}
          range={range}
          pollKey={pollKey}
        />
      ))}
    </div>
  );
}

/**
 * Exported helper for the page-level mixed row layout.
 */
export function HttpRateCells({ http, range, pollKey }: HttpRateRowProps) {
  const bySlug: Map<string, HttpRateSummary> = http
    ? new Map(http.map((h) => [h.service, h]))
    : new Map();
  return (
    <>
      {HTTP_ORDER.map((slug) => (
        <HttpRateChart
          key={slug}
          service={slug}
          http={bySlug.get(slug) ?? null}
          range={range}
          pollKey={pollKey}
        />
      ))}
    </>
  );
}
