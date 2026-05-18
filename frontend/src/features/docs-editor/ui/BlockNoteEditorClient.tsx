'use client';

import { useEffect, useRef, useState } from 'react';
import { BlockNoteEditor, type Block, type PartialBlock } from '@blocknote/core';
import { BlockNoteView } from '@blocknote/mantine';
import { useCreateBlockNote } from '@blocknote/react';
import '@blocknote/core/fonts/inter.css';
import '@blocknote/mantine/style.css';
import { playgroundTheme } from '../lib/playgroundTheme';

/**
 * BlockNote editor — client-only component, dynamic-imported from the
 * sibling wrapper to keep `next build` from blowing up on
 * `ReferenceError: window is not defined` (ProseMirror touches `window`
 * at import time). See ADR-12 §3 for the mandatory pattern.
 *
 * Markdown roundtrip: the parent owns the raw MD body and passes it via
 * `initialBody`. On every block-document mutation we serialize back to
 * MD via `blocksToMarkdownLossy` and call the parent's `onChange`.
 * Persistence (PATCH) is parent's responsibility — the editor stays
 * local-only otherwise the autosave wiring would belong here, but
 * autosave is M2.1 (spec §2 P1).
 *
 * The `initialBody` prop is consumed exactly once on mount; later prop
 * changes do not re-parse the document (changing the body of an open
 * editor is not a legal flow in S1).
 */

export interface BlockNoteEditorClientProps {
  initialBody: string;
  onChange?: (markdown: string) => void;
  editable?: boolean;
}

export default function BlockNoteEditorClient({
  initialBody,
  onChange,
  editable = true,
}: BlockNoteEditorClientProps) {
  const editor = useCreateBlockNote();
  const [ready, setReady] = useState(false);
  const initialBodyRef = useRef(initialBody);

  // Parse the initial markdown once on mount. BlockNote's parser is async
  // (it touches the DOM); the parent renders an EditorSkeleton via the
  // dynamic wrapper while this resolves on first paint.
  useEffect(() => {
    let cancelled = false;
    const body = initialBodyRef.current;
    const seed: PartialBlock[] = [];
    (async () => {
      if (body && body.trim().length > 0) {
        const parsed = await editor.tryParseMarkdownToBlocks(body);
        if (cancelled) return;
        if (parsed.length > 0) {
          editor.replaceBlocks(editor.document, parsed);
        }
      } else {
        // empty doc — let BlockNote keep its default single empty block
        void seed;
      }
      if (!cancelled) setReady(true);
    })();
    return () => {
      cancelled = true;
    };
  }, [editor]);

  // Emit serialized markdown on every change, debounced via microtask so
  // we coalesce within a single tick. Heavier debouncing belongs at the
  // parent (autosave).
  useEffect(() => {
    if (!onChange) return;
    let queued = false;
    const handler = async () => {
      if (queued) return;
      queued = true;
      queueMicrotask(() => {
        queued = false;
      });
      const md = await editor.blocksToMarkdownLossy(editor.document as Block[]);
      onChange(md);
    };
    return editor.onChange(handler);
  }, [editor, onChange]);

  return (
    <div className={ready ? '' : 'opacity-0'}>
      <BlockNoteView editor={editor} editable={editable} theme={playgroundTheme} />
    </div>
  );
}

// Re-export so the wrapper can type the prop without pulling
// BlockNote internals through.
export type { BlockNoteEditor };
