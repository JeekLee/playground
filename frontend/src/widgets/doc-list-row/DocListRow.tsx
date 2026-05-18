import { Eye, Heart, MoreHorizontal } from 'lucide-react';
import { Chip } from '@/shared/ui/chip';
import { cn } from '@/shared/lib/cn';
import { formatRelative, type MyDocumentListItem } from '@/entities/document';

/**
 * DocListRow — single-row representation of one of the caller's docs on
 * `/docs/mine`. Per design doc M2-docs.md §"My documents" key elements:
 *   left:   title (h3) + visibility chip
 *   middle: "Updated <relative>" (`text-muted`)
 *   right:  for Published rows → "👁 viewCount · ♥ likeCount" + overflow,
 *           for Draft/Private rows → "—" + overflow.
 * Hover lift swaps the row background to `surface.soft`.
 *
 * S1 omits per-row hover-as-link affordances beyond the row itself
 * being a link target (handled by the parent's `<a>` wrapper).
 */

export interface DocListRowProps {
  doc: MyDocumentListItem;
  className?: string;
}

export function DocListRow({ doc, className }: DocListRowProps) {
  const isPublished = doc.visibility === 'public';
  return (
    <div
      className={cn(
        'flex w-full items-center gap-md px-md py-md transition-colors duration-[140ms] hover:bg-surface-soft',
        className,
      )}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-xs">
        <div className="flex items-center gap-sm">
          <h3 className="truncate text-h3 text-text">{doc.title}</h3>
          {isPublished ? (
            <Chip variant="accent">Published</Chip>
          ) : (
            <Chip variant="neutral">Draft</Chip>
          )}
        </div>
        {doc.excerpt && (
          <p className="line-clamp-1 text-small text-text-muted">{doc.excerpt}</p>
        )}
      </div>
      <span className="hidden text-small text-text-muted md:inline">
        Updated {formatRelative(doc.updatedAt)}
      </span>
      <div className="flex w-[120px] items-center justify-end gap-sm text-small text-text-muted">
        {isPublished ? (
          <>
            <span className="inline-flex items-center gap-xs">
              <Eye size={13} aria-hidden="true" />
              {doc.viewCount}
            </span>
            <span className="inline-flex items-center gap-xs">
              <Heart size={13} aria-hidden="true" />
              {doc.likeCount}
            </span>
          </>
        ) : (
          <span aria-hidden="true">—</span>
        )}
        <MoreHorizontal size={14} aria-hidden="true" className="text-text-subtle" />
      </div>
    </div>
  );
}
