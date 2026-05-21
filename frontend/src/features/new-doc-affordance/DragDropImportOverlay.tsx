'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Upload } from 'lucide-react';
import {
  importMarkdownDocument,
  MAX_UPLOAD_BYTES,
  UPLOAD_ERROR_COPY,
} from '@/shared/api/docs';

/**
 * Viewport-wide drag-drop overlay for source-file import. M2 shipped this
 * as a `.md`-only path; M6 widens to `.md` + `.pdf` per design context
 * §2.1 / §6.2.
 *
 * The overlay listens for `dragenter`/`dragover`/`dragleave`/`drop` on
 * `window`. Active state surfaces a centered dashed-border drop card. On
 * drop the file is uploaded via `POST /api/docs` multipart. Non-`.md`
 * / non-`.pdf` files trigger a `danger` toast (`INVALID_FILE_TYPE` copy);
 * oversize files surface the `FILE_TOO_LARGE` copy. Backend-mapped errors
 * (corrupted / encrypted / too many pages / OCR cap) use the same
 * `UPLOAD_ERROR_COPY` dictionary so the NewDocButton and overlay never
 * disagree on what to say.
 *
 * On `/docs/{id}` edit / `/docs/new` routes the visual mode is destructive
 * ("Drop .md/.pdf to replace this document's body") per the design doc;
 * S1 scope ships only `/docs/mine` (which always means a fresh document),
 * so we render the create variant here. Per-page destructive variant is
 * a follow-up.
 */

export interface DragDropImportOverlayProps {
  /** Folder path applied to the multipart `path` field on upload. */
  folderPath?: string;
  /** Optional callback after a successful import. Receives the new doc id. */
  onImportComplete?: (id: string) => void;
}

export function DragDropImportOverlay({
  folderPath,
  onImportComplete,
}: DragDropImportOverlayProps) {
  const router = useRouter();
  const [active, setActive] = useState(false);
  const [uploading, setUploading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Auto-clear errors after 5s — long enough to read the multi-clause
  // PDF error copy (e.g., "Scanned PDF too long (max 30 pages for OCR).")
  // without rushing the reader.
  useEffect(() => {
    if (!error) return;
    const id = window.setTimeout(() => setError(null), 5000);
    return () => window.clearTimeout(id);
  }, [error]);

  const handleImport = useCallback(
    async (file: File) => {
      const name = file.name.toLowerCase();
      if (!(name.endsWith('.md') || name.endsWith('.pdf'))) {
        setError(UPLOAD_ERROR_COPY.INVALID_FILE_TYPE);
        return;
      }
      if (file.size > MAX_UPLOAD_BYTES) {
        setError(UPLOAD_ERROR_COPY.FILE_TOO_LARGE);
        return;
      }
      setUploading(file.name);
      const result = await importMarkdownDocument(file, { path: folderPath });
      setUploading(null);
      if (result.kind === 'ok') {
        onImportComplete?.(result.value.id);
        router.push(`/docs/${result.value.id}`);
      } else if (result.kind === 'unauthorized') {
        router.push('/login?next=' + encodeURIComponent('/docs/mine'));
      } else if (result.kind === 'upload-rejected') {
        setError(UPLOAD_ERROR_COPY[result.code]);
      } else if (result.kind === 'too-large') {
        setError(
          result.code ? UPLOAD_ERROR_COPY[result.code] : UPLOAD_ERROR_COPY.FILE_TOO_LARGE,
        );
      } else {
        setError(`Couldn’t import ${file.name}. Please try again.`);
      }
    },
    [folderPath, onImportComplete, router],
  );

  useEffect(() => {
    let dragCounter = 0;

    const hasFile = (e: DragEvent) =>
      Array.from(e.dataTransfer?.types ?? []).includes('Files');

    const onEnter = (e: DragEvent) => {
      if (!hasFile(e)) return;
      e.preventDefault();
      dragCounter += 1;
      if (dragCounter === 1) setActive(true);
    };
    const onOver = (e: DragEvent) => {
      if (!hasFile(e)) return;
      e.preventDefault();
    };
    const onLeave = (e: DragEvent) => {
      if (!hasFile(e)) return;
      e.preventDefault();
      dragCounter -= 1;
      if (dragCounter <= 0) {
        dragCounter = 0;
        setActive(false);
      }
    };
    const onDrop = async (e: DragEvent) => {
      if (!hasFile(e)) return;
      e.preventDefault();
      dragCounter = 0;
      setActive(false);
      const file = e.dataTransfer?.files?.[0];
      if (!file) return;
      await handleImport(file);
    };

    window.addEventListener('dragenter', onEnter);
    window.addEventListener('dragover', onOver);
    window.addEventListener('dragleave', onLeave);
    window.addEventListener('drop', onDrop);
    return () => {
      window.removeEventListener('dragenter', onEnter);
      window.removeEventListener('dragover', onOver);
      window.removeEventListener('dragleave', onLeave);
      window.removeEventListener('drop', onDrop);
    };
  }, [handleImport]);

  const visible = active || !!uploading;

  return (
    <>
      {visible && (
        <div
          role="presentation"
          aria-hidden="true"
          className="fixed inset-0 z-40 flex items-center justify-center bg-[rgba(42,44,32,0.30)]"
        >
          <div className="flex h-[200px] w-[420px] flex-col items-center justify-center gap-sm rounded-lg border-2 border-dashed border-border-strong bg-surface px-xl py-lg text-center shadow-pop">
            <Upload size={36} className="text-accent" aria-hidden="true" />
            {uploading ? (
              <>
                <p className="text-h3 text-text">Uploading {uploading}…</p>
                <p className="text-small text-text-muted">Hold on.</p>
              </>
            ) : (
              <>
                <p className="text-h3 text-text">Drop a .md or .pdf to import</p>
                <p className="text-small text-text-muted">Markdown or PDF · 25 MB max</p>
              </>
            )}
          </div>
        </div>
      )}
      {error && (
        <div
          role="status"
          aria-live="polite"
          className="pointer-events-none fixed bottom-[24px] right-[24px] z-40 max-w-[360px] rounded-md border border-danger bg-danger-soft px-md py-sm text-small text-danger shadow-pop"
        >
          {error}
        </div>
      )}
    </>
  );
}

export const handleImportFile = importMarkdownDocument; // re-export for callers that don't want to wire the overlay
