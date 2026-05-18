'use client';

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { useRouter } from 'next/navigation';
import { Search as SearchIcon } from 'lucide-react';
import { Avatar } from '@/shared/ui/avatar';
import { cn } from '@/shared/lib/cn';
import { parseSnippet } from '@/shared/lib/snippet';
import { searchDocs } from '@/shared/api/docs';
import {
  authorInitials,
  formatRelative,
  type DocSearchScope,
  type SearchHit,
} from '@/entities/document';

/**
 * CommandPalette — global ⌘K / Ctrl+K overlay.
 *
 * Per design doc M2-docs.md §"⌘K search palette" + dispatch:
 *  - Listens at the window level for ⌘K / Ctrl+K. The sidebar's static
 *    search pill (S1) also opens this — see {@link useCommandPalette}.
 *  - Backdrop: page underneath dims to `color.text @ 0.30α`.
 *  - Card: 560-600px wide, anchored 96px from the top, `shadow.pop`.
 *  - Input strip: 🔍 glyph + query input + scope hint.
 *  - Results list: up to 6 rows. Each row title with `<mark>` highlights,
 *    meta `<author> · <visibility> · /docs/{id} · <relative>`.
 *  - Keyboard:
 *      ↑/↓     navigate
 *      Enter   open active row
 *      ⌘Enter  open /docs/search?q=…&scope=…
 *      Tab     toggle scope (Mine ↔ Public; only if authed)
 *      Esc     close
 *  - Empty (no query): "Type to search documents."
 *  - Empty (results): "No matches. Press ⌘↵ to open /docs/search."
 *  - 503: single row "Search is offline. Try again in a moment."
 *
 * S2 scope: ⌘K is also reachable when anonymous (community feed is a
 * public surface) — the default scope flips to `public` for anonymous
 * callers since `mine` requires a session.
 */

export interface CommandPaletteProps {
  isAuthenticated: boolean;
}

interface PaletteState {
  query: string;
  scope: DocSearchScope;
  hits: SearchHit[];
  activeIndex: number;
  loading: boolean;
  unavailable: boolean;
}

const DEBOUNCE_MS = 200;
const MAX_VISIBLE = 6;

