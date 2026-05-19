'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchDashboard } from '@/shared/api/metrics';
import type {
  DashboardResponse,
  MetricsResult,
  RangePreset,
} from '@/entities/metrics';

/**
 * `useDashboardPoll` — drives the `/metrics` page's 15-second polling
 * cadence against `GET /api/metrics/dashboard`.
 *
 * Contract (per design context Frame 1 + spec §7.2):
 *  - Fetches immediately on mount and whenever `range` changes.
 *  - Sets a 15s `setInterval` for background refreshes.
 *  - Each fetch carries an `AbortSignal`; the prior request is aborted
 *    when a new one starts (range change OR manual refresh).
 *  - The interval and any in-flight fetch are cleaned up on unmount —
 *    no leaks.
 *
 * Loading state contract (per design context Frame 3):
 *  - `status === 'loading'` ONLY on the initial fetch (before any data
 *    has arrived). Subsequent refreshes leave the previous data visible
 *    and set `isRefreshing = true` so the topbar `Updated Ns ago`
 *    indicator can pause its tick + the `⟳` button can rotate.
 *
 * Error state contract (per design context Frame 4 + spec §7.3):
 *  - One 5xx → keep prior data visible; surface `status === 'error'`
 *    so the page can render the per-widget overlays. Whole-dashboard
 *    banner only after 3 consecutive failures (`consecutiveFailures`
 *    is exposed so the page owns that policy).
 *
 * Per spec §7.2: "Auto-refresh runs in background, 15s interval, no
 * pause/resume in P0." We honour that — no visibility-based throttling
 * beyond the browser's default `setInterval` clamping.
 */

const POLL_INTERVAL_MS = 15_000;

export type PollStatus = 'idle' | 'loading' | 'ready' | 'error';

export interface DashboardPollState {
  /** `loading` only on cold start; `ready` once any payload has arrived. */
  status: PollStatus;
  data: DashboardResponse | null;
  /** Snapshot of the last result. Useful for distinguishing 429 / 503. */
  lastResult: MetricsResult<DashboardResponse> | null;
  /** Timestamp (ms) of the last successful fetch. Drives the `Updated Ns ago`. */
  updatedAt: number | null;
  /** True while a refresh fetch is in flight (and we already have data). */
  isRefreshing: boolean;
  /** Streak of consecutive non-ok results since the last 200. */
  consecutiveFailures: number;
  /** Manually trigger a refetch + reset the 15s timer. */
  refresh: () => void;
}

export function useDashboardPoll(range: RangePreset): DashboardPollState {
  const [data, setData] = useState<DashboardResponse | null>(null);
  const [status, setStatus] = useState<PollStatus>('idle');
  const [lastResult, setLastResult] = useState<MetricsResult<DashboardResponse> | null>(null);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [consecutiveFailures, setConsecutiveFailures] = useState(0);

  // Refs — these need to survive across renders without re-creating the
  // effect. The poll interval ID + the latest abort controller live here.
  const abortRef = useRef<AbortController | null>(null);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Track whether we already have any data so the next fetch picks the
  // right loading vs refreshing branch even within the same effect.
  const hasDataRef = useRef(false);

  const runFetch = useCallback(
    async (currentRange: RangePreset) => {
      // Abort any in-flight call so we don't race the new one.
      if (abortRef.current) abortRef.current.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      if (hasDataRef.current) {
        setIsRefreshing(true);
      } else {
        setStatus('loading');
      }

      const result = await fetchDashboard(currentRange, controller.signal);

      // If a newer fetch already replaced us, drop this result on the
      // floor — the live state belongs to the more recent caller.
      if (abortRef.current !== controller) return;

      setLastResult(result);
      setIsRefreshing(false);

      if (result.kind === 'aborted') return;

      if (result.kind === 'ok') {
        setData(result.value);
        hasDataRef.current = true;
        setStatus('ready');
        setUpdatedAt(Date.now());
        setConsecutiveFailures(0);
      } else {
        setConsecutiveFailures((n) => n + 1);
        if (!hasDataRef.current) setStatus('error');
      }
    },
    [],
  );

  const refresh = useCallback(() => {
    // Reset the 15s timer when the user manually refreshes so the next
    // auto-tick lands a full interval away (per spec §7.2).
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = setInterval(() => {
        void runFetch(range);
      }, POLL_INTERVAL_MS);
    }
    void runFetch(range);
  }, [range, runFetch]);

  useEffect(() => {
    // New range → clear stale data so the chart axes don't show old extents.
    // Per spec §7.2 the existing data may briefly render against the new
    // range's axis label before the next 200 arrives; that's acceptable.
    void runFetch(range);

    intervalRef.current = setInterval(() => {
      void runFetch(range);
    }, POLL_INTERVAL_MS);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
      if (abortRef.current) {
        abortRef.current.abort();
        abortRef.current = null;
      }
    };
  }, [range, runFetch]);

  return {
    status,
    data,
    lastResult,
    updatedAt,
    isRefreshing,
    consecutiveFailures,
    refresh,
  };
}
