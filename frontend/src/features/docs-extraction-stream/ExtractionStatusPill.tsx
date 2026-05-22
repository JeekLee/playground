import { cn } from '@/shared/lib/cn';
import type { ExtractionStatus } from '@/entities/document';

/**
 * Slim status pill rendered between the topbar and the article block when a
 * document is mid-extraction. Per M2-docs amendment 2026-05-22 §(2):
 *   - In-flight: `surface.soft` bg + `accent` 1px border + `radius.pill`,
 *     padding `8px 16px`, label `⏳ Analyzing… <N> 페이지` in
 *     `font.small` 13/500/`accent`. The page count is informational — when
 *     SSE hasn't reported `pageTotal` yet, render `⏳ Analyzing…` with no
 *     number rather than a flickering placeholder.
 *   - Failed: identical geometry, swap `accent` → `danger`. Label
 *     `⚠ Analysis failed`. The reason itself surfaces below in the
 *     {@link ExtractionFailedCard}; this pill is a marker that something
 *     went wrong even when the user scrolls the body out of view.
 *
 * The pulsing dot prefix is intentional — a typewriter status line rather
 * than a progress bar. The extraction's progress lives in the optional
 * `pageDone / pageTotal` tail; the pill itself is a *fact*, not an
 * indeterminate spinner.
 */

export interface ExtractionStatusPillProps {
  status: Extract<ExtractionStatus, 'pending_extraction' | 'extracting' | 'failed'>;
  pageDone?: number | null;
  pageTotal?: number | null;
  className?: string;
}

export function ExtractionStatusPill({
  status,
  pageDone,
  pageTotal,
  className,
}: ExtractionStatusPillProps) {
  if (status === 'failed') {
    return (
      <div className={cn('flex w-full items-center justify-center px-[28px] py-[4px]', className)}>
        <span
          role="status"
          aria-live="polite"
          className={cn(
            'inline-flex items-center gap-sm rounded-pill border border-danger bg-danger-soft px-md py-[6px]',
            'text-[13px] font-medium leading-none text-danger',
          )}
        >
          <span aria-hidden="true">⚠</span>
          <span>Analysis failed</span>
        </span>
      </div>
    );
  }

  const showProgress = typeof pageTotal === 'number' && pageTotal > 0;
  const label = showProgress
    ? typeof pageDone === 'number' && pageDone > 0
      ? `Analyzing… ${pageDone} / ${pageTotal} pages`
      : `Analyzing… ${pageTotal} pages`
    : 'Analyzing…';

  return (
    <div className={cn('flex w-full items-center justify-center px-[28px] py-[4px]', className)}>
      <span
        role="status"
        aria-live="polite"
        className={cn(
          'inline-flex items-center gap-sm rounded-pill border border-accent bg-surface-soft px-md py-[6px]',
          'text-[13px] font-medium leading-none text-accent',
        )}
      >
        <PulsingDot />
        <span>{label}</span>
      </span>
    </div>
  );
}

/**
 * Single 6px dot fading 0.3 ↔ 1.0 — reuses the `chat-cursor` keyframe so we
 * don't multiply animation declarations across the design system. The
 * keyframe lives in `tailwind.config.ts` under
 * `theme.extend.keyframes['chat-cursor']`.
 */
function PulsingDot() {
  return (
    <span
      aria-hidden="true"
      className="inline-block h-[6px] w-[6px] rounded-pill bg-accent animate-chat-cursor"
    />
  );
}
