'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Copy, Eye, Pencil } from 'lucide-react';
import { Avatar } from '@/shared/ui/avatar';
import { Chip } from '@/shared/ui/chip';
import { PdfBadge } from '@/shared/ui/pdf-badge';
import { MarkdownReader } from '@/features/docs-reader';
import { LikeButton } from '@/features/doc-like';
import {
  DownloadOriginalButton,
  ExtractionAnalyzingSkeleton,
  ExtractionFailedCard,
  ExtractionStatusPill,
  useExtractionStream,
} from '@/features/docs-extraction-stream';
import {
  formatRelative,
  hasOriginalBlob,
  isExtractionFailed,
  isExtractionInFlight,
  isPdfSourced,
} from '@/entities/document';
import { incrementView } from '@/shared/api/docs';
import type { Document, ExtractionStatus } from '@/entities/document';

/**
 * DocReader — read-only surface for `/docs/{id}` when the caller is NOT
 * the document's author. Per design doc M2-docs.md §"Document
 * (/docs/{id})":
 *  - title (h1)
 *  - author block: 32px avatar + display name + relative published date
 *  - URL pill (copy-link affordance) right-aligned to the author row
 *  - meta row: 👁 viewCount + ♥ like button
 *  - markdown body via the reader pipeline
 *
 * S3 additions:
 *  - LikeButton — optimistic toggle for authenticated viewers; anonymous
 *    viewers get a click-to-reveal "Sign in to like" CTA per design doc
 *    §7.3.
 *  - View-counter beacon — `POST /api/docs/{id}/view` fires on first
 *    render (anonymous-OK; backend dedups via the `PLAYGROUND_ANON`
 *    cookie). Strict-Mode double-mount is guarded by a ref so dev mode
 *    doesn't fire two beacons.
 */

export interface DocReaderProps {
  doc: Document;
  /** Caller's authenticated status; drives the LikeButton's branch. */
  isAuthenticated: boolean;
  /**
   * Caller owns the document — surface an Edit affordance that deep-
   * links to `?mode=edit`. Non-owners never see the button regardless of
   * authentication state.
   */
  isOwner?: boolean;
}

