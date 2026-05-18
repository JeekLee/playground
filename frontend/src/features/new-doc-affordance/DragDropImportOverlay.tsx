'use client';

import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Upload } from 'lucide-react';
import {
  importMarkdownDocument,
  MAX_BODY_BYTES,
} from '@/shared/api/docs';

/**
 * Viewport-wide drag-drop overlay for `.md` import — design doc
 * §"+ New document dropdown + .md import affordance" "Drag-drop overlay
 * (active)" frame.
 *
 * The overlay listens for `dragenter`/`dragover`/`dragleave`/`drop` on
 * `window`. Active state surfaces a centered dashed-border drop card. On
 * drop the file is uploaded via `POST /api/docs` multipart. Non-`.md`
 * files trigger a `danger` toast; oversize files surface the same toast
 * the dropdown's import path uses.
 *
 * On `/docs/{id}` edit / `/docs/new` routes the visual mode is destructive
 * ("Drop .md to replace this document's body") per the design doc; S1
 * scope ships only `/docs/mine` (which always means a fresh document),
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

  // Auto-clear errors after 4s.
  useEffect(() => {
    if (!error) return;
    const id = window.setTimeout(() => setError(null), 4000);
    return () => window.clearTimeout(id);
  }, [error]);

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
      if (!file.name.toLowerCase().endsWith('.md')) {
        setError('Only .md files are accepted.');
        return;
      }
      if (file.size > MAX_BODY_BYTES) {
        setError(`${file.name} is too large (>1 MB). Trim and try again.`);
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
      } else if (result.kind === 'too-large') {
        setError(`${file.name} is too large (>1 MB). Trim and try again.`);
      } else {
        setError(`Couldn't import ${file.name}. Make sure it's a UTF-8 Markdown file.`);
      }
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
  }, [folderPath, router, onImportComplete]);

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
                <p className="text-h3 text-text">Drop a Markdown file to import</p>
                <p className="text-small text-text-muted">.md files only · 1 MB max</p>
              </>
            )}
          </div>
        </div>
      )}
      {error && (
        <div className="pointer-events-none fixed bottom-[24px] right-[24px] z-40 rounded-md border border-danger bg-danger-soft px-md py-sm text-small text-danger shadow-pop">
          {error}
        </div>
      )}
    </>
  );
}

export const handleImportFile = importMarkdownDocument; // re-export for callers that don't want to wire the overlay
