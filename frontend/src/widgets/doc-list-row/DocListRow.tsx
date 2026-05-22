import { Eye, Heart, MoreHorizontal } from 'lucide-react';
import { Chip } from '@/shared/ui/chip';
import { PdfBadge } from '@/shared/ui/pdf-badge';
import { cn } from '@/shared/lib/cn';
import {
  formatRelative,
  isExtractionFailed,
  isExtractionInFlight,
  isPdfSourced,
  type MyDocumentListItem,
} from '@/entities/document';

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
 *
 * M6.1 — when `extractionStatus` is in flight (`pending_extraction` /
 * `extracting`) or failed, the title row dims to `text-muted` and a
 * "분석 중" / "분석 실패" hint chip surfaces next to the title. The row
 * stays a click target — navigating to the detail page lets the user see
 * the full Analyzing skeleton or the failure card. The field is optional
 * on `MyDocumentListItem` (the backend list DTO doesn't currently expose
 * it), so legacy rows render unchanged.
 */

export interface DocListRowProps {
  doc: MyDocumentListItem;
  className?: string;
}

export function DocListRow({ doc, className }: DocListRowProps) {
  const isPublished = doc.visibility === 'public';
  const analyzing = isExtractionInFlight(doc.extractionStatus);
  const failed = isExtractionFailed(doc.extractionStatus);
  const dimmed = analyzing || failed;
  return (
    <div
      className={cn(
        'flex w-full items-center gap-md px-md py-md transition-colors duration-[140ms] hover:bg-surface-soft',
        className,
      )}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-xs">
        <div className="flex items-center gap-sm">
          <h3
            className={cn(
              'truncate text-h3',
              dimmed ? 'text-text-muted' : 'text-text',
            )}
          >
            {doc.title}
          </h3>
          {isPdfSourced(doc) && <PdfBadge />}
          {isPublished ? (
            <Chip variant="accent">Published</Chip>
          ) : (
            <Chip variant="neutral">Draft</Chip>
          )}
          {analyzing && (
            <Chip variant="neutral" dot>
              분석 중
            </Chip>
          )}
          {failed && (
            <Chip variant="danger" dot>
              분석 실패
            </Chip>
          )}
        </div>
        {doc.excerpt && !dimmed && (
          <p className="line-clamp-1 text-small text-text-muted">{doc.excerpt}</p>
        )}
      </div>
      <span className="hidden text-small text-text-muted md:inline">
        Updated {formatRelative(doc.updatedAt)}
      </span>
      <div className="flex w-[120px] items-center justify-end gap-sm text-small text-text-muted">
        {isPublished && !dimmed ? (
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