export function DocReader({ doc, isAuthenticated, isOwner = false }: DocReaderProps) {
  const router = useRouter();
  const [copied, setCopied] = useState(false);
  const url =
    typeof window === 'undefined'
      ? `/docs/${doc.id}`
      : `${window.location.origin}/docs/${doc.id}`;
  const authorInitials =
    doc.author.displayName
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((s) => s[0]?.toUpperCase() ?? '')
      .join('') || '?';

  // M6.1 — subscribe to the extraction lifecycle. The hook is the single
  // source of truth for the live status; on terminal transition we refresh
  // the route so the SSR'd `doc.body` re-fetches with the populated
  // markdown. Pre-M6.1 rows and synchronous-path uploads ship with
  // `extractionStatus === 'extracted'` (or undefined for very old rows) so
  // the snapshot completes the stream immediately and the hook idles.
  const onExtractionTerminal = useCallback(
    (final: ExtractionStatus) => {
      // 'extracted' is the case where the body materialized — refresh to
      // pull the populated markdown from SSR. 'failed' rendering is fully
      // client-driven from the hook's state, so no refresh is needed.
      if (final === 'extracted') {
        router.refresh();
      }
    },
    [router],
  );
  const extraction = useExtractionStream({
    docId: doc.id,
    initialStatus: doc.extractionStatus,
    initialReason: doc.extractionReason,
    onTerminal: onExtractionTerminal,
  });

  const liveStatus = extraction.status ?? doc.extractionStatus;
  const analyzing = isExtractionInFlight(liveStatus);
  const failed = isExtractionFailed(liveStatus);
  const showOriginal = hasOriginalBlob(doc);

  // Fire-and-forget view beacon — public docs only (the API no-ops
  // private docs but we still skip the round-trip when the SSR'd
  // visibility says private). Also held off while extraction is in flight
  // so a half-written doc doesn't accumulate phantom views before the body
  // exists; we re-fire after extraction finishes.
  useEffect(() => {
    if (doc.visibility !== 'public') return;
    if (analyzing || failed) return;
    // Guard against React strict-mode double-mount in dev.
    let cancelled = false;
    const handle = window.setTimeout(() => {
      if (cancelled) return;
      void incrementView(doc.id);
    }, 200);
    return () => {
      cancelled = true;
      window.clearTimeout(handle);
    };
  }, [doc.id, doc.visibility, analyzing, failed]);

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Silent fail; the URL is visible on screen.
    }
  };

  const pdfSourced = isPdfSourced(doc);

  return (
    <article className="mx-auto flex w-full max-w-[1100px] flex-col gap-lg px-[28px] py-xl">
      <header className="flex flex-col gap-md">
        <h1 className="flex flex-wrap items-center gap-md text-h1 text-text">
          <span>{doc.title}</span>
          {pdfSourced && <PdfBadge className="relative top-[-2px]" />}
        </h1>
        <div className="flex flex-wrap items-center justify-between gap-md">
          <div className="flex items-center gap-sm">
            <Avatar initials={authorInitials} size="md" />
            <div className="flex flex-col leading-tight">
              <span className="text-small font-semibold text-text">
                {doc.author.displayName}
              </span>
              <span className="text-[11px] text-text-muted">
                {doc.publishedAt
                  ? `Published ${formatRelative(doc.publishedAt)}`
                  : `Last updated ${formatRelative(doc.updatedAt)}`}
              </span>
            </div>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-sm">
            {isOwner && (
              <Link
                href={`/docs/${doc.id}?mode=edit`}
                className="inline-flex items-center gap-xs rounded-pill border border-accent bg-accent-soft px-md py-[6px] text-small font-medium text-accent transition-colors duration-[140ms] hover:bg-accent hover:text-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
                aria-label="Edit this document"
              >
                <Pencil size={12} aria-hidden="true" />
                <span>Edit</span>
              </Link>
            )}
            {showOriginal && (
              <DownloadOriginalButton docId={doc.id} mimeType={doc.mimeType} />
            )}
            <button
              type="button"
              onClick={copyLink}
              className="inline-flex items-center gap-sm rounded-pill border border-border bg-surface px-md py-[6px] font-mono text-[11px] text-text-subtle transition-colors duration-[140ms] hover:bg-surface-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
              aria-label="Copy document link"
            >
              <Copy size={12} aria-hidden="true" />
              <span>/docs/{doc.id}</span>
            </button>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-md">
          <span className="inline-flex items-center gap-xs text-small text-text-muted">
            <Eye size={13} aria-hidden="true" />
            <span className="font-mono text-[12px]">{doc.viewCount}</span>
            <span>{doc.viewCount === 1 ? 'view' : 'views'}</span>
          </span>
          <LikeButton
            documentId={doc.id}
            initialLikedByMe={doc.likedByMe}
            initialLikeCount={doc.likeCount}
            isAnonymous={!isAuthenticated}
          />
          {pdfSourced && (
            // M6 §2.6 — meta row gains "· source: .pdf" tail when the doc
            // was uploaded as a PDF. The `(N pages)` portion sketched in
            // the design doc is intentionally omitted — PRD elected not
            // to persist `pdf_page_count` in P0.
            <span
              className="inline-flex items-center gap-xs text-small text-text-muted"
              aria-label="Source file format: PDF"
            >
              <span aria-hidden="true">·</span>
              <span>
                source: <span className="font-mono text-[12px]">.pdf</span>
              </span>
            </span>
          )}
          {copied && <Chip variant="success">Link copied</Chip>}
        </div>
      </header>

      {(analyzing || failed) && (
        <ExtractionStatusPill
          status={failed ? 'failed' : 'extracting'}
          pageDone={extraction.pageDone}
          pageTotal={extraction.pageTotal}
          className="-mt-sm"
        />
      )}

      {analyzing ? (
        <ExtractionAnalyzingSkeleton />
      ) : failed ? (
        <ExtractionFailedCard reason={extraction.reason ?? doc.extractionReason} />
      ) : (
        <MarkdownReader body={doc.body} />
      )}

      {extraction.connectionLost && (analyzing || failed) && (
        <p
          role="status"
          aria-live="polite"
          className="text-small text-text-muted"
        >
          Connection to the analysis stream was lost — refresh the page if the status doesn&apos;t
          update.
        </p>
      )}

      <footer className="border-t border-border pt-md">
        <Link
          href="/docs"
          className="text-small font-medium text-accent hover:text-accent-hover"
        >
          &larr; All documents
        </Link>
      </footer>
    </article>
  );
}
