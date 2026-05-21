import { cn } from '@/shared/lib/cn';

/**
 * `(PDF)` source badge — M6 design context §4.4 + §2.6.
 *
 * A tiny inline pill rendered next to a document's title (in detail
 * pages, list rows, community cards, search hits) when the document was
 * uploaded as a `.pdf`. The literal label `(PDF)` — including the
 * parentheses — reads as document metadata rather than as a button
 * (deliberate choice per design doc §4.4 "Why not a separate icon?").
 *
 * Visual contract:
 *   - `accent.soft` bg, `accent` fg, weight 600, size 11
 *   - `radius.pill` (999px) — same family as the `Viewing publicly` chip
 *   - 14px horizontal padding → auto-fits to the 52–60px target width
 *     given the `(PDF)` label's measured advance in Inter 11/600
 *   - Vertical padding tuned so the rendered height is 20–22px
 *
 * All tokens flow through Tailwind's design-system mapping
 * (`bg-accent-soft`, `text-accent`, `rounded-pill`). Zero new tokens
 * introduced — the badge is composed entirely from the M2 vocabulary
 * (design context §5 "zero new tokens" rule).
 *
 * The badge intentionally does NOT reuse {@link Chip} because Chip's
 * `accent` variant ships at 9px horizontal padding + line-height `none`,
 * which renders too compressed for a metadata flag living inline with a
 * 28px H1. This component pins the M6 geometry directly so future Chip
 * variant changes don't drift the badge.
 *
 * Accessibility: the badge carries an `aria-label="Source file: PDF"` so
 * screen readers announce semantic meaning instead of the bare glyph
 * string. Visually `aria-hidden` is NOT applied — sighted users see the
 * label.
 */

export interface PdfBadgeProps {
  /**
   * Optional className for layout overrides (e.g., `align-middle` next to
   * a title's baseline). The component owns its own visual chrome — do
   * NOT pass background / color / radius overrides via this hook.
   */
  className?: string;
}

export function PdfBadge({ className }: PdfBadgeProps) {
  return (
    <span
      role="img"
      aria-label="Source file: PDF"
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center',
        'rounded-pill bg-accent-soft px-[14px] py-[3px]',
        'text-[11px] font-semibold leading-none tracking-[0.02em] text-accent',
        className,
      )}
    >
      (PDF)
    </span>
  );
}
