import Link from 'next/link';
import { Chip } from '@/shared/ui/chip';

/**
 * DocNotFound — design doc §"Document (/docs/{id})" "404 (id unknown or
 * `visibility != public`)": dedicated 404 card centered in main column,
 * `info.soft` "404 · NOT FOUND" chip, copy `That document isn't
 * published.`, accent text-link `Go to all documents →`.
 *
 * S1 swaps the destination to `/docs/mine` since the community feed
 * (`/docs`) lands in S2.
 */
export function DocNotFound() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center px-[28px] py-xl">
      <div className="flex max-w-[460px] flex-col items-center gap-md rounded-md border border-border bg-surface p-xl text-center shadow-card">
        <Chip variant="info">404 · NOT FOUND</Chip>
        <h1 className="text-h2 text-text">That document isn&rsquo;t available</h1>
        <p className="text-small text-text-muted">
          It may have been unpublished, deleted, or it never existed at this URL.
        </p>
        <Link
          href="/docs/mine"
          className="text-small font-medium text-accent hover:text-accent-hover"
        >
          Go to my documents &rarr;
        </Link>
      </div>
    </div>
  );
}
