'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Copy, Eye, Heart } from 'lucide-react';
import { Avatar } from '@/shared/ui/avatar';
import { Chip } from '@/shared/ui/chip';
import { MarkdownReader } from '@/features/docs-reader';
import { formatRelative } from '@/entities/document';
import type { Document } from '@/entities/document';

/**
 * DocReader — read-only surface for `/docs/{id}` when the caller is NOT
 * the document's author. Per design doc §"Document (/docs/{id})":
 *  - title (h1)
 *  - author block: 32px avatar + display name + relative published date
 *  - URL pill (copy-link affordance) right-aligned to the author row
 *  - meta row: view count + like button (S1: disabled — like is S2)
 *  - markdown body via the reader pipeline
 *
 * S1 omits: like toggling (button rendered visually but inert),
 * view-counter beacon (omitted per dispatch).
 */

export interface DocReaderProps {
  doc: Document;
}

export function DocReader({ doc }: DocReaderProps) {
  const [copied, setCopied] = useState(false);
  const url =
    typeof window === 'undefined' ? `/docs/${doc.id}` : `${window.location.origin}/docs/${doc.id}`;
  const authorInitials = doc.author.displayName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((s) => s[0]?.toUpperCase() ?? '')
    .join('') || '?';

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // Silent fail; the URL is visible on screen.
    }
  };

  return (
    <article className="mx-auto flex w-full max-w-[800px] flex-col gap-lg px-[28px] py-xl">
      <header className="flex flex-col gap-md">
        <h1 className="text-h1 text-text">{doc.title}</h1>
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
        <div className="flex items-center gap-md">
          <span className="inline-flex items-center gap-xs text-small text-text-muted">
            <Eye size={13} aria-hidden="true" />
            {doc.viewCount} {doc.viewCount === 1 ? 'view' : 'views'}
          </span>
          <span
            aria-disabled="true"
            className="inline-flex items-center gap-xs rounded-pill border border-border-strong px-sm py-[3px] text-small text-text-muted"
            title="Sign in to like (coming in S2)"
          >
            <Heart size={13} aria-hidden="true" />
            {doc.likeCount}
          </span>
          {copied && <Chip variant="success">Link copied</Chip>}
        </div>
      </header>

      <MarkdownReader body={doc.body} />

      <footer className="border-t border-border pt-md">
        <Link
          href="/docs/mine"
          className="text-small font-medium text-accent hover:text-accent-hover"
        >
          &larr; Back to my documents
        </Link>
      </footer>
    </article>
  );
}
