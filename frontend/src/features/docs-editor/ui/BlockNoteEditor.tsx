'use client';

import dynamic from 'next/dynamic';
import { EditorSkeleton } from './EditorSkeleton';

/**
 * SSR-safe wrapper for the BlockNote editor — per ADR-12 §3 the actual
 * editor file imports `@blocknote/react` (which transitively pulls in
 * Tiptap + ProseMirror, both of which touch `window` at module load).
 * Routing the import through `next/dynamic({ ssr: false })` keeps the
 * server build from blowing up.
 *
 * The skeleton matches the editor's outer dimensions to prevent layout
 * shift on first paint.
 */

const BlockNoteEditorClient = dynamic(() => import('./BlockNoteEditorClient'), {
  ssr: false,
  loading: () => <EditorSkeleton />,
});

export interface BlockNoteEditorProps {
  initialBody: string;
  onChange?: (markdown: string) => void;
  editable?: boolean;
}

export function BlockNoteEditor(props: BlockNoteEditorProps) {
  return <BlockNoteEditorClient {...props} />;
}
