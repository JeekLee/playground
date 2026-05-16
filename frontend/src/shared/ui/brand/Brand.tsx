import { cn } from '@/shared/lib/cn';

/**
 * Wordmark — design system §2.2.
 * Glyph `J` (26×26, accent fill, radius 7px, white text) +
 * stacked text:
 *   line 1 `JeekLee's` (13.5px / 700 / -0.01em)
 *   line 2 `playground` (10px / 500 / +0.04em / uppercase, text.muted)
 *
 * `compact` renders glyph-only for tight contexts (favicon analog;
 * unused in M1 screens but available for later milestones).
 */

export interface BrandProps {
  compact?: boolean;
  className?: string;
}

export function Brand({ compact = false, className }: BrandProps) {
  return (
    <div className={cn('flex items-center gap-sm', className)}>
      <div
        className="flex h-[26px] w-[26px] items-center justify-center rounded-[7px] bg-accent text-surface"
        aria-hidden="true"
      >
        <span className="font-sans text-[16px] font-bold leading-none">J</span>
      </div>
      {!compact && (
        <div className="flex flex-col leading-tight">
          <span className="text-[13.5px] font-bold tracking-[-0.01em] text-text">
            JeekLee&rsquo;s
          </span>
          <span className="text-[10px] font-medium uppercase tracking-[0.04em] text-text-muted">
            playground
          </span>
        </div>
      )}
    </div>
  );
}
