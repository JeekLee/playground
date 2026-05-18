'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Button } from '@/shared/ui/button';
import { Chip } from '@/shared/ui/chip';
import { BlockNoteEditor, useSaveShortcut } from '@/features/docs-editor';
import { FolderPicker } from '@/features/folder-picker';
import {
  bodyByteSize,
  createDocument,
  MAX_BODY_BYTES,
  patchDocument,
  publishDocument,
} from '@/shared/api/docs';
import { normalizeFolderPath } from '@/entities/document';

/**
 * `/docs/new` — new-document editor per design doc §"New document
 * (editor)". S1 scope is the minimum-viable surface:
 *  - editor toolbar: save-state pill (left) + folder picker pill (center,
 *    read-only in S1 — the actual folder picker overlay lands with S3) +
 *    primary `Publish` button (right)
 *  - BlockNote editor surface centered at 720px width
 *
 * Flow:
 *  1. The page starts with no `docId` and an empty body.
 *  2. The first time the user types meaningful content (a non-empty title
 *     OR a non-empty body), we POST to `/api/docs` to materialize the row.
 *     The server returns the freshly-minted UUID; we hold onto it and
 *     switch the save loop to PATCH thereafter.
 *  3. Subsequent edits trigger PATCH on a 1.5s debounce. Save-state pill
 *     reflects in-flight, success, and failure.
 *  4. `Publish` calls `POST /api/docs/{id}/publish` if a row exists; if
 *     not (user clicks Publish before typing anything substantive) the
 *     button stays disabled.
 *
 * The 1 MB body cap (ADR-12 §4) is enforced client-side: oversize bodies
 * never leave the browser.
 */

type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved'; at: number }
  | { kind: 'error'; message: string }
  | { kind: 'too-large' };

const SAVE_DEBOUNCE_MS = 1500;

