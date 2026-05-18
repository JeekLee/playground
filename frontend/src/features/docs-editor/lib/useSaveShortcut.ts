import { useEffect } from 'react';

/**
 * Window-level ⌘+S / Ctrl+S handler for the BlockNote editor surfaces
 * (`/docs/new`, `/docs/{id}` author view). Prevents the browser's default
 * "Save page" dialog and invokes the parent's synchronous save callback —
 * complements the debounced auto-save loop per design doc M2-docs.md §"New
 * document (editor)" line "⌘+S or autosave triggers PATCH /api/docs/{id}".
 *
 * No-ops when `enabled === false` so caller can gate on too-large or
 * publishing states.
 */
export function useSaveShortcut(handler: () => void, enabled = true): void {
    useEffect(() => {
        if (!enabled) return;
        const listener = (event: KeyboardEvent) => {
            // metaKey on macOS, ctrlKey elsewhere. Cross-platform without UA sniff.
            if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
                event.preventDefault();
                handler();
            }
        };
        window.addEventListener('keydown', listener);
        return () => window.removeEventListener('keydown', listener);
    }, [enabled, handler]);
}
