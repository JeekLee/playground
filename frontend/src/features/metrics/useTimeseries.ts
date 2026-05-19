'use client';

import { useEffect, useRef, useState } from 'react';
import { fetchTimeseries } from '@/shared/api/metrics';
import type {
  MetricsResult,
  RangePreset,
  TimeseriesResponse,
} from '@/entities/metrics';

/**
 * `useTimeseries` — per-chart fetcher. Each chart card mounts one of
 * these so the page hydrates in parallel (per ADR-15 §8 + spec §5.3:
 * "The frontend fetches each chart's series separately to parallelize").
 *
 * Refetch rules:
 *  - On mount.
 *  - When `range` changes (the `?range=Xh` URL param).
 *  - When `pollKey` changes — the page bumps this on every 15s
 *    dashboard tick (and on manual ⟳) so chart and dashboard refresh
 *    in lockstep without a second polling timer.
 *  - On `retryNonce` change — the per-widget Retry button bumps this.
 *
 * Per-widget degrade (per design context Frame 4 §4.4 + spec §7.3 row
 * "Stale (one widget)"):
 *  - Single timeseries 5xx → `status === 'error'`, prior data kept
 *    visible if any (the overlay covers the chart area).
 *  - `lastResult` carries the discriminated union so the page can
 *    distinguish `service-unavailable` from `error` if needed.
 *
 * Abort safety:
 *  - Each fetch carries an `AbortController`; the prior one is
 *    aborted when a new one starts.
 *  - The controller is aborted on unmount.
 */

export type TimeseriesStatus = 'loading' | 'ready' | 'error';

export interface TimeseriesState {
  status: TimeseriesStatus;
  data: TimeseriesResponse | null;
  lastResult: MetricsResult<TimeseriesResponse> | null;
}

export interface UseTimeseriesOptions {
  /**
   * The polling cadence shared with the dashboard. The page's
   * `useDashboardPoll` exposes `updatedAt` — pass that here so chart
   * and dashboard stay synchronized.
   */
  pollKey: number | null;
  /**
   * Bumped by the per-widget Retry button (per design context §4.4).
   * Resets the loading state and forces a refetch even if `range` and
   * `pollKey` are unchanged.
   */
  retryNonce?: number;
  /** Optional `step` parameter override. */
  step?: string;
  /**
   * When `false`, the hook stays idle. Used to skip fetches before the
   * dashboard payload arrives (no point hydrating charts before the
   * service grid says the BC is even reachable).
   */
  enabled?: boolean;
}

export function useTimeseries(
  metricId: string,
  range: RangePreset,
  options: UseTimeseriesOptions,
): TimeseriesState {
  const { pollKey, retryNonce = 0, step, enabled = true } = options;

  const [data, setData] = useState<TimeseriesResponse | null>(null);
  const [status, setStatus] = useState<TimeseriesStatus>('loading');
  const [lastResult, setLastResult] = useState<MetricsResult<TimeseriesResponse> | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const hasDataRef = useRef(false);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    if (abortRef.current) abortRef.current.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    // Only flip back to `loading` on cold start. Subsequent ticks keep
    // the prior data visible so the chart doesn't flash blank.
    if (!hasDataRef.current) {
      setStatus('loading');
    }

    let cancelled = false;
    (async () => {
      const result = await fetchTimeseries(metricId, range, {
        step,
        signal: controller.signal,
      });
      if (cancelled || abortRef.current !== controller) return;
      setLastResult(result);
      if (result.kind === 'aborted') return;
      if (result.kind === 'ok') {
        setData(result.value);
        hasDataRef.current = true;
        setStatus('ready');
      } else {
        setStatus('error');
      }
    })();

    return () => {
      cancelled = true;
      controller.abort();
      if (abortRef.current === controller) abortRef.current = null;
    };
  }, [metricId, range, pollKey, retryNonce, step, enabled]);

  return { status, data, lastResult };
}