export function DocNewPage() {
  const router = useRouter();
  const search = useSearchParams();
  const initialPath = normalizeFolderPath(search?.get('path'));

  const [docId, setDocId] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  // Folder path is mutable until the first save materializes the doc.
  // After that, the picker switches to read-only (PATCH is title+body
  // only per spec §6.1 + ADR-12 §14 — the move action lands in M2.1).
  const [folderPath, setFolderPath] = useState<string>(initialPath);
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });
  const [publishing, setPublishing] = useState(false);

  // Guard: prevent re-entry of "first save" while a POST is in flight.
  const [creating, setCreating] = useState(false);

  // Tracks the (title, body) snapshot that was last persisted to the server.
  // The debounced save loop + ⌘+S shortcut both compare against this so we
  // never round-trip when nothing has changed (auto-save was firing every
  // 1.5s even on idle because setSaveState reruns the effect; this prevents
  // those no-op PATCH calls).
  const lastSavedRef = useRef<{ title: string; body: string }>({ title: '', body: '' });

  const persist = useCallback(
    async (nextTitle: string, nextBody: string, currentId: string | null) => {
      // 1 MB cap.
      if (bodyByteSize(nextBody) > MAX_BODY_BYTES) {
        setSaveState({ kind: 'too-large' });
        return currentId;
      }
      // Nothing meaningful yet — skip.
      const hasContent = nextTitle.trim().length > 0 || nextBody.trim().length > 0;
      if (!currentId && !hasContent) return currentId;

      setSaveState({ kind: 'saving' });

      if (!currentId) {
        if (creating) return currentId;
        setCreating(true);
        const created = await createDocument({
          title: nextTitle.trim().length > 0 ? nextTitle.trim() : 'Untitled',
          body: nextBody,
          path: folderPath,
        });
        setCreating(false);
        if (created.kind === 'ok') {
          setSaveState({ kind: 'saved', at: Date.now() });
          setDocId(created.value.id);
          return created.value.id;
        }
        setSaveState({
          kind: 'error',
          message:
            created.kind === 'unauthorized'
              ? 'Session expired — sign in again.'
              : created.kind === 'too-large'
                ? 'Document is too large.'
                : 'Save failed — retry',
        });
        if (created.kind === 'unauthorized') {
          router.push('/login?next=' + encodeURIComponent('/docs/new'));
        }
        return currentId;
      }

      const patched = await patchDocument(currentId, {
        title: nextTitle.trim().length > 0 ? nextTitle.trim() : undefined,
        body: nextBody,
      });
      if (patched.kind === 'ok') {
        lastSavedRef.current = { title: nextTitle, body: nextBody };
        setSaveState({ kind: 'saved', at: Date.now() });
      } else if (patched.kind === 'too-large') {
        setSaveState({ kind: 'too-large' });
      } else {
        setSaveState({ kind: 'error', message: 'Save failed — retry' });
      }
      return currentId;
    },
    [creating, folderPath, router],
  );

  // After a successful first-create POST, remember the snapshot so the
  // debounced loop doesn't immediately re-fire as a no-op PATCH.
  useEffect(() => {
    if (docId && saveState.kind === 'saved') {
      lastSavedRef.current = { title, body };
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional, only on save success
  }, [docId, saveState.kind]);

  // Debounced save loop. Only fires when (title, body) actually changed
  // since the last successful save — prevents idle no-op PATCH every 1.5s.
  useEffect(() => {
    if (saveState.kind === 'too-large') return;
    const hasContent = title.trim().length > 0 || body.trim().length > 0;
    if (!hasContent && !docId) return;
    if (
      lastSavedRef.current.title === title &&
      lastSavedRef.current.body === body
    ) {
      return;
    }
    const handle = window.setTimeout(() => {
      void persist(title, body, docId);
    }, SAVE_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [body, docId, persist, saveState.kind, title]);

  // ⌘+S / Ctrl+S immediate save — same no-op guard as the debounced loop.
  useSaveShortcut(
    useCallback(() => {
      const hasContent = title.trim().length > 0 || body.trim().length > 0;
      if (!hasContent && !docId) return;
      if (
        lastSavedRef.current.title === title &&
        lastSavedRef.current.body === body
      ) {
        return;
      }
      void persist(title, body, docId);
    }, [body, docId, persist, title]),
    saveState.kind !== 'too-large' && !publishing,
  );

  const onPublish = useCallback(async () => {
    // Always run a final synchronous save first so the published row
    // matches what's in the editor.
    if (publishing) return;
    setPublishing(true);
    let id = docId;
    if (!id) {
      const created = await createDocument({
        title: title.trim().length > 0 ? title.trim() : 'Untitled',
        body,
        path: folderPath,
      });
      if (created.kind !== 'ok') {
        setPublishing(false);
        setSaveState({ kind: 'error', message: 'Save failed — retry' });
        return;
      }
      id = created.value.id;
      setDocId(id);
    } else {
      await patchDocument(id, { title: title.trim() || undefined, body });
    }
    const published = await publishDocument(id);
    setPublishing(false);
    if (published.kind === 'ok') {
      router.push(`/docs/${id}?published=1`);
    } else {
      setSaveState({ kind: 'error', message: 'Publish failed — retry' });
    }
  }, [body, docId, folderPath, publishing, router, title]);

  const canPublish =
    !publishing &&
    (title.trim().length > 0 || body.trim().length > 0) &&
    saveState.kind !== 'too-large';

  // Once the doc is materialized, path is committed and the picker is
  // read-only (PATCH carries only `title?` + `body?` per spec §6.1).
  const pickerReadOnly = docId !== null;

  return (
    <div className="flex h-full flex-col">
      <EditorToolbar
        saveState={saveState}
        folderPath={folderPath}
        pickerReadOnly={pickerReadOnly}
        onChangeFolderPath={setFolderPath}
        onPublish={onPublish}
        canPublish={canPublish}
        publishing={publishing}
      />
      <div className="flex-1 overflow-y-auto bg-bg">
        <div className="mx-auto w-full max-w-[1100px] px-md py-xl">
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Untitled"
            aria-label="Document title"
            className="w-full bg-transparent text-h1 text-text placeholder:text-text-subtle focus:outline-none"
          />
          <div className="mt-lg">
            <BlockNoteEditor initialBody="" onChange={setBody} />
          </div>
        </div>
      </div>
    </div>
  );
}

function EditorToolbar({
  saveState,
  folderPath,
  pickerReadOnly,
  onChangeFolderPath,
  onPublish,
  canPublish,
  publishing,
}: {
  saveState: SaveState;
  folderPath: string;
  pickerReadOnly: boolean;
  onChangeFolderPath: (path: string) => void;
  onPublish: () => void;
  canPublish: boolean;
  publishing: boolean;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-md border-b border-border bg-surface-soft px-[28px] py-md">
      <SaveStatePill state={saveState} />
      <FolderPicker
        value={folderPath}
        onChange={onChangeFolderPath}
        readOnly={pickerReadOnly}
      />
      <Button variant="primary" onClick={onPublish} disabled={!canPublish}>
        {publishing ? 'Publishing…' : 'Publish'}
      </Button>
    </div>
  );
}

function SaveStatePill({ state }: { state: SaveState }) {
  if (state.kind === 'idle') {
    return <span className="text-small text-text-muted">Not saved yet</span>;
  }
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
    return (
      <Chip variant="danger">
        Document too large — trim
      </Chip>
    );
  }
  return <Chip variant="danger">{state.message}</Chip>;
}
