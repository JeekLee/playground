import Link from 'next/link';
import { Eye, Heart } from 'lucide-react';
import { Avatar } from '@/shared/ui/avatar';
import { Chip } from '@/shared/ui/chip';
import { cn } from '@/shared/lib/cn';
import {
  authorInitials,
  formatDate,
  type DocumentListItem,
} from '@/entities/document';

/**
 * CommunityDocCard — the 3-column thumbnail card used on `/docs`
 * (community feed) and `/` (Latest published docs / Latest documents).
 *
 * Composition pinned by design doc M2-docs.md §"Documents (/docs)":
 *  - 124px gradient thumbnail with one of three sand variants, alternating
 *    by index so a row never reads as monochrome
 *  - h3 title, 2-line excerpt in `font.small text.muted`
 *  - meta row: tag chip · estimated read time · published date · view ·
 *    like count (counters render as `0` in S2 — they wire in S3)
 *  - author row: 16px khaki avatar + display name in `font.small`/500
 *    `text.muted`, separated from meta by a 1px `border` divider so the
 *    multi-author shift reads at a glance
 *
 * Hover treatment per design system §6.4 `interactive` card variant:
 *  -2px translate + accent border + `shadow.pop`.
 */

const THUMB_VARIANTS = [
  // khaki — diagonal cream-to-khaki gradient evokes paper edge
  'from-surface-soft via-surface-soft to-khaki',
  // sand — the surface.soft on its own with a subtler accent fade
  'from-surface-soft via-bg to-surface-soft',
  // sage — the success.soft variant for visual rhythm
  'from-success-soft via-surface-soft to-success-soft',
] as const;

const TAG_HINTS = [
  'agents',
  'spark',
  'design',
  'infra',
  'search',
  'rag',
  'platform',
  'notes',
] as const;

export interface CommunityDocCardProps {
  doc: DocumentListItem;
  /** Index in the parent grid; drives the thumbnail variant. */
  index: number;
  className?: string;
  /**
   * Append `?as=reader` to the doc link. Used on the Home's owner-
   * curated section so the signed-in owner clicking their own card
   * lands on the public reader preview, not their editor surface.
   * Defaults to false — community feed (/docs) keeps the standard
   * link behavior (owner clicks own card → editor; non-owners →
   * reader, gated by docs-api per spec §6.1).
   */
  forceReader?: boolean;
}

export function CommunityDocCard({
  doc,
  index,
  className,
  forceReader = false,
}: CommunityDocCardProps) {
  const variant = THUMB_VARIANTS[index % THUMB_VARIANTS.length] ?? THUMB_VARIANTS[0];
  // Tag chips are display-only in M2 per design doc "Out of scope" — we
  // derive a visual hint from the document's path/folder so cards don't
  // look identical, but no value persists or filters anything.
  const tagHint = deriveTagHint(doc, index);
  const readMinutes = estimateReadMinutes(doc.excerpt);
  const href = forceReader ? `/docs/${doc.id}?as=reader` : `/docs/${doc.id}`;

  return (
    <Link
      href={href}
      className={cn(
        'group flex flex-col overflow-hidden rounded-md border border-border bg-surface shadow-card transition-all duration-[140ms] ease-out',
        'hover:-translate-y-[2px] hover:border-accent hover:shadow-pop',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
        className,
      )}
      aria-label={`${doc.title} by ${doc.author.displayName}`}
    >
      <div
        className={cn(
          'relative h-[124px] w-full bg-gradient-to-br',
          variant,
        )}
        aria-hidden="true"
      >
        {/* Subtle grain dot to keep the thumbnail from reading as flat. */}
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_20%,rgba(110,122,58,0.08),transparent_60%)]" />
      </div>
      <div className="flex flex-1 flex-col gap-sm p-md">
        <div className="flex flex-1 flex-col gap-xs">
          <h3 className="line-clamp-2 text-h3 text-text group-hover:text-accent">
            {doc.title}
          </h3>
          <p className="line-clamp-2 text-small text-text-muted">{doc.excerpt}</p>
        </div>
        <div className="flex flex-wrap items-center gap-x-sm gap-y-xs text-small text-text-muted">
          <Chip variant="accent">{tagHint}</Chip>
          <span aria-hidden="true">·</span>
          <span>{readMinutes} min</span>
          {doc.publishedAt && (
            <>
              <span aria-hidden="true">·</span>
              <span>{formatDate(doc.publishedAt)}</span>
            </>
          )}
          <span aria-hidden="true">·</span>
          <span className="inline-flex items-center gap-xs">
            <Eye size={12} aria-hidden="true" />
            {doc.viewCount}
          </span>
          <span className="inline-flex items-center gap-xs">
            <Heart size={12} aria-hidden="true" />
            {doc.likeCount}
          </span>
        </div>
        <div className="-mx-md mt-xs border-t border-border" aria-hidden="true" />
        <div className="flex items-center gap-sm pt-xs">
          <Avatar initials={authorInitials(doc.author)} size="sm" />
          <span className="truncate text-small font-medium text-text-muted">
            {doc.author.displayName}
          </span>
        </div>
      </div>
    </Link>
  );
}

/**
 * Skeleton variant used while the feed is in flight. Matches the card
 * geometry exactly to keep cumulative layout shift at zero.
 */
export function CommunityDocCardSkeleton({ index }: { index: number }) {
  const variant = THUMB_VARIANTS[index % THUMB_VARIANTS.length] ?? THUMB_VARIANTS[0];
  return (
    <div
      className="flex flex-col overflow-hidden rounded-md border border-border bg-surface shadow-card"
      aria-hidden="true"
    >
      <div className={cn('h-[124px] w-full animate-pulse bg-gradient-to-br', variant)} />
      <div className="flex flex-1 flex-col gap-sm p-md">
        <div className="h-[18px] w-3/5 animate-pulse rounded-sm bg-surface-soft" />
        <div className="space-y-xs">
          <div className="h-[12px] w-11/12 animate-pulse rounded-sm bg-surface-soft" />
          <div className="h-[12px] w-9/12 animate-pulse rounded-sm bg-surface-soft" />
        </div>
        <div className="flex items-center gap-sm pt-xs">
          <div className="h-6 w-6 animate-pulse rounded-pill bg-surface-soft" />
          <div className="h-[10px] w-[80px] animate-pulse rounded-sm bg-surface-soft" />
        </div>
      </div>
    </div>
  );
}

function deriveTagHint(doc: DocumentListItem, index: number): string {
  // Take the first path segment when present, otherwise a stable hint
  // keyed off the index so cards in the same grid don't all show the
  // same chip. Tags are visual-only in M2 P0 (design doc "Out of scope").
  const trimmed = doc.path.replace(/^\/+|\/+$/g, '');
  if (trimmed) {
    const first = trimmed.split('/')[0];
    if (first) return first;
  }
  return TAG_HINTS[index % TAG_HINTS.length] ?? 'notes';
}

function estimateReadMinutes(excerpt: string): number {
  // The list endpoint doesn't carry the full body length, so we approximate
  // from the derived excerpt — `excerpt` is the first 160 chars (spec §4.3),
  // which projects to ~3-7 min for a typical post. For the M2 visual budget
  // we floor at 1 and cap at 12 — accurate enough for a meta-row hint, and
  // it stays in sync with the design doc's "N min" placeholder.
  const charCount = excerpt.length;
  const projectedWords = (charCount / 5) * 8; // rough body-length expansion
  const minutes = Math.max(1, Math.min(12, Math.round(projectedWords / 220)));
  return minutes;
}
