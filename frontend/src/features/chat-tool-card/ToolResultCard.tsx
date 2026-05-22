'use client';

import type { ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

/**
 * Generic `tool_result` card — design-system primitive (§2.2 / frame
 * `78:1329`).
 *
 * The card is the slot-based shell every tool BC reuses (M8 fills it
 * with `📁 generate_massing`, future M9+ `slide-gen` / `image-gen`
 * fill it with their own glyph + action). Slot anatomy:
 *
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │ [icon] [name]                                  [primary action] │
 *   │        [summary]                                                │
 *   │                                                                 │
 *   │ [optional accordion / code line — bottom-left]                  │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 * Variants:
 *   - `default` — surface bg + border + accent action button (happy path)
 *   - `warning` — warning.soft bg + warning border + secondary action
 *     button (error path; M8 uses this for `tool_error` cards)
 *
 * Layout deltas vs the design doc:
 *   - Per-frame width is fixed at `820px` to match the chat content
 *     column (the parent `.flex-col.gap-lg` in `ChatPage` constrains
 *     `max-w-[820px]`). The card's intrinsic max-width takes that as
 *     a ceiling.
 *   - Height is intrinsic — 120px collapsed (icon row + summary row +
 *     accordion line) and grows naturally when the accordion expands.
 *     The composer is viewport-locked separately so the growth scrolls
 *     the conversation up; nothing here pins height.
 *
 * Motion (from the frontend-design skill's "feel" layer — design tokens
 * pin color/spacing/radius, this layer pins rhythm):
 *   - Hover: shadow.card → shadow.pop, 220ms cubic-bezier(.4,0,.2,1).
 *     The lift is barely perceptible — it signals "this is a live
 *     surface" without competing with the surrounding chat.
 *   - First mount: 180ms fade-in via `animate-tool-card-in` (defined in
 *     `tailwind.config.ts`'s keyframes). Avoids the jarring snap when
 *     a tool_result event lands mid-stream.
 */

export interface ToolResultCardProps {
  variant?: 'default' | 'warning';
  /** Slot ① — leading glyph (emoji or icon). 22px. */
  icon: ReactNode;
  /** Slot ② — tool display name. */
  name: ReactNode;
  /** Slot ③ — one-line summary OR (for in-flight state) `Running…`. */
  summary: ReactNode;
  /**
   * Slot ④ — primary action button anchored top-right. The button is
   * supplied as a ReactNode so the tool BC can pick `<a href download>`
   * for file outputs (M8) or `<button onClick>` for non-file outputs.
   * Pass `null` to omit the slot (in-flight skeleton state).
   */
  primaryAction?: ReactNode;
  /**
   * Slot ⑤ — content at the card bottom-left. For the default variant
   * this is typically an `<details>`-style accordion (M8: `▸ Program
   * details`). For the warning variant it's a single
   * `code: <CODE> · <elapsed>s` telemetry line. Both shapes layout the
   * same — `font-small` 12px, `text-muted`, 20px below the summary row.
   */
  footer?: ReactNode;
  /** Bottom-block content that's revealed inside the same card frame —
   *  for example the `▾ Program details` table when the accordion is
   *  open. Rendered with a top divider; absent when no expansion. */
  expanded?: ReactNode;
  /** Accessibility — short label for the whole card region. */
  ariaLabel?: string;
  className?: string;
}

export function ToolResultCard({
  variant = 'default',
  icon,
  name,
  summary,
  primaryAction,
  footer,
  expanded,
  ariaLabel,
  className,
}: ToolResultCardProps) {
  const isWarning = variant === 'warning';
  return (
    <section
      role="group"
      aria-label={ariaLabel}
      className={cn(
        'group/card relative flex w-full max-w-[820px] flex-col rounded-md border shadow-card',
        'animate-tool-card-in transition-shadow duration-200 ease-out hover:shadow-pop',
        isWarning
          ? 'border-warning bg-warning-soft'
          : 'border-border bg-surface',
        className,
      )}
      data-tool-card-variant={variant}
    >
      <header className="flex items-start justify-between gap-md px-md pt-md">
        <div className="flex min-w-0 flex-1 items-start gap-sm">
          <span
            aria-hidden="true"
            className={cn(
              // 22px circle landing zone for the glyph; emoji renders at
              // ~18px so we wrap it in a 24px slot for visual centering.
              'inline-flex h-[24px] w-[24px] shrink-0 items-center justify-center text-[20px] leading-none',
              isWarning ? 'text-warning' : 'text-text',
            )}
          >
            {icon}
          </span>
          <div className="flex min-w-0 flex-1 flex-col gap-[6px] pt-[1px]">
            <p
              className={cn(
                'truncate text-body font-semibold',
                isWarning ? 'text-warning' : 'text-text',
              )}
            >
              {name}
            </p>
            <div
              className={cn(
                'text-small leading-snug',
                isWarning ? 'font-medium text-text' : 'font-medium text-text',
              )}
            >
              {summary}
            </div>
          </div>
        </div>
        {primaryAction !== undefined && primaryAction !== null && (
          <div className="shrink-0 pt-[1px]">{primaryAction}</div>
        )}
      </header>

      {footer !== undefined && footer !== null && (
        <div className="flex items-center justify-between gap-sm px-md pb-md pt-sm">
          {footer}
        </div>
      )}

      {expanded !== undefined && expanded !== null && (
        <div className="border-t border-border px-md py-md">{expanded}</div>
      )}
    </section>
  );
}
