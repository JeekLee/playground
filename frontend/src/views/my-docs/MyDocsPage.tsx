'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { FileText, PenLine, X } from 'lucide-react';
import { Button } from '@/shared/ui/button';
import { Chip } from '@/shared/ui/chip';
import { NewDocButton, DragDropImportOverlay } from '@/features/new-doc-affordance';
import { DocListRow } from '@/widgets/doc-list-row';
import { FolderTree, type StatusFilter } from '@/widgets/folder-tree';
import {
  normalizeFolderPath,
  ROOT_PATH,
  type FolderListItem,
  type MyDocumentListItem,
} from '@/entities/document';

/**
 * `/docs/mine` — the author's directory + list workspace. Per design
 * doc M2-docs.md §"My documents" the layout splits horizontally:
 *
 *   ┌──────────────┬────────────────────────────────────────┐
 *   │  tree pane   │   list pane                            │
 *   │  220px       │   fills remaining width                │
 *   │              │                                        │
 *   │  STATUS      │   ▤ Building an agent team   2h · 1.2K │
 *   │   All     N  │   ▤ Why a single-developer…  2d · —    │
 *   │   Drafts  N  │   …                                    │
 *   │   Published N│                                        │
 *   │              │                                        │
 *   │  FOLDERS     │                                        │
 *   │   ▾ /        │                                        │
 *   │   ▾ agents   │                                        │
 *   │     · build- │                                        │
 *   └──────────────┴────────────────────────────────────────┘
 *
 * Path filter is server-driven (the page route passes `path` through to
 * `GET /api/docs?scope=mine&path=...`). Status filter is client-side
 * because the M2 list endpoint returns all visibilities and the
 * filter is purely a UI toggle (spec §6.1 + design doc).
 *
 * The post-delete `?deleted=<title>` query triggers a single dismissible
 * toast with a deliberately non-functional `Undo` link (ADR-12 §13).
 */

export interface MyDocsPageProps {
  docs: MyDocumentListItem[];
  folders: FolderListItem[];
  activePath: string;
  activeStatus: StatusFilter;
  /** Set when the route was reached via `?deleted=<title>` (post-delete redirect). */
  deletedTitle?: string;
  loadError?: string;
}

export function MyDocsPage({
  docs,
  folders,
  activePath,
  activeStatus,
  deletedTitle,
  loadError,
}: MyDocsPageProps) {
  const router = useRouter();

  const [showDeletedToast, setShowDeletedToast] = useState(Boolean(deletedTitle));
  useEffect(() => {
    if (!showDeletedToast) return;
    const handle = window.setTimeout(() => {
      setShowDeletedToast(false);
      // Strip the `?deleted=` query off the URL so a refresh doesn't
      // re-fire the toast.
      router.replace('/docs/mine', { scroll: false });
    }, 6000);
    return () => window.clearTimeout(handle);
  }, [router, showDeletedToast]);

  // Counts that drive the status pane match the rows actually returned
  // for the current `path` filter — so switching folders updates the
  // pane numbers in sync with the list.
  const statusCounts = useMemo(() => {
    let drafts = 0;
    let published = 0;
    for (const doc of docs) {
      if (doc.visibility === 'public') published += 1;
      else drafts += 1;
    }
    return { all: docs.length, drafts, published };
  }, [docs]);

  // Client-side status filter on the already-loaded list.
  const filtered = useMemo(() => {
    if (activeStatus === 'all') return docs;
    if (activeStatus === 'drafts') {
      return docs.filter((d) => d.visibility !== 'public');
    }
    return docs.filter((d) => d.visibility === 'public');
  }, [docs, activeStatus]);

  const onSelectPath = useCallback(
    (path: string) => {
      const canon = normalizeFolderPath(path);
      const qs = new URLSearchParams();
      if (canon !== ROOT_PATH) qs.set('path', canon);
      if (activeStatus !== 'all') qs.set('status', activeStatus);
      const href = qs.toString() ? `/docs/mine?${qs.toString()}` : '/docs/mine';
      router.push(href);
    },
    [activeStatus, router],
  );

  const onSelectStatus = useCallback(
    (status: StatusFilter) => {
      const qs = new URLSearchParams();
      if (activePath !== ROOT_PATH) qs.set('path', activePath);
      if (status !== 'all') qs.set('status', status);
      const href = qs.toString() ? `/docs/mine?${qs.toString()}` : '/docs/mine';
      router.push(href);
    },
    [activePath, router],
  );

  const breadcrumb =
    activePath === ROOT_PATH
      ? 'My documents'
      : `My documents / ${activePath.replace(/^\/+|\/+$/g, '').split('/').join(' / ')}`;

  // Pre-load the New-document destination with the current folder so
  // typing a doc lands in the same folder the user is viewing.
  const folderForNew = activePath === ROOT_PATH ? undefined : activePath;
  const newDocHref =
    activePath === ROOT_PATH
      ? '/docs/new'
      : `/docs/new?path=${encodeURIComponent(activePath)}`;

  return (
    <div className="flex flex-col gap-xl px-[28px] py-[26px]">
      <DragDropImportOverlay />

      <DeletedToast
        title={deletedTitle}
        open={showDeletedToast}
        onDismiss={() => {
          setShowDeletedToast(false);
          router.replace('/docs/mine', { scroll: false });
        }}
      />

      <section className="flex flex-wrap items-end justify-between gap-md">
        <div className="flex flex-col gap-xs">
          <p className="text-eyebrow text-accent">{breadcrumb}</p>
          <h1 className="text-h2 text-text">My documents</h1>
          <p className="text-small text-text-muted">
            Every document you&rsquo;ve authored, organized by folder. Click a row to read or
            edit.
          </p>
        </div>
        <NewDocButton folderPath={folderForNew} />
      </section>

      <div className="flex flex-col gap-md lg:flex-row lg:items-start">
        <FolderTree
          folders={folders}
          activePath={activePath}
          activeStatus={activeStatus}
          statusCounts={statusCounts}
          onSelectPath={onSelectPath}
          onSelectStatus={onSelectStatus}
        />

        <div className="flex min-w-0 flex-1 flex-col gap-md">
          <ListHeader
            activePath={activePath}
            activeStatus={activeStatus}
            shownCount={filtered.length}
            totalCount={docs.length}
          />

          {loadError ? (
            <section
              role="alert"
              className="flex flex-col items-start gap-sm rounded-md border border-danger bg-danger-soft px-md py-md text-small text-danger"
            >
              <p className="font-medium">Couldn&rsquo;t load your documents</p>
              <p className="text-text-muted">{loadError}</p>
              <Link
                href="/docs/mine"
                className="text-small font-medium text-accent hover:text-accent-hover"
              >
                Retry &rarr;
              </Link>
            </section>
          ) : filtered.length === 0 ? (
            <EmptyState
              activePath={activePath}
              activeStatus={activeStatus}
              hasAnyDocs={docs.length > 0}
              newDocHref={newDocHref}
            />
          ) : (
            <section
              aria-label="My documents"
              className="overflow-hidden rounded-md border border-border bg-surface shadow-card"
            >
              {filtered.map((doc, i) => (
                <Link
                  key={doc.id}
                  href={`/docs/${doc.id}`}
                  className={
                    'block focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent ' +
                    (i > 0 ? 'border-t border-border' : '')
                  }
                >
                  <DocListRow doc={doc} />
                </Link>
              ))}
            </section>
          )}
        </div>
      </div>
    </div>
  );
}

