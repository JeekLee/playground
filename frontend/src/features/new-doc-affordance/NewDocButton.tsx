'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ChevronDown, Plus, Upload } from 'lucide-react';
import { Button } from '@/shared/ui/button';
import { cn } from '@/shared/lib/cn';
import {
  importMarkdownDocument,
  MAX_UPLOAD_BYTES,
  UPLOAD_ERROR_COPY,
} from '@/shared/api/docs';

/**
 * `+ New document` button — composite of (a) the primary action button
 * (label = "Blank document" → navigates to `/docs/new`) and (b) a chevron
 * that opens a 200px dropdown with the second affordance.
 *
 * Dropdown rows per M2 design doc §"+ New document dropdown" + M6
 * design context §2.1 (the row 2 label is the one M6 change):
 *   + Blank document             → /docs/new
 *   ↑ Import .md or .pdf…        → native file picker, then POST /api/docs multipart
 *
 * Upload semantics:
 *   - Native picker `accept` widens from `.md` → `.md,.pdf` per M6 §2.1.
 *   - Client-side size cap is `MAX_UPLOAD_BYTES` (25 MB per ADR-16); over
 *     that the file never leaves the browser and the toast says the same
 *     thing the server's 413 would (kept aligned so the UX doesn't shift
 *     between client + server reasons for the same outcome).
 *   - On a 400/413 with a known `code`, the toast copy is sourced from
 *     {@link UPLOAD_ERROR_COPY} (M6 design context §6.2).
 *   - `.md` files are also reachable by drag-drop onto the viewport (see
 *     `DragDropImportOverlay`).
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

  // Auto-clear the inline error after 5s so the row doesn't sit forever.
  // PDF error copy is longer than the M2 markdown copy, so the dwell goes
  // 4s → 5s — enough to read "max 30 pages for OCR" without rushing.
  useEffect(() => {
    if (!errorMessage) return;
    const id = window.setTimeout(() => setErrorMessage(null), 5000);
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
      const name = file.name.toLowerCase();
      if (!(name.endsWith('.md') || name.endsWith('.pdf'))) {
        setErrorMessage(UPLOAD_ERROR_COPY.INVALID_FILE_TYPE);
        return;
      }
      if (file.size > MAX_UPLOAD_BYTES) {
        setErrorMessage(UPLOAD_ERROR_COPY.FILE_TOO_LARGE);
        return;
      }
      setImporting(true);
      const result = await importMarkdownDocument(file, { path: folderPath });
      setImporting(false);
      if (result.kind === 'ok') {
        router.push(`/docs/${result.value.id}`);
      } else if (result.kind === 'unauthorized') {
        router.push('/login?next=' + encodeURIComponent('/docs/mine'));
      } else if (result.kind === 'upload-rejected') {
        setErrorMessage(UPLOAD_ERROR_COPY[result.code]);
      } else if (result.kind === 'too-large') {
        setErrorMessage(
          result.code ? UPLOAD_ERROR_COPY[result.code] : UPLOAD_ERROR_COPY.FILE_TOO_LARGE,
        );
      } else {
        setErrorMessage(`Couldn’t import ${file.name}. Please try again.`);
      }
    },
    [folderPath, router],
  );

  return (
    <div ref={wrapRef} className={cn('relative inline-flex', className)}>
      <input
        ref={fileInputRef}
        type="file"
        // M6 — picker accepts both `.md` and `.pdf` sources. The MIME hints
        // (`text/markdown`, `application/pdf`) help browsers that resolve
        // file dialogs by mime rather than extension.
        accept=".md,.pdf,text/markdown,application/pdf"
        className="sr-only"
        onChange={handleFileChange}
        aria-label="Import .md or .pdf file"
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
          className="absolute right-0 top-[calc(100%+6px)] z-30 w-[240px] overflow-hidden rounded-md border border-border bg-surface shadow-pop"
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
            {/* M6.1: copy split — "Uploading…" while the multipart upload
                streams; "Analyzing…" is shown on the destination page via
                the SSE pill, so the dropdown stays focused on the upload
                phase. The dropdown closes the moment the upload resolves
                and we navigate. */}
            <span>{importing ? 'Uploading…' : 'Import .md or .pdf…'}</span>
          </button>
        </div>
      )}
      {errorMessage && (
        <p
          role="status"
          aria-live="polite"
          className="absolute right-0 top-[calc(100%+8px)] z-20 inline-flex max-w-[360px] items-center rounded-md border border-danger bg-danger-soft px-sm py-xs text-small text-danger shadow-card"
        >
          {errorMessage}
        </p>
      )}
    </div>
  );
}