export function CommandPalette({ isAuthenticated }: CommandPaletteProps) {
  const router = useRouter();
  const { open, close, isOpen } = useCommandPalette();
  const defaultScope: DocSearchScope = isAuthenticated ? 'mine' : 'public';

  const [state, setState] = useState<PaletteState>(() => ({
    query: '',
    scope: defaultScope,
    hits: [],
    activeIndex: 0,
    loading: false,
    unavailable: false,
  }));

  const inputRef = useRef<HTMLInputElement | null>(null);
  const cardRef = useRef<HTMLDivElement | null>(null);
  const requestSeq = useRef(0);

  // Focus the input when the palette opens; reset query on close.
  useLayoutEffect(() => {
    if (isOpen) {
      // Defer one frame so the overlay is mounted before focusing.
      const id = window.requestAnimationFrame(() => inputRef.current?.focus());
      return () => window.cancelAnimationFrame(id);
    }
    setState((s) => ({ ...s, query: '', hits: [], activeIndex: 0, loading: false }));
    return undefined;
  }, [isOpen]);

  // Debounced search.
  useEffect(() => {
    if (!isOpen) return;
    const trimmed = state.query.trim();
    if (trimmed.length === 0) {
      setState((s) => ({ ...s, hits: [], loading: false, unavailable: false }));
      return;
    }
    const handle = window.setTimeout(async () => {
      const mySeq = ++requestSeq.current;
      setState((s) => ({ ...s, loading: true }));
      const result = await searchDocs({ q: trimmed, scope: state.scope });
      if (mySeq !== requestSeq.current) return;
      if (result.kind === 'ok') {
        setState((s) => ({
          ...s,
          hits: result.value.items.slice(0, MAX_VISIBLE),
          activeIndex: 0,
          loading: false,
          unavailable: false,
        }));
      } else if (result.kind === 'service-unavailable') {
        setState((s) => ({ ...s, hits: [], loading: false, unavailable: true }));
      } else if (result.kind === 'unauthorized') {
        // Mine scope hit by anonymous — flip silently.
        setState((s) => ({ ...s, scope: 'public', loading: false }));
      } else {
        setState((s) => ({ ...s, hits: [], loading: false }));
      }
    }, DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [isOpen, state.query, state.scope]);

  // Close when the user navigates away (router won't re-mount this; we
  // listen by side-effect on the URL via popstate as a belt-and-suspenders).
  useEffect(() => {
    if (!isOpen) return;
    const onPop = () => close();
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, [isOpen, close]);

  const openHit = useCallback(
    (hit: SearchHit) => {
      router.push(`/docs/${hit.documentId}`);
      close();
    },
    [router, close],
  );

  const openInFullSearch = useCallback(() => {
    const q = state.query.trim();
    const params = new URLSearchParams();
    if (q) params.set('q', q);
    params.set('scope', state.scope);
    router.push(`/docs/search?${params.toString()}`);
    close();
  }, [router, state.query, state.scope, close]);

  // Global keyboard handler — only mounted while open. Captures ↑↓ Enter
  // ⌘Enter Tab Esc.
  useEffect(() => {
    if (!isOpen) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        close();
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setState((s) =>
          s.hits.length === 0
            ? s
            : { ...s, activeIndex: (s.activeIndex + 1) % s.hits.length },
        );
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setState((s) =>
          s.hits.length === 0
            ? s
            : {
                ...s,
                activeIndex: (s.activeIndex - 1 + s.hits.length) % s.hits.length,
              },
        );
        return;
      }
      if (e.key === 'Tab' && isAuthenticated) {
        e.preventDefault();
        setState((s) => ({ ...s, scope: s.scope === 'mine' ? 'public' : 'mine' }));
        return;
      }
      if (e.key === 'Enter') {
        if (e.metaKey || e.ctrlKey) {
          e.preventDefault();
          openInFullSearch();
          return;
        }
        const hit = state.hits[state.activeIndex];
        if (hit) {
          e.preventDefault();
          openHit(hit);
        } else if (state.query.trim().length > 0) {
          e.preventDefault();
          openInFullSearch();
        }
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [isOpen, state.hits, state.activeIndex, state.query, isAuthenticated, close, openHit, openInFullSearch]);

  if (!isOpen) {
    return null;
  }

  return (
    <div
      role="dialog"
      aria-label="Search documents"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-start justify-center px-md pt-[96px]"
      onClick={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      {/* Backdrop */}
      <div
        aria-hidden="true"
        className="absolute inset-0 bg-[rgba(42,44,32,0.30)] backdrop-blur-[1px]"
        onClick={close}
      />

      <div
        ref={cardRef}
        className="relative z-10 flex w-full max-w-[600px] flex-col overflow-hidden rounded-lg border border-border bg-surface shadow-pop"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Input strip */}
        <div className="flex items-center gap-sm border-b border-border px-md py-md">
          <SearchIcon size={16} aria-hidden="true" className="text-text-muted" />
          <input
            ref={inputRef}
            type="search"
            value={state.query}
            onChange={(e) => setState((s) => ({ ...s, query: e.target.value, activeIndex: 0 }))}
            placeholder={
              state.scope === 'mine'
                ? 'Search your documents…'
                : 'Search the community…'
            }
            aria-label="Search query"
            autoComplete="off"
            className="flex-1 bg-transparent text-body text-text placeholder:text-text-subtle focus:outline-none"
          />
          {state.loading && (
            <span
              aria-hidden="true"
              className="inline-block h-[12px] w-[12px] animate-spin rounded-pill border-2 border-border-strong border-t-accent"
            />
          )}
          <ScopeBadge scope={state.scope} authed={isAuthenticated} />
        </div>

        {/* Results */}
        <PaletteResults
          state={state}
          onActivate={(i) => setState((s) => ({ ...s, activeIndex: i }))}
          onOpen={openHit}
          onOpenFullSearch={openInFullSearch}
        />

        {/* Footer */}
        <PaletteFooter
          scope={state.scope}
          showTabHint={isAuthenticated}
          showFullSearchHint={state.query.trim().length > 0}
        />
      </div>
    </div>
  );
}

function ScopeBadge({ scope, authed }: { scope: DocSearchScope; authed: boolean }) {
  if (!authed) {
    return (
      <span className="rounded-pill bg-surface-soft px-[10px] py-[3px] text-[11px] font-medium text-text-muted">
        Public
      </span>
    );
  }
  return (
    <span
      className={cn(
        'rounded-pill px-[10px] py-[3px] text-[11px] font-medium',
        scope === 'mine' ? 'bg-accent-soft text-accent' : 'bg-surface-soft text-text-muted',
      )}
      aria-label={`Current scope: ${scope}`}
    >
      {scope === 'mine' ? 'Mine' : 'Public'}
    </span>
  );
}

interface PaletteResultsProps {
  state: PaletteState;
  onActivate: (i: number) => void;
  onOpen: (hit: SearchHit) => void;
  onOpenFullSearch: () => void;
}

function PaletteResults({
  state,
  onActivate,
  onOpen,
  onOpenFullSearch,
}: PaletteResultsProps) {
  const trimmed = state.query.trim();

  if (state.unavailable) {
    return (
      <div className="px-md py-md text-small text-text-muted">
        Search is offline. Try again in a moment.
      </div>
    );
  }
  if (trimmed.length === 0) {
    return (
      <div className="px-md py-md text-small text-text-muted">
        Type to search documents.
      </div>
    );
  }
  if (state.hits.length === 0 && !state.loading) {
    return (
      <div className="flex flex-col gap-sm px-md py-md text-small text-text-muted">
        <span>No matches.</span>
        <button
          type="button"
          onClick={onOpenFullSearch}
          className="self-start text-small font-medium text-accent hover:text-accent-hover"
        >
          Press ⌘↵ to open /docs/search &rarr;
        </button>
      </div>
    );
  }
  return (
    <ul role="listbox" aria-label="Search results" className="max-h-[420px] overflow-y-auto">
      {state.hits.map((hit, i) => (
        <PaletteRow
          key={hit.documentId}
          hit={hit}
          active={i === state.activeIndex}
          onHover={() => onActivate(i)}
          onClick={() => onOpen(hit)}
        />
      ))}
    </ul>
  );
}

function PaletteRow({
  hit,
  active,
  onHover,
  onClick,
}: {
  hit: SearchHit;
  active: boolean;
  onHover: () => void;
  onClick: () => void;
}) {
  const segments = parseSnippet(hit.title);
  const author = hit.author?.displayName ?? 'You';
  const meta = `${author} · ${hit.visibility} · /docs/${hit.documentId.slice(0, 8)}…`;
  const when = formatRelative(hit.publishedAt ?? hit.updatedAt);

  return (
    <li
      role="option"
      aria-selected={active}
      onMouseEnter={onHover}
      onClick={onClick}
      className={cn(
        'flex cursor-pointer items-center gap-md border-b border-border px-md py-sm last:border-b-0',
        active && 'bg-accent-soft',
      )}
    >
      {hit.author && <Avatar initials={authorInitials(hit.author)} size="sm" />}
      <div className="flex min-w-0 flex-1 flex-col gap-[2px]">
        <div
          className={cn(
            'truncate text-small font-semibold',
            active ? 'text-accent' : 'text-text',
          )}
        >
          {segments.length === 0
            ? hit.title
            : segments.map((seg, i) =>
                seg.type === 'mark' ? (
                  <mark
                    key={i}
                    className="rounded-sm bg-accent-soft px-[2px] text-text"
                  >
                    {seg.text}
                  </mark>
                ) : (
                  <span key={i}>{seg.text}</span>
                ),
              )}
        </div>
        <div className="truncate text-[11px] text-text-muted">
          <span>{meta}</span>
          <span aria-hidden="true"> · </span>
          <span>{when}</span>
        </div>
      </div>
    </li>
  );
}

function PaletteFooter({
  scope,
  showTabHint,
  showFullSearchHint,
}: {
  scope: DocSearchScope;
  showTabHint: boolean;
  showFullSearchHint: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-md border-t border-border bg-surface-soft px-md py-[8px] text-[11px] text-text-subtle">
      <div className="flex flex-wrap items-center gap-md">
        <FooterHint kbd="↑↓" label="navigate" />
        <FooterHint kbd="↵" label="open" />
        {showFullSearchHint && <FooterHint kbd="⌘↵" label="open in /docs/search" />}
        {showTabHint && <FooterHint kbd="Tab" label="toggle scope" />}
        <FooterHint kbd="Esc" label="close" />
      </div>
      <span aria-hidden="true" className="font-medium">
        {scope === 'mine' ? 'Mine' : 'Public'}
      </span>
    </div>
  );
}

function FooterHint({ kbd, label }: { kbd: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-xs">
      <kbd className="rounded-sm bg-surface px-[5px] py-[1px] font-mono text-[10px] text-text-muted">
        {kbd}
      </kbd>
      <span>{label}</span>
    </span>
  );
}

// -------------------------- shared hook ------------------------------------

interface PaletteController {
  isOpen: boolean;
  open: () => void;
  close: () => void;
}

let currentController: { open: () => void; close: () => void } | null = null;

/**
 * Hook + module-scoped controller — gives any client component a hook
 * into "open the global ⌘K palette." The {@link CommandPalette} component
 * itself registers as the active controller on mount; consumers
 * (sidebar pill, account-pill-style global helpers) call
 * `useCommandPaletteController().open()`.
 *
 * The pattern intentionally avoids a React Context because the palette
 * sits at the layout root and consumers may live anywhere in the tree.
 * A module-scoped imperative handle is the simplest contract that
 * preserves SSR friendliness and avoids prop drilling.
 */
export function useCommandPalette(): PaletteController {
  const [isOpen, setIsOpen] = useState(false);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);

  useEffect(() => {
    // Register this instance as the live controller.
    currentController = { open, close };
    return () => {
      if (currentController?.open === open) {
        currentController = null;
      }
    };
  }, [open, close]);

  // Global keyboard shortcut — ⌘K / Ctrl+K opens (or closes) the palette.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setIsOpen((v) => !v);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  return { isOpen, open, close };
}

/**
 * Imperative open-the-palette helper for components outside the palette
 * itself (e.g. the sidebar's static search pill). Safe to call from any
 * client component — no-op if the palette isn't mounted (anonymous
 * route, etc).
 */
export function openCommandPalette(): void {
  currentController?.open();
}
