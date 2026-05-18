import Link from 'next/link';
import { TileGrid } from '@/widgets/tile-grid';
import { CommunityDocCard } from '@/widgets/community-doc-card';
import type { DocumentListItem } from '@/entities/document';
import type { User } from '@/entities/user';

/**
 * Signed-in Home composition — same shape as Public Home with the
 * subtitle deltas pinned in the M1 design context. Sidebar account
 * footer + topbar account pill are owned by the layout (they swap on
 * `user`); this page changes only the hero subtitle.
 *
 * M2 S2 delta: the `Latest documents` section is replaced by the
 * owner-curated `Latest published docs` section. See
 * `HomePublicPage` for the full rationale.
 */

export interface HomeSignedInPageProps {
  user: User;
  latestDocs: DocumentListItem[];
}

export function HomeSignedInPage({ user, latestDocs }: HomeSignedInPageProps) {
  return (
    <div className="flex flex-col gap-xl px-[28px] py-[26px]">
      <section className="flex flex-col gap-sm">
        <p className="text-eyebrow text-accent">A personal platform · open to read</p>
        <h1 className="text-h1 text-text">What would you like to do today?</h1>
        <p className="max-w-[640px] text-body text-text-muted">
          Welcome back, {user.displayName}. Pick a surface — the locked Apps rows unlock as
          each milestone ships.
        </p>
      </section>

      <section className="flex flex-col gap-lg" aria-labelledby="things-you-can-try">
        <div className="flex items-baseline justify-between">
          <h2 id="things-you-can-try" className="text-h2 text-text">
            Things you can try
          </h2>
          <a href="#all" className="text-small font-medium text-accent hover:text-accent-hover">
            See all &rarr;
          </a>
        </div>
        <TileGrid />
      </section>

      <LatestDocsSection items={latestDocs} />
    </div>
  );
}

function LatestDocsSection({ items }: { items: DocumentListItem[] }) {
  return (
    <section className="flex flex-col gap-lg" aria-labelledby="latest-published-docs">
      <div className="flex items-baseline justify-between">
        <h2 id="latest-published-docs" className="text-h2 text-text">
          Latest published docs
        </h2>
        <Link
          href="/docs"
          className="text-small font-medium text-accent hover:text-accent-hover"
        >
          All documents &rarr;
        </Link>
      </div>
      {items.length === 0 ? (
        <article className="flex flex-col items-center gap-sm rounded-md border border-border bg-surface p-xl text-center shadow-card">
          <p className="text-eyebrow text-accent">M2 — Documents</p>
          <h3 className="text-h3 text-text">No published docs from the owner yet.</h3>
          <p className="max-w-[560px] text-small text-text-muted">
            Publish your first document from{' '}
            <Link
              href="/docs/mine"
              className="font-medium text-accent hover:text-accent-hover"
            >
              My documents
            </Link>{' '}
            to fill this slot, or browse{' '}
            <Link href="/docs" className="font-medium text-accent hover:text-accent-hover">
              the community feed
            </Link>
            .
          </p>
        </article>
      ) : (
        <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3">
          {items.map((doc, i) => (
            <CommunityDocCard key={doc.id} doc={doc} index={i} />
          ))}
        </div>
      )}
    </section>
  );
}
