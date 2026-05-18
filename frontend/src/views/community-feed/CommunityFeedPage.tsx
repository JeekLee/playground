'use client';

import { useCallback, useState } from 'react';
import { AlertTriangle, ArrowRight } from 'lucide-react';
import { Button } from '@/shared/ui/button';
import {
  CommunityDocCard,
  CommunityDocCardSkeleton,
} from '@/widgets/community-doc-card';
import { fetchCommunityFeed } from '@/shared/api/docs';
import type { DocumentListItem } from '@/entities/document';

/**
 * CommunityFeedPage — `/docs` (community feed) main column.
 *
 * Per design doc M2-docs.md §"Documents (/docs)":
 *  - hero: h1 "Latest published docs" + one-line community subtitle
 *  - section header "Latest" + accent "View archive →" link
 *  - 3-column thumbnail grid (cards from {@link CommunityDocCard})
 *  - centered "Load more →" secondary button for cursor pagination
 *  - empty state copy: "No documents published yet. Sign in to be first."
 *  - 429 handling: non-blocking banner per ADR-12 §7 anti-scrape rate limit
 *  - 5xx handling: non-blocking danger banner with retry link
 *
 * The initial page render is server-side (see `app/(shell)/docs/page.tsx`);
 * cursor pagination is handled here on the client.
 */

export interface CommunityFeedPageProps {
  initialItems: DocumentListItem[];
  initialNextCursor: string | null;
  loadError?: string;
  rateLimited?: boolean;
}

export function CommunityFeedPage({
  initialItems,
  initialNextCursor,
  loadError,
  rateLimited,
}: CommunityFeedPageProps) {
  const [items, setItems] = useState<DocumentListItem[]>(initialItems);
  const [nextCursor, setNextCursor] = useState<string | null>(initialNextCursor);
  const [loadingMore, setLoadingMore] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const [pageRateLimited, setPageRateLimited] = useState<boolean>(Boolean(rateLimited));

  const loadMore = useCallback(async () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    setPageError(null);
    const result = await fetchCommunityFeed({ cursor: nextCursor });
    setLoadingMore(false);
    if (result.kind === 'ok') {
      setItems((prev) => [...prev, ...result.value.items]);
      setNextCursor(result.value.nextCursor);
      return;
    }
    if (result.kind === 'rate-limited') {
      setPageRateLimited(true);
      return;
    }
    setPageError("Couldn't load more documents — try again in a moment.");
  }, [nextCursor, loadingMore]);

  return (
    <div className="mx-auto flex w-full max-w-[1180px] flex-col gap-xl px-[28px] py-[26px]">
      {/* Hero — design doc §"Documents (/docs)" §Key elements. */}
      <section className="flex flex-col gap-sm">
        <p className="text-eyebrow text-accent">The community</p>
        <h1 className="text-h1 text-text">Latest published docs</h1>
        <p className="max-w-[640px] text-body text-text-muted">
          Notes from the community on agents, infra, design, and the spaces in between.
        </p>
      </section>

      {(loadError || pageError) && (
        <div
          role="alert"
          className="flex items-center gap-sm rounded-md border border-danger bg-danger-soft px-md py-sm text-small text-danger"
        >
          <AlertTriangle size={14} aria-hidden="true" />
          <span>{loadError ?? pageError}</span>
        </div>
      )}

      {pageRateLimited && (
        <div
          role="status"
          className="flex items-center gap-sm rounded-md border border-warning bg-warning-soft px-md py-sm text-small text-warning"
        >
          <AlertTriangle size={14} aria-hidden="true" />
          <span>
            Slow down — the feed is rate-limited for anonymous traffic. Sign in to keep
            browsing without the cap.
          </span>
        </div>
      )}

      <section className="flex flex-col gap-lg" aria-labelledby="latest-section">
        <div className="flex items-baseline justify-between">
          <h2 id="latest-section" className="text-h2 text-text">
            Latest
          </h2>
          <span className="text-small text-text-subtle">
            {items.length} {items.length === 1 ? 'doc' : 'docs'}
          </span>
        </div>

        {items.length === 0 && !loadError ? (
          <EmptyState />
        ) : (
          <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3">
            {items.map((doc, i) => (
              <CommunityDocCard key={doc.id} doc={doc} index={i} />
            ))}
            {loadingMore &&
              Array.from({ length: 3 }).map((_, i) => (
                <CommunityDocCardSkeleton key={`sk-${i}`} index={items.length + i} />
              ))}
          </div>
        )}

        {nextCursor && (
          <div className="flex justify-center">
            <Button
              variant="secondary"
              onClick={loadMore}
              disabled={loadingMore}
              aria-label="Load more documents"
            >
              <span>{loadingMore ? 'Loading…' : 'Load more'}</span>
              {!loadingMore && <ArrowRight size={14} aria-hidden="true" />}
            </Button>
          </div>
        )}
      </section>
    </div>
  );
}

function EmptyState() {
  return (
    <section className="flex flex-col items-center gap-md rounded-md border border-dashed border-border-strong bg-surface px-xl py-xl text-center">
      <div className="flex h-[44px] w-[44px] items-center justify-center rounded-md bg-surface-soft text-accent">
        <ArrowRight size={20} aria-hidden="true" />
      </div>
      <div className="flex flex-col gap-xs">
        <h3 className="text-h3 text-text">No documents published yet</h3>
        <p className="max-w-[420px] text-small text-text-muted">
          Sign in to be first. The community feed lights up as soon as anyone
          publishes something.
        </p>
      </div>
    </section>
  );
}
