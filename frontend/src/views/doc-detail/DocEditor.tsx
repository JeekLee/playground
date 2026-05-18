'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Trash2 } from 'lucide-react';
import { Button } from '@/shared/ui/button';
import { Chip } from '@/shared/ui/chip';
import { BlockNoteEditor, useSaveShortcut } from '@/features/docs-editor';
import { FolderPicker } from '@/features/folder-picker';
import { ConfirmModal } from '@/widgets/confirm-modal';
import {
  bodyByteSize,
  deleteDocument,
  MAX_BODY_BYTES,
  patchDocument,
  publishDocument,
  unpublishDocument,
} from '@/shared/api/docs';
import type { Document } from '@/entities/document';

/**
 * DocEditor — `/docs/{id}` author surface. Per design doc §"Edit document"
 * the toolbar carries save-state (left) + folder picker pill (center,
 * read-only in S1) + button row (`🗑 Delete` ghost → `Unpublish` outline
 * → `Publish changes`/`Publish` primary).
 *
 * Visibility flow:
 *  - When the doc is `private`, the primary button reads `Publish`.
 *  - When `public`, the primary button reads `Publish changes` (a re-save +
 *    re-publish in one click, since the editor PATCHes on debounce
 *    already; clicking `Publish changes` syncs immediately and re-runs
 *    `POST /publish` so `published_at` semantics stay consistent).
 *  - `Unpublish` (outline) renders only when `public`, and opens the
 *    confirm modal first.
 *  - `Delete` (ghost) is always available and opens the destructive
 *    confirm modal.
 *
 * The "→ View public" link strip appears below the toolbar when the doc
 * is `public` and exposes the UUID URL (design doc §"Edit document"
 * `→ View public` line).
 */

export interface DocEditorProps {
  doc: Document;
  /** Set when the page was loaded via `?published=1` (post-publish flash). */
  publishedFlash?: boolean;
}

type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved'; at: number }
  | { kind: 'error'; message: string }
  | { kind: 'too-large' };

const SAVE_DEBOUNCE_MS = 1500;

