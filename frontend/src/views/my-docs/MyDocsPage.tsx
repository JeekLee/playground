import Link from 'next/link';
import { FileText, PenLine } from 'lucide-react';
import { Button } from '@/shared/ui/button';
import { NewDocButton, DragDropImportOverlay } from '@/features/new-doc-affordance';
import { DocListRow } from '@/widgets/doc-list-row';
import type { MyDocumentListItem } from '@/entities/document';

/**
 * `/docs/mine` — the author's at-a-glance index of every document they've
 * authored. Per design doc §"My documents":
 *   - page header: h2 "My documents" (left) + `+ New document` (right)
 *   - list pane: bordered card, rows separated by 1px dividers, each row
 *     showing title + visibility chip + "Updated <relative>" + counts
 *
 * S1 scope: drops the left tree pane + folder filter (S3 scope per
 * dispatch). The list spans the full content width; clicking any row
 * routes to `/docs/{id}` for view/edit.
 *
 * The empty state mirrors design doc §"Empty / error / loading states"
 * for `/docs/mine`: "No documents yet. Start writing." + a primary
 * `+ New document` button.
 *
 * The viewport-wide drag-drop overlay for `.md` import is also mounted
 * here per the design doc.
 */

export interface MyDocsPageProps {
  docs: MyDocumentListItem[];
  loadError?: string;
}

export function MyDocsPage({ docs, loadError }: MyDocsPageProps) {
  return (
    <div className="flex flex-col gap-xl px-[28px] py-[26px]">
      <DragDropImportOverlay />

      <section className="flex items-center justify-between gap-md">
        <div className="flex flex-col gap-xs">
          <p className="text-eyebrow text-accent">My workspace</p>
          <h1 className="text-h2 text-text">My documents</h1>
          <p className="text-small text-text-muted">
            Every document you&rsquo;ve authored, mixed visibility. Click a row to read or edit.
          </p>
        </div>
        <NewDocButton />
      </section>

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
      ) : docs.length === 0 ? (
        <EmptyState />
      ) : (
        <section
          aria-label="My documents"
          className="overflow-hidden rounded-md border border-border bg-surface shadow-card"
        >
          {docs.map((doc, i) => (
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
  );
}

function EmptyState() {
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
      <Button href="/docs/new" variant="primary">
        <PenLine size={14} aria-hidden="true" />
        <span>Start a new document</span>
      </Button>
    </section>
  );
}
