'use client';

import type { ReactNode } from 'react';
import { AlertTriangle, RotateCcw } from 'lucide-react';
import { cn } from '@/shared/lib/cn';

/**
 * WidgetDegradeOverlay — the per-widget failure surface described in
 * design context §4.4 + Frame 4.
 *
 * Used by single-chart widgets (JVM heap, HTTP rate, spark latency)
 * when their individual `/api/metrics/timeseries` call returns 5xx or
 * the dashboard payload tagged the widget `"degraded": true`.
 *
 * Visual contract:
 *  - Card bg → `danger.soft`; border → `danger`.
 *  - The card's title/eyebrow stay legible (operator must still know
 *    which widget broke).
 *  - The body slot is replaced by the overlay: `⚠ Failed to refresh`
 *    in `danger` 12/600, then `↻ Retry` in `text.muted` 12/500 below.
 *  - The big-number value is rendered with `valueColor="danger"` by
 *    the parent (this component owns only the overlay region — the
 *    card chrome lives in each individual widget).
 *
 * Behavior:
 *  - `onRetry` calls only the affected widget's per-chart fetch
 *    (per design context §4.4 — NOT a full `/dashboard` refetch).
 *  - The next auto-poll tick also retries automatically.
 */

export interface WidgetDegradeOverlayProps {
  onRetry: () => void;
  /**
   * Optional title-row override. Most callers leave this null and let
   * the parent widget render its own title above the overlay; passing
   * a value here lets reusable card layouts inject the title text into
   * a "card carries title + overlay-only body" shape if needed.
   */
  title?: ReactNode;
  /** Tunable copy in case a specific failure mode wants a different line. */
  message?: string;
  /** Display variant — `full` fills the body; `compact` is for sparkline-sized cards. */
  size?: 'full' | 'compact';
}

export function WidgetDegradeOverlay({
  onRetry,
  title,
  message = 'Failed to refresh',
  size = 'full',
}: WidgetDegradeOverlayProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-start gap-sm',
        size === 'full' ? 'py-md' : 'py-xs',
      )}
      role="alert"
    >
      {title ? (
        <div className="text-[12px] font-semibold text-text">{title}</div>
      ) : null}
      <div className="flex items-center gap-xs">
        <AlertTriangle size={12} aria-hidden="true" className="text-danger" />
        <span className="text-[12px] font-semibold leading-none text-danger">{message}</span>
      </div>
      <button
        type="button"
        onClick={onRetry}
        className="inline-flex items-center gap-xs rounded-sm text-[12px] font-medium text-text-muted transition-colors duration-[120ms] hover:text-text focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
      >
        <RotateCcw size={11} aria-hidden="true" />
        <span>Retry</span>
      </button>
    </div>
  );
}

/**
 * Classnames helper for a card that wraps a degraded body. Keep
 * the radius / padding identical to the non-degraded state so the
 * overlay only flips colors, not geometry (per design context §1.5
 * "overlay does NOT pop over neighbouring widgets").
 */
export function degradedCardClasses(): string {
  return 'rounded-md border border-danger bg-danger-soft';
}

export function normalCardClasses(): string {
  return 'rounded-md border border-border bg-surface';
}
