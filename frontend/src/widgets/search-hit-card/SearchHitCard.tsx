import Link from 'next/link';
import { Avatar } from '@/shared/ui/avatar';
import { Chip } from '@/shared/ui/chip';
import { cn } from '@/shared/lib/cn';
import { parseSnippet } from '@/shared/lib/snippet';
import { authorInitials, formatRelative, type SearchHit } from '@/entities/document';

/**
 * SearchHitCard — single hit on `/docs/search`.
 *
 * Per design doc M2-docs.md §"Search results" §Key elements:
 *  - title (h3)
 *  - snippet in `font.body text.muted` with `<mark>`-tagged spans
 *    rendered against `accent.soft` bg
 *  - meta row (left): visibility chip · `/docs/{id-prefix}` · updated
 *    relative time
 *  - author row (right): 14px khaki avatar + display name in
 *    `font.small`/500 `text.muted` (only when the hit is `public`)
 *
 * The whole card is a link to `/docs/{id}` — search always navigates to
 * the canonical single-document route.
 */

export interface SearchHitCardProps {
  hit: SearchHit;
  className?: string;
}

export function SearchHitCard({ hit, className }: SearchHitCardProps) {
  const segments = parseSnippet(hit.snippet);
  const idPrefix = hit.documentId.slice(0, 8);
  const isPublic = hit.visibility === 'public';
  const updated = formatRelative(hit.publishedAt ?? hit.updatedAt);

  return (
    <Link
      href={`/docs/${hit.documentId}`}
      className={cn(
        'group flex flex-col gap-sm rounded-md border border-border bg-surface px-md py-md shadow-card',
        'transition-all duration-[140ms] hover:-translate-y-[1px] hover:border-accent hover:shadow-pop',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
        className,
      )}
    >
      <h3 className="text-h3 text-text group-hover:text-accent">{hit.title}</h3>
      {segments.length > 0 && (
        <p className="line-clamp-2 text-body text-text-muted">
          {segments.map((seg, i) =>
            seg.type === 'mark' ? (
              <mark
                key={i}
                className="rounded-sm bg-accent-soft px-[2px] text-text"
              >
                {seg.text}
              </mark>
            ) : (
              <span key={i}>{seg.text}</span>
            ),
          )}
        </p>
      )}
      <div className="flex flex-wrap items-center justify-between gap-sm">
        <div className="flex flex-wrap items-center gap-sm text-small text-text-subtle">
          {isPublic ? (
            <Chip variant="accent">Published</Chip>
          ) : (
            <Chip variant="neutral">Draft</Chip>
          )}
          <span aria-hidden="true">·</span>
          <span className="font-mono text-[12px]">/docs/{idPrefix}…</span>
          <span aria-hidden="true">·</span>
          <span>updated {updated}</span>
        </div>
        {hit.author && (
          <div className="flex items-center gap-sm">
            <Avatar initials={authorInitials(hit.author)} size="sm" />
            <span className="text-small font-medium text-text-muted">
              {hit.author.displayName}
            </span>
          </div>
        )}
      </div>
    </Link>
  );
}

/**
 * Hit skeleton — title bar + two snippet bars + meta row.
 */
export function SearchHitCardSkeleton() {
  return (
    <div
      aria-hidden="true"
      className="flex flex-col gap-sm rounded-md border border-border bg-surface px-md py-md shadow-card"
    >
      <div className="h-[18px] w-3/5 animate-pulse rounded-sm bg-surface-soft" />
      <div className="space-y-xs">
        <div className="h-[12px] w-11/12 animate-pulse rounded-sm bg-surface-soft" />
        <div className="h-[12px] w-9/12 animate-pulse rounded-sm bg-surface-soft" />
      </div>
      <div className="flex items-center gap-sm">
        <div className="h-[18px] w-[60px] animate-pulse rounded-pill bg-surface-soft" />
        <div className="h-[12px] w-[100px] animate-pulse rounded-sm bg-surface-soft" />
      </div>
    </div>
  );
}
