'use client';

import { useMemo } from 'react';
import { AlertTriangle } from 'lucide-react';
import { useSearchParams } from 'next/navigation';
import { cn } from '@/shared/lib/cn';
import {
  ContainerResourceTable,
  HostStatusRow,
  HttpRateCells,
  JvmHeapRow,
  RangePresetPills,
  ServiceHealthGrid,
  SparkGatewayPanel,
  UpdatedAgoIndicator,
  resolveRangeFromUrl,
} from '@/widgets/metrics';
import { useDashboardPoll } from '@/features/metrics';
import type { DashboardResponse } from '@/entities/metrics';

/**
 * MetricsDashboardPage — the `/metrics` viewport.
 *
 * Layout per design context M5-metrics.md §1.5:
 *  - Sticky header strip (range pills + Updated indicator) directly
 *    below the topbar — 72px tall, never scrolls.
 *  - Widget area: scrollable when the viewport is shorter than the
 *    composed widget stack; canonical 1440 × 900 frame fits without
 *    scroll.
 *
 * Failure surfaces:
 *  - 3+ consecutive `/api/metrics/dashboard` 5xx → whole-page banner
 *    (design context §2.3 + spec §7.3 "Stale (whole dashboard)").
 *  - 429 from the gateway (M5 s2 rate-limiter) → distinct banner copy.
 *  - 401 on a public endpoint shouldn't happen; if it does the user
 *    still sees the banner and the prior data stays visible.
 *
 * Skeleton (design context Frame 3):
 *  - Initial cold start (no data yet): all widgets render their own
 *    skeleton states based on `null` props; the indicator reads
 *    `Loading…`.
 *  - Subsequent refresh: previous data stays visible while the
 *    indicator shows `Refreshing…`.
 */
export function MetricsDashboardPage() {
  const searchParams = useSearchParams();
  // `URLSearchParams` instance is stable across re-renders by Next.js
  // for the same URL, so we can resolve the range directly here.
  const range = useMemo(() => resolveRangeFromUrl(searchParams), [searchParams]);

  const {
    data,
    status,
    updatedAt,
    isRefreshing,
    consecutiveFailures,
    refresh,
    lastResult,
  } = useDashboardPoll(range);

  // Per spec §7.3 row "Stale (whole dashboard)" — 3 consecutive failures
  // triggers the banner. We keep prior data visible underneath.
  const wholePageFailure = consecutiveFailures >= 3;
  const rateLimited = lastResult?.kind === 'rate-limited';

  return (
    <div className="flex h-[calc(100vh-56px)] flex-col">
      <StickyHeaderStrip
        range={range}
        updatedAt={updatedAt}
        isRefreshing={isRefreshing}
        onRefresh={refresh}
      />
      <div className="flex-1 overflow-y-auto">
        {wholePageFailure || rateLimited ? (
          <DashboardBanner
            kind={rateLimited ? 'rate-limited' : 'whole-failure'}
            retryAfter={rateLimited && lastResult?.kind === 'rate-limited' ? lastResult.retryAfter : undefined}
            onRetry={refresh}
          />
        ) : null}
        <div className="mx-auto flex max-w-[1208px] flex-col gap-lg px-lg py-md">
          <ServiceHealthGrid services={data?.services ?? null} />
          <HostStatusRow
            host={data?.host ?? null}
            range={range}
            pollKey={updatedAt}
          />
          <JvmHeapRow
            jvm={data?.jvm ?? null}
            range={range}
            pollKey={updatedAt}
          />
          <BottomMixedRow data={data} range={range} pollKey={updatedAt} />
          <ContainerResourceTable containers={data?.containers ?? null} />
        </div>
      </div>
      <ScreenReaderStatus status={status} consecutiveFailures={consecutiveFailures} />
    </div>
  );
}

function StickyHeaderStrip({
  range,
  updatedAt,
  isRefreshing,
  onRefresh,
}: {
  range: ReturnType<typeof resolveRangeFromUrl>;
  updatedAt: number | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}) {
  return (
    <div
      className="sticky top-0 z-10 flex h-[72px] items-center justify-between border-b border-border bg-bg px-lg"
      aria-label="Metrics range and refresh controls"
    >
      <RangePresetPills active={range} />
      <UpdatedAgoIndicator
        updatedAt={updatedAt}
        isRefreshing={isRefreshing}
        onRefresh={onRefresh}
      />
    </div>
  );
}