function ListHeader({
  activePath,
  activeStatus,
  shownCount,
  totalCount,
}: {
  activePath: string;
  activeStatus: StatusFilter;
  shownCount: number;
  totalCount: number;
}) {
  const pathLabel = activePath === ROOT_PATH ? '/' : activePath;
  const statusLabel =
    activeStatus === 'all'
      ? 'all'
      : activeStatus === 'drafts'
        ? 'drafts only'
        : 'published only';
  return (
    <header className="flex flex-wrap items-baseline justify-between gap-sm">
      <div className="flex items-baseline gap-sm">
        <span className="font-mono text-small text-text">{pathLabel}</span>
        <Chip variant="neutral">{statusLabel}</Chip>
      </div>
      <span className="text-small text-text-subtle">
        {shownCount} of {totalCount}
      </span>
    </header>
  );
}

function EmptyState({
  activePath,
  activeStatus,
  hasAnyDocs,
  newDocHref,
}: {
  activePath: string;
  activeStatus: StatusFilter;
  hasAnyDocs: boolean;
  newDocHref: string;
}) {
  // Three empty shapes per design doc §"My documents" §Empty states:
  //   (a) whole workspace empty → New-document CTA
  //   (b) folder has docs but the status filter is empty → muted line
  //   (c) folder itself is empty (root has docs, this folder doesn't)
  if (!hasAnyDocs) {
    return (
      <section className="flex flex-col items-center gap-md rounded-md border border-dashed border-border-strong bg-surface px-xl py-xl text-center">
        <div className="flex h-[44px] w-[44px] items-center justify-center rounded-md bg-surface-soft text-accent">
          <FileText size={20} aria-hidden="true" />
        </div>
        <div className="flex flex-col gap-xs">
          <h2 className="text-h3 text-text">No documents yet</h2>
          <p className="max-w-[420px] text-small text-text-muted">
            Start with a blank page, or drag a Markdown file onto this window to import it.
          </p>
        </div>
        <Button href={newDocHref} variant="primary">
          <PenLine size={14} aria-hidden="true" />
          <span>Start a new document</span>
        </Button>
      </section>
    );
  }

  const pathLabel = activePath === ROOT_PATH ? '/' : activePath;
  const message =
    activeStatus === 'all'
      ? `No documents in ${pathLabel}.`
      : `No ${activeStatus} in ${pathLabel}.`;
  return (
    <section className="rounded-md border border-dashed border-border bg-surface px-md py-lg text-center">
      <p className="text-small text-text-muted">{message}</p>
    </section>
  );
}

function DeletedToast({
  title,
  open,
  onDismiss,
}: {
  title: string | undefined;
  open: boolean;
  onDismiss: () => void;
}) {
  if (!open || !title) return null;
  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed right-[28px] top-[80px] z-30 flex max-w-[420px] items-center gap-md rounded-md border border-success bg-success-soft px-md py-sm shadow-pop"
    >
      <div className="flex min-w-0 flex-1 flex-col gap-xs">
        <span className="text-small font-semibold text-success">
          Deleted &ldquo;{title}&rdquo;
        </span>
        <span className="inline-flex items-baseline gap-sm text-[11px] text-text-muted">
          The doc is gone.
          {/* ADR-12 §13: Undo link is visually present but non-functional
              in M2 P0. Marked with `data-testid` so the E2E test confirms
              the affordance without expecting behavior. */}
          <button
            type="button"
            onClick={(e) => e.preventDefault()}
            data-testid="undo-disabled"
            aria-disabled="true"
            title="Undo restore ships with M2.1"
            className="cursor-not-allowed text-text-subtle underline-offset-2 hover:underline"
          >
            Undo
          </button>
        </span>
      </div>
      <button
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss notification"
        className="flex h-[24px] w-[24px] items-center justify-center rounded-sm text-success transition-colors duration-[140ms] hover:bg-success/20"
      >
        <X size={13} aria-hidden="true" />
      </button>
    </div>
  );
}