export function DocEditor({ doc, publishedFlash = false }: DocEditorProps) {
  const router = useRouter();
  const [title, setTitle] = useState(doc.title);
  const [body, setBody] = useState(doc.body);
  const [visibility, setVisibility] = useState<Document['visibility']>(doc.visibility);
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });
  const [showUnpublishModal, setShowUnpublishModal] = useState(false);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);
  const [pendingModalAction, setPendingModalAction] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [showPublishedToast, setShowPublishedToast] = useState(publishedFlash);

  // Auto-dismiss the post-publish flash toast.
  useEffect(() => {
    if (!showPublishedToast) return;
    const id = window.setTimeout(() => setShowPublishedToast(false), 5000);
    return () => window.clearTimeout(id);
  }, [showPublishedToast]);

  // Tracks the (title, body) snapshot that was last persisted to the server.
  // Starts as the SSR-loaded doc values; updated on every successful PATCH.
  // The debounced save loop + ⌘+S compare against this — they used to fall
  // back on the stale `doc.title/doc.body` SSR props, which never updated
  // after a save, so every save success triggered another no-op PATCH in a
  // tight loop driven by setSaveState reruns of the effect.
  const lastSavedRef = useRef<{ title: string; body: string }>({ title: doc.title, body: doc.body });
  const pristine =
    lastSavedRef.current.title === title && lastSavedRef.current.body === body;

  // Shared save action — reused by the debounced loop and the ⌘+S shortcut.
  const runSave = useCallback(async () => {
    if (bodyByteSize(body) > MAX_BODY_BYTES) {
      setSaveState({ kind: 'too-large' });
      return;
    }
    setSaveState({ kind: 'saving' });
    const result = await patchDocument(doc.id, {
      title: title.trim().length > 0 ? title.trim() : undefined,
      body,
    });
    if (result.kind === 'ok') {
      lastSavedRef.current = { title, body };
      setSaveState({ kind: 'saved', at: Date.now() });
    } else if (result.kind === 'too-large') {
      setSaveState({ kind: 'too-large' });
    } else if (result.kind === 'unauthorized') {
      router.push('/login?next=' + encodeURIComponent(`/docs/${doc.id}`));
    } else if (result.kind === 'not-found') {
      setSaveState({ kind: 'error', message: 'Document not found' });
    } else {
      setSaveState({ kind: 'error', message: 'Save failed — retry' });
    }
  }, [body, doc.id, router, title]);

  // Debounced PATCH. Skipped while pristine (no changes since last save).
  useEffect(() => {
    if (pristine) return;
    if (saveState.kind === 'too-large') return;
    const handle = window.setTimeout(() => { void runSave(); }, SAVE_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [pristine, runSave, saveState.kind]);

  // ⌘+S / Ctrl+S immediate save — same no-op guard as the debounced loop.
  useSaveShortcut(
    useCallback(() => { if (!pristine) void runSave(); }, [pristine, runSave]),
    saveState.kind !== 'too-large' && !publishing,
  );

  const onPublish = useCallback(async () => {
    if (publishing) return;
    setPublishing(true);
    // Sync save first so the published row matches the editor exactly.
    if (!pristine) {
      const saved = await patchDocument(doc.id, {
        title: title.trim().length > 0 ? title.trim() : undefined,
        body,
      });
      if (saved.kind !== 'ok') {
        setPublishing(false);
        setSaveState({
          kind: 'error',
          message: saved.kind === 'too-large' ? 'Document is too large' : 'Save failed — retry',
        });
        return;
      }
    }
    const published = await publishDocument(doc.id);
    setPublishing(false);
    if (published.kind === 'ok') {
      setVisibility('public');
      setShowPublishedToast(true);
      router.refresh();
    } else {
      setSaveState({ kind: 'error', message: 'Publish failed — retry' });
    }
  }, [body, doc.id, pristine, publishing, router, title]);

  const onUnpublishConfirm = useCallback(async () => {
    setPendingModalAction(true);
    setModalError(null);
    const result = await unpublishDocument(doc.id);
    setPendingModalAction(false);
    if (result.kind === 'ok') {
      setShowUnpublishModal(false);
      setVisibility('private');
      router.refresh();
    } else if (result.kind === 'unauthorized') {
      router.push('/login?next=' + encodeURIComponent(`/docs/${doc.id}`));
    } else {
      setModalError('Unpublish failed — try again.');
    }
  }, [doc.id, router]);

  const onDeleteConfirm = useCallback(async () => {
    setPendingModalAction(true);
    setModalError(null);
    const result = await deleteDocument(doc.id);
    setPendingModalAction(false);
    if (result.kind === 'ok') {
      router.push('/docs/mine?deleted=' + encodeURIComponent(doc.title));
    } else if (result.kind === 'unauthorized') {
      router.push('/login?next=' + encodeURIComponent(`/docs/${doc.id}`));
    } else {
      setModalError('Delete failed — try again.');
    }
  }, [doc.id, doc.title, router]);

  const isPublic = visibility === 'public';
  const overLimit = saveState.kind === 'too-large';

  return (
    <div className="flex h-full flex-col">
      <EditorToolbar
        saveState={saveState}
        visibility={visibility}
        folderPath={doc.path}
        onPublish={onPublish}
        onOpenUnpublish={() => {
          setModalError(null);
          setShowUnpublishModal(true);
        }}
        onOpenDelete={() => {
          setModalError(null);
          setShowDeleteModal(true);
        }}
        canPublish={!publishing && !overLimit}
        publishing={publishing}
      />

      {isPublic && (
        <a
          href={`/docs/${doc.id}?as=reader`}
          target="_blank"
          rel="noreferrer noopener"
          className="border-b border-border bg-bg px-[28px] py-[4px] text-small text-accent hover:text-accent-hover"
        >
          &rarr; View public: /docs/{doc.id}
        </a>
      )}

      <div className="relative flex-1 overflow-y-auto bg-bg">
        {showPublishedToast && (
          <div
            role="status"
            className="pointer-events-none fixed right-[28px] top-[140px] z-30 flex items-center gap-md rounded-md border border-success bg-success-soft px-md py-sm text-small text-success shadow-pop"
          >
            <span>Published as /docs/{doc.id.slice(0, 8)}…</span>
            <a
              href={`/docs/${doc.id}?as=reader`}
              target="_blank"
              rel="noreferrer noopener"
              className="pointer-events-auto font-semibold underline"
            >
              View public
            </a>
          </div>
        )}

        <div className="mx-auto w-full max-w-[720px] px-md py-xl">
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Untitled"
            aria-label="Document title"
            className="w-full bg-transparent text-h1 text-text placeholder:text-text-subtle focus:outline-none"
          />
          <div className="mt-lg">
            <BlockNoteEditor initialBody={doc.body} onChange={setBody} />
          </div>
        </div>
      </div>

      <ConfirmModal
        open={showUnpublishModal}
        title="Unpublish this document?"
        body="It becomes private — only you can see it. The UUID is retained, so re-publishing later reuses the same /docs/{id} URL (no broken links)."
        confirmLabel="Unpublish"
        variant="secondary"
        pending={pendingModalAction}
        errorMessage={modalError}
        onConfirm={onUnpublishConfirm}
        onClose={() => setShowUnpublishModal(false)}
      />

      <ConfirmModal
        open={showDeleteModal}
        title="Delete this document?"
        body="This can't be undone. If the document is currently published, its public URL (/docs/{id}) will return 404."
        confirmLabel="Delete"
        variant="danger"
        pending={pendingModalAction}
        errorMessage={modalError}
        onConfirm={onDeleteConfirm}
        onClose={() => setShowDeleteModal(false)}
      />
    </div>
  );
}

function EditorToolbar({
  saveState,
  visibility,
  folderPath,
  onPublish,
  onOpenUnpublish,
  onOpenDelete,
  canPublish,
  publishing,
}: {
  saveState: SaveState;
  visibility: Document['visibility'];
  folderPath: string;
  onPublish: () => void;
  onOpenUnpublish: () => void;
  onOpenDelete: () => void;
  canPublish: boolean;
  publishing: boolean;
}) {
  const isPublic = visibility === 'public';
  return (
    <div className="flex flex-wrap items-center justify-between gap-md border-b border-border bg-surface-soft px-[28px] py-md">
      <SaveStatePill state={saveState} visibility={visibility} />
      <FolderPicker
        value={folderPath}
        // Read-only on edit per ADR-12 §14 + spec §6.1 — PATCH carries
        // only `title?` + `body?`. The Move action ships in M2.1.
        readOnly
        onChange={() => {
          /* no-op — read-only */
        }}
      />
      <div className="flex items-center gap-sm">
        <Button variant="ghost" onClick={onOpenDelete} aria-label="Delete document">
          <Trash2 size={14} aria-hidden="true" />
          <span>Delete</span>
        </Button>
        {isPublic && (
          <Button variant="secondary" onClick={onOpenUnpublish}>
            Unpublish
          </Button>
        )}
        <Button variant="primary" onClick={onPublish} disabled={!canPublish}>
          {publishing
            ? isPublic
              ? 'Publishing…'
              : 'Publishing…'
            : isPublic
              ? 'Publish changes'
              : 'Publish'}
        </Button>
      </div>
    </div>
  );
}

function SaveStatePill({
  state,
  visibility,
}: {
  state: SaveState;
  visibility: Document['visibility'];
}) {
  if (state.kind === 'saving') {
    return <span className="text-small text-text-muted">Saving…</span>;
  }
  if (state.kind === 'saved') {
    return (
      <Chip variant="success" dot>
        Saved
      </Chip>
    );
  }
  if (state.kind === 'too-large') {
    return <Chip variant="danger">Document too large — trim</Chip>;
  }
  if (state.kind === 'error') {
    return <Chip variant="danger">{state.message}</Chip>;
  }
  return (
    <span className="text-small text-text-muted">
      {visibility === 'public' ? 'Published' : 'Draft'}
    </span>
  );
}

