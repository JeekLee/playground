import { cn } from '@/shared/lib/cn';

/**
 * Body-region placeholder rendered in place of the markdown reader while a
 * document is mid-extraction. Per M2-docs amendment 2026-05-22 §(2): the
 * standard /docs/{id} "Loading" state — 6 paragraph skeletons matching the
 * article block geometry. The intent is visual continuity ("this doc
 * exists, we're filling in the body") not a hard "Loading…" placeholder.
 *
 * The skeleton lines breathe with a `pulse` animation borrowed from the
 * existing chat-cursor keyframe (0.3 ↔ 1.0 opacity) — quieter than
 * Tailwind's default `animate-pulse` because the rest of this page already
 * has the Analyzing status pill doing the active-state heavy lifting.
 *
 * The widths are intentionally irregular (last line of each paragraph
 * shorter than the preceding ones) so the silhouette reads as prose, not
 * as a uniform progress fill.
 */

export interface ExtractionAnalyzingSkeletonProps {
  className?: string;
}

const PARAGRAPHS: ReadonlyArray<ReadonlyArray<string>> = [
  ['w-full', 'w-[96%]', 'w-[84%]'],
  ['w-[92%]', 'w-full', 'w-[70%]'],
  ['w-full', 'w-[88%]', 'w-[58%]'],
];

export function ExtractionAnalyzingSkeleton({ className }: ExtractionAnalyzingSkeletonProps) {
  return (
    <div
      role="presentation"
      aria-hidden="true"
      className={cn('flex flex-col gap-lg', className)}
    >
      {PARAGRAPHS.map((paragraph, pIdx) => (
        <div key={pIdx} className="flex flex-col gap-sm">
          {paragraph.map((width, lIdx) => (
            <span
              key={lIdx}
              className={cn(
                'block h-[14px] rounded-sm bg-surface-soft animate-chat-cursor',
                width,
              )}
              // Stagger the pulse so the silhouette ripples slightly down
              // the column instead of strobing in lockstep.
              style={{ animationDelay: `${(pIdx * 3 + lIdx) * 110}ms` }}
            />
          ))}
        </div>
      ))}
    </div>
  );
}
