'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { useCallback } from 'react';
import { cn } from '@/shared/lib/cn';
import { RANGE_PRESETS, isRangePreset, type RangePreset } from '@/entities/metrics';

/**
 * RangePresetPills — sticky-strip preset selector.
 *
 * Per design context §4.2:
 *  - 5 pills: 15m / 1h / 6h / 24h / 7d (default active = 1h).
 *  - Inactive pill: `surface` bg + 1px `border` + `radius.pill`,
 *    label `text` 12/500.
 *  - Active pill: `accent.soft` bg, NO border, label `accent` 12/600.
 *  - Click → push the new `?range=Xh` URL param via shallow replace
 *    (no full navigation); parent's `useDashboardPoll` listens to the
 *    URL and refetches.
 *
 * URL-as-state contract:
 *  - `?range=Xh` is the source of truth. The parent reads it via
 *    `useSearchParams` and feeds the active value down.
 *  - We use `router.replace(...)` (not `push`) so the back button
 *    doesn't accumulate range flips.
 *  - `scroll: false` so the sticky strip + content don't jump.
 */

export interface RangePresetPillsProps {
  active: RangePreset;
  /** Left-of-the-pills label, design context §2.1 ("Range:"). */
  label?: string;
}

export function RangePresetPills({ active, label = 'Range' }: RangePresetPillsProps) {
  const router = useRouter();
  const searchParams = useSearchParams();

  const onSelect = useCallback(
    (value: RangePreset) => {
      if (value === active) return;
      const params = new URLSearchParams(searchParams?.toString() ?? '');
      params.set('range', value);
      // `replace` keeps the history clean — flipping ranges shouldn't
      // pollute the back stack (per spec §7.2 implicit UX).
      router.replace(`?${params.toString()}`, { scroll: false });
    },
    [active, router, searchParams],
  );

  return (
    <div className="flex items-center gap-sm" role="radiogroup" aria-label="Range">
      <span className="text-[12px] font-medium text-text-muted">{label}:</span>
      <div className="flex items-center gap-[6px]">
        {RANGE_PRESETS.map((value) => {
          const isActive = value === active;
          return (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={isActive}
              onClick={() => onSelect(value)}
              className={cn(
                'inline-flex h-[28px] items-center justify-center rounded-pill px-[12px] text-[12px] leading-none transition-colors duration-[120ms]',
                isActive
                  ? 'bg-accent-soft font-semibold text-accent'
                  : 'border border-border bg-surface font-medium text-text hover:border-border-strong',
              )}
            >
              {value}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Helper for `page.tsx` — reads the current range from URL, falling
 * back to `1h` per spec §7.2. Exported so the page and the pills
 * share the same parsing logic.
 */
export function resolveRangeFromUrl(searchParams: URLSearchParams | null): RangePreset {
  const raw = searchParams?.get('range');
  return isRangePreset(raw) ? raw : '1h';
}