function BottomMixedRow({
  data,
  range,
  pollKey,
}: {
  data: DashboardResponse | null;
  range: ReturnType<typeof resolveRangeFromUrl>;
  pollKey: number | null;
}) {
  const sparkUptime = data?.services.find((s) => s.name === 'spark-inference-gateway')?.uptimeSec ?? null;
  return (
    <section
      aria-labelledby="metrics-traffic"
      className="flex flex-col gap-sm"
    >
      <h2 id="metrics-traffic" className="text-eyebrow text-text-muted">
        HTTP request rate · spark-inference-gateway
      </h2>
      {/* Per design context §2.1 — mixed-width row anchored to the
          bottom of the viewport. 3 HTTP rate cards (narrow) + Spark
          latency wide card + Spark models small card. We approximate
          with a CSS grid: 3 portrait cards + the panel on a 2/1 split. */}
      <div className="grid grid-cols-1 gap-[12px] xl:grid-cols-[repeat(3,175px)_minmax(0,1fr)]">
        <HttpRateCells
          http={data?.httpRate ?? null}
          range={range}
          pollKey={pollKey}
        />
        <SparkGatewayPanel
          spark={data?.sparkGateway ?? null}
          uptimeSec={sparkUptime}
          range={range}
          pollKey={pollKey}
        />
      </div>
    </section>
  );
}

function DashboardBanner({
  kind,
  retryAfter,
  onRetry,
}: {
  kind: 'whole-failure' | 'rate-limited';
  retryAfter?: number;
  onRetry: () => void;
}) {
  const isRateLimit = kind === 'rate-limited';
  return (
    <div
      role="alert"
      className={cn(
        'mx-auto mt-md flex max-w-[1208px] items-center gap-md rounded-md border px-lg py-md',
        isRateLimit
          ? 'border-warning bg-warning-soft text-text'
          : 'border-danger bg-danger-soft text-text',
      )}
    >
      <AlertTriangle
        size={16}
        aria-hidden="true"
        className={cn(isRateLimit ? 'text-warning' : 'text-danger')}
      />
      <div className="flex-1">
        <div className="text-[13px] font-semibold">
          {isRateLimit
            ? "You've hit the metrics rate limit."
            : "Couldn't reach metrics service."}
        </div>
        <div className="text-[12px] text-text-muted">
          {isRateLimit
            ? `Anonymous viewers are capped at 30 requests / minute / IP.${
                retryAfter ? ` Try again in ${retryAfter}s.` : ''
              }`
            : 'Retrying every 15s. Click below to retry now.'}
        </div>
      </div>
      <button
        type="button"
        onClick={onRetry}
        className={cn(
          'inline-flex h-[28px] items-center rounded-md border px-md text-[12px] font-medium transition-colors duration-[120ms]',
          isRateLimit
            ? 'border-warning bg-surface text-text hover:bg-warning-soft'
            : 'border-danger bg-surface text-danger hover:bg-danger-soft',
        )}
      >
        Retry now
      </button>
    </div>
  );
}

/**
 * Live-region status announcer for screen readers. The visual UI
 * already shows loading/refresh state; this surface makes the same
 * state available to assistive tech.
 */
function ScreenReaderStatus({
  status,
  consecutiveFailures,
}: {
  status: 'idle' | 'loading' | 'ready' | 'error';
  consecutiveFailures: number;
}) {
  let message = '';
  if (status === 'loading') {
    message = 'Loading metrics dashboard.';
  } else if (status === 'error') {
    message = 'Metrics dashboard failed to load. Retrying.';
  } else if (consecutiveFailures > 0) {
    message = `Metrics refresh failed ${consecutiveFailures} time${consecutiveFailures === 1 ? '' : 's'} in a row.`;
  } else if (status === 'ready') {
    message = 'Metrics dashboard updated.';
  }
  return (
    <div className="sr-only" aria-live="polite" aria-atomic="true">
      {message}
    </div>
  );
}
