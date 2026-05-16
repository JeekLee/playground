import { TileGrid } from '@/widgets/tile-grid';
import type { User } from '@/entities/user';

/**
 * Signed-in Home composition — same shape as Public Home with the
 * subtitle deltas pinned in the M1 design context. Sidebar account
 * footer + topbar account pill are owned by the layout (they swap on
 * `user`); this page changes only the hero subtitle.
 */

export interface HomeSignedInPageProps {
  user: User;
}

export function HomeSignedInPage({ user }: HomeSignedInPageProps) {
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

      <section className="flex flex-col gap-lg" aria-labelledby="latest-documents">
        <div className="flex items-baseline justify-between">
          <h2 id="latest-documents" className="text-h2 text-text">
            Latest documents
          </h2>
          <a
            href="#all-docs"
            className="text-small font-medium text-accent hover:text-accent-hover"
          >
            All documents &rarr;
          </a>
        </div>
        <article className="flex flex-col items-center gap-sm rounded-md border border-border bg-surface p-xl text-center shadow-card">
          <p className="text-eyebrow text-accent">M2 — Documents</p>
          <h3 className="text-h3 text-text">
            Documents will appear here when the document is online.
          </h3>
          <p className="max-w-[560px] text-small text-text-muted">
            Your private documents and the public reader feed both land in M2.
          </p>
          <a
            href="https://github.com/JeekLee/playground/milestones"
            target="_blank"
            rel="noreferrer noopener"
            className="text-small font-medium text-accent hover:text-accent-hover"
          >
            &rarr; Track the M2 milestone on GitHub
          </a>
        </article>
      </section>
    </div>
  );
}
