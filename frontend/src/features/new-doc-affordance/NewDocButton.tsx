'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ChevronDown, Plus, Upload } from 'lucide-react';
import { Button } from '@/shared/ui/button';
import { cn } from '@/shared/lib/cn';
import {
  importMarkdownDocument,
  MAX_BODY_BYTES,
} from '@/shared/api/docs';

/**
 * `+ New document` button — composite of (a) the primary action button
 * (label = "Blank document" → navigates to `/docs/new`) and (b) a chevron
 * that opens a 200px dropdown with the second affordance, "Import .md…".
 *
 * Per design doc §"+ New document dropdown" the two affordances are:
 *   + Blank document       → /docs/new
 *   ↑ Import .md…          → native file picker, then POST /api/docs multipart
 *
 * The `.md` upload is also reachable by drag-drop onto the viewport (see
 * `DragDropImportOverlay`). The size cap (1 MB per ADR-12 §4) is checked
 * client-side here so an over-cap file never leaves the browser.
 */

export interface NewDocButtonProps {
  className?: string;
  /** Optional folder path to inherit on Blank-document navigation. */
  folderPath?: string;
}

export function NewDocButton({ className, folderPath }: NewDocButtonProps) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Close the dropdown on outside click / Escape.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('mousedown', onDown);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('mousedown', onDown);
      window.removeEventListener('keydown', onKey);
    };
  }, [open]);

  // Auto-clear the inline error after 4s so the row doesn't sit forever.
  useEffect(() => {
    if (!errorMessage) return;
    const id = window.setTimeout(() => setErrorMessage(null), 4000);
    return () => window.clearTimeout(id);
  }, [errorMessage]);

  const newDocHref = folderPath ? `/docs/new?path=${encodeURIComponent(folderPath)}` : '/docs/new';

  const handleImportClick = useCallback(() => {
    setOpen(false);
    fileInputRef.current?.click();
  }, []);

  const handleFileChange = useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0];
      event.target.value = '';
      if (!file) return;
      if (!file.name.toLowerCase().endsWith('.md')) {
        setErrorMessage('Only .md files are accepted.');
        return;
      }
      if (file.size > MAX_BODY_BYTES) {
        setErrorMessage(`${file.name} is too large (>1 MB). Trim and try again.`);
        return;
      }
      setImporting(true);
      const result = await importMarkdownDocument(file, { path: folderPath });
      setImporting(false);
      if (result.kind === 'ok') {
        router.push(`/docs/${result.value.id}`);
      } else if (result.kind === 'unauthorized') {
        router.push('/login?next=' + encodeURIComponent('/docs/mine'));
      } else if (result.kind === 'too-large') {
        setErrorMessage(`${file.name} is too large (>1 MB). Trim and try again.`);
      } else {
        setErrorMessage(`Couldn't import ${file.name}. Make sure it's a UTF-8 Markdown file.`);
      }
    },
    [folderPath, router],
  );

  return (
    <div ref={wrapRef} className={cn('relative inline-flex', className)}>
      <input
        ref={fileInputRef}
        type="file"
        accept=".md,text/markdown"
        className="sr-only"
        onChange={handleFileChange}
        aria-label="Import markdown file"
      />
      <Button
        href={newDocHref}
        variant="primary"
        className="rounded-r-none border-r-0"
        aria-label="New document"
      >
        <Plus size={14} aria-hidden="true" />
        <span>New document</span>
      </Button>
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="More new-document options"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          'inline-flex h-auto items-center justify-center rounded-md rounded-l-none border border-l-0 border-accent bg-accent px-sm text-surface',
          'transition-colors duration-[140ms] hover:bg-accent-hover',
          'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
        )}
      >
        <ChevronDown size={14} aria-hidden="true" />
      </button>
      {open && (
        <div
          role="menu"
          aria-label="New document options"
          className="absolute right-0 top-[calc(100%+6px)] z-30 w-[220px] overflow-hidden rounded-md border border-border bg-surface shadow-pop"
        >
          <a
            role="menuitem"
            href={newDocHref}
            onClick={() => setOpen(false)}
            className="flex items-center gap-sm px-md py-sm text-small text-text hover:bg-surface-soft"
          >
            <Plus size={14} className="text-text-muted" aria-hidden="true" />
            <span>Blank document</span>
          </a>
          <button
            type="button"
            role="menuitem"
            onClick={handleImportClick}
            className="flex w-full items-center gap-sm border-t border-border px-md py-sm text-left text-small text-text hover:bg-surface-soft"
          >
            <Upload size={14} className="text-text-muted" aria-hidden="true" />
            <span>{importing ? 'Importing…' : 'Import .md…'}</span>
          </button>
        </div>
      )}
      {errorMessage && (
        <p
          role="status"
          className="pointer-events-none absolute right-0 top-[calc(100%+8px)] z-20 inline-flex items-center rounded-md border border-danger bg-danger-soft px-sm py-xs text-small text-danger shadow-card"
        >
          {errorMessage}
        </p>
      )}
    </div>
  );
}
