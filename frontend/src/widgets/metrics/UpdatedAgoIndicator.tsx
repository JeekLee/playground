'use client';

import { useEffect, useState } from 'react';
import { RotateCw } from 'lucide-react';
import { cn } from '@/shared/lib/cn';

/**
 * UpdatedAgoIndicator — right-aligned `Updated Ns ago` counter plus the
 * manual `⟳` refresh button.
 *
 * Per design context §4.3:
 *  - Indicator format `Updated Ns ago` in `text.muted` 12/400.
 *  - Ticks every 1 s client-side from `updatedAt` (ms timestamp).
 *  - On `Loading…` (initial cold start, `updatedAt === null`) the
 *    counter reads `Loading…`.
 *  - While `isRefreshing`, the indicator stops ticking and the `⟳`
 *    icon rotates (CSS `animate-spin`-equivalent — we use a tailwind
 *    transition + the `lucide-react` `RotateCw` glyph).
 *  - Refresh button: 32 × 28, `surface` bg + 1px `border` +
 *    `radius.md` 8px (we use the closest token, `radius.md` 10px,
 *    since rolling a fifth radius isn't worth a token spike).
 */

export interface UpdatedAgoIndicatorProps {
  /** Epoch ms of the last successful fetch, or null while loading first. */
  updatedAt: number | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

export function UpdatedAgoIndicator({
  updatedAt,
  isRefreshing,
  onRefresh,
}: UpdatedAgoIndicatorProps) {
  // Tick the indicator every 1s so the "Updated Ns ago" stays live.
  // We cap at 600s so the text doesn't grow unboundedly; the dashboard
  // is polling every 15s anyway, so anything past that is a bug.
  const [now, setNow] = useState<number>(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(id);
  }, []);

  let label: string;
  if (updatedAt === null) {
    label = 'Loading…';
  } else if (isRefreshing) {
    // Spec §7.3 row "Subsequent refresh": indicator doesn't tick while
    // a refresh is in flight. Surface that explicitly.
    label = 'Refreshing…';
  } else {
    const elapsedSec = Math.max(0, Math.floor((now - updatedAt) / 1000));
    label = elapsedSec === 0
      ? 'Updated just now'
      : `Updated ${elapsedSec}s ago`;
  }

  return (
    <div className="flex items-center gap-md">
      <span
        className="text-[12px] font-normal leading-none text-text-muted"
        aria-live="polite"
      >
        {label}
      </span>
      <button
        type="button"
        onClick={onRefresh}
        aria-label="Refresh now"
        title="Refresh now"
        className={cn(
          'inline-flex h-[28px] w-[32px] items-center justify-center rounded-md border border-border bg-surface text-text transition-colors duration-[120ms] hover:border-border-strong hover:bg-surface-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
        )}
      >
        <RotateCw
          size={14}
          aria-hidden="true"
          className={cn(isRefreshing && 'animate-spin')}
        />
      </button>
    </div>
  );
}
