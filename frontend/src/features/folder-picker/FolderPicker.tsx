'use client';

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { ChevronDown, Folder, Plus } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import {
  buildFolderTree,
  flattenFolderTree,
  normalizeFolderPath,
  ROOT_PATH,
  type FolderListItem,
} from '@/entities/document';
import { fetchFolders } from '@/shared/api/docs';

/**
 * FolderPicker — the toolbar pill on `/docs/new` (and the read-only
 * variant on `/docs/{id}` editor).
 *
 * Per design doc M2-docs.md §"New document (editor)" §"Middle (new in
 * v5)":
 *   "folder picker pill — surface bg + border 1px stroke + radius.pill
 *    999, padding 8px 14px, label '📁 /build-log ▾' (12px text.muted)."
 *
 * The picker is interactive at create time only. On `/docs/{id}` (edit)
 * it is rendered read-only (no chevron, subtler `text.subtle` label) —
 * per ADR-12 §14 + spec §6.1, PATCH accepts only `{title?, body?}` and
 * the move action ships in M2.1.
 *
 * The dropdown loads the caller's existing folders (`GET /api/docs/
 * folders`) once on first open, with a memoized fallback if the load
 * is in flight. The user can:
 *  - filter by typing (case-insensitive substring match on the path)
 *  - pick an existing folder
 *  - type a new path and pick "Create '<path>'" (in-memory only —
 *    the path materializes on the server when the doc is created)
 */

export interface FolderPickerProps {
  /** Current selection (canonical path; defaults to root `/`). */
  value: string;
  /** Called when the user picks a different folder. */
  onChange: (path: string) => void;
  /** When true, renders the pill as a static label. */
  readOnly?: boolean;
  /**
   * Optional pre-loaded folder list. When omitted the picker fetches
   * on first open. Passed by `/docs/new` SSR so the dropdown opens
   * with the right list immediately.
   */
  initialFolders?: FolderListItem[];
  className?: string;
}

const PATH_RE = /^(\/[a-z0-9][a-z0-9-]*)+\/$/;

export function FolderPicker({
  value,
  onChange,
  readOnly = false,
  initialFolders,
  className,
}: FolderPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [folders, setFolders] = useState<FolderListItem[] | null>(
    initialFolders ?? null,
  );
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const popoverRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);

  const normalizedValue = normalizeFolderPath(value);

  // Lazy-load on first open so the read-only variant never burns the
  // round-trip.
  useEffect(() => {
    if (!open || readOnly) return;
    if (folders !== null || loading) return;
    setLoading(true);
    void fetchFolders().then((result) => {
      setLoading(false);
      if (result.kind === 'ok') {
        setFolders(result.value.items);
      }
    });
  }, [folders, loading, open, readOnly]);

  // Auto-focus the input when the popover opens.
  useEffect(() => {
    if (!open) return;
    const handle = window.setTimeout(() => inputRef.current?.focus(), 30);
    return () => window.clearTimeout(handle);
  }, [open]);

  // Click-outside + Esc dismiss.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      const target = e.target as Node | null;
      if (
        target &&
        !popoverRef.current?.contains(target) &&
        !triggerRef.current?.contains(target)
      ) {
        setOpen(false);
      }
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

  // Build the flat sorted list of selectable paths.
  const candidates = useMemo<FolderListItem[]>(() => {
    const base = folders ?? [];
    // Always surface root as a target even when the folder list omits it.
    const rootEntry: FolderListItem = {
      path: ROOT_PATH,
      count: base.find((f) => normalizeFolderPath(f.path) === ROOT_PATH)?.count ?? 0,
    };
    const seen = new Set<string>([ROOT_PATH]);
    const normalized: FolderListItem[] = [rootEntry];
    // Flatten the tree so synthetic parents (e.g. `/agents/` when only
    // `/agents/build-log/` is in the response) show up too, otherwise
    // the user can't pick them.
    const tree = buildFolderTree(base);
    for (const node of flattenFolderTree(tree)) {
      const canon = normalizeFolderPath(node.path);
      if (seen.has(canon)) continue;
      seen.add(canon);
      normalized.push({ path: canon, count: node.count });
    }
    return normalized;
  }, [folders]);

  const filtered = useMemo(() => {
    if (!query.trim()) return candidates;
    const q = query.trim().toLowerCase();
    return candidates.filter((c) => c.path.toLowerCase().includes(q));
  }, [candidates, query]);

  // "Create new" affordance: when the query parses as a valid path AND
  // it doesn't already exist in `candidates`, offer to pick it.
  const createCandidate = useMemo<string | null>(() => {
    if (readOnly) return null;
    const trimmed = query.trim();
    if (!trimmed) return null;
    const canon = normalizeFolderPath(trimmed.toLowerCase().replace(/\s+/g, '-'));
    if (canon === ROOT_PATH) return null;
    if (!PATH_RE.test(canon)) return null;
    if (candidates.some((c) => c.path === canon)) return null;
    return canon;
  }, [candidates, query, readOnly]);

  const select = useCallback(
    (path: string) => {
      onChange(normalizeFolderPath(path));
      setOpen(false);
      setQuery('');
    },
    [onChange],
  );

  if (readOnly) {
    return (
      <span
        aria-disabled="true"
        className={cn(
          'inline-flex items-center gap-sm rounded-pill border border-border bg-surface px-md py-[6px] text-small text-text-subtle',
          className,
        )}
        title="Folder picker is read-only on existing docs (Move action ships in M2.1)"
      >
        <Folder size={13} aria-hidden="true" />
        <span className="font-mono">{labelFor(normalizedValue)}</span>
      </span>
    );
  }

  return (
    <div className={cn('relative inline-flex', className)}>
      <button
        ref={triggerRef}
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        aria-haspopup="listbox"
        className={cn(
          'inline-flex items-center gap-sm rounded-pill border bg-surface px-md py-[6px] text-small transition-colors duration-[140ms]',
          'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
          open
            ? 'border-accent text-accent'
            : 'border-border text-text-muted hover:border-border-strong hover:text-text',
        )}
      >
        <Folder size={13} aria-hidden="true" />
        <span className="font-mono">{labelFor(normalizedValue)}</span>
        <ChevronDown
          size={12}
          aria-hidden="true"
          className={cn(
            'transition-transform duration-[140ms]',
            open && 'rotate-180',
          )}
        />
      </button>

      {open && (
        <div
          ref={popoverRef}
          role="dialog"
          aria-label="Choose a folder"
          className="absolute left-1/2 top-[calc(100%+8px)] z-30 w-[280px] -translate-x-1/2 overflow-hidden rounded-md border border-border bg-surface shadow-pop"
        >
          <div className="border-b border-border bg-surface-soft px-sm py-xs">
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search or type a new folder…"
              aria-label="Folder name"
              className="w-full bg-transparent text-small text-text placeholder:text-text-subtle focus:outline-none"
            />
          </div>
          <ul
            role="listbox"
            aria-label="Folders"
            className="max-h-[260px] overflow-y-auto py-xs"
          >
            {loading && (
              <li className="px-md py-sm text-small text-text-subtle">
                Loading folders…
              </li>
            )}
            {!loading &&
              filtered.length === 0 &&
              !createCandidate && (
                <li className="px-md py-sm text-small text-text-subtle">
                  No matching folders. Type a valid path like{' '}
                  <span className="font-mono">/agents/build-log/</span>.
                </li>
              )}
            {!loading &&
              filtered.map((candidate) => {
                const active = candidate.path === normalizedValue;
                return (
                  <li key={candidate.path}>
                    <button
                      type="button"
                      role="option"
                      aria-selected={active}
                      onClick={() => select(candidate.path)}
                      className={cn(
                        'flex w-full items-center justify-between gap-sm px-md py-[6px] text-left text-small transition-colors duration-[140ms]',
                        active
                          ? 'bg-accent-soft font-semibold text-accent'
                          : 'text-text hover:bg-surface-soft',
                      )}
                    >
                      <span className="inline-flex items-center gap-sm">
                        <Folder size={12} aria-hidden="true" className="text-text-subtle" />
                        <span className="font-mono text-[12px]">
                          {labelFor(candidate.path)}
                        </span>
                      </span>
                      <span
                        className={cn(
                          'font-mono text-[11px]',
                          active ? 'text-accent' : 'text-text-subtle',
                        )}
                      >
                        {candidate.count}
                      </span>
                    </button>
                  </li>
                );
              })}
            {createCandidate && (
              <li>
                <button
                  type="button"
                  role="option"
                  aria-selected={false}
                  onClick={() => select(createCandidate)}
                  className="flex w-full items-center gap-sm px-md py-[6px] text-left text-small text-accent transition-colors duration-[140ms] hover:bg-accent-soft"
                >
                  <Plus size={12} aria-hidden="true" />
                  <span>
                    Create{' '}
                    <span className="font-mono text-[12px]">{createCandidate}</span>
                  </span>
                </button>
              </li>
            )}
          </ul>
          <p className="border-t border-border bg-surface-soft px-md py-[6px] text-[11px] text-text-subtle">
            Folder is applied at create time. <span className="font-mono">{'Esc'}</span>{' '}
            cancels.
          </p>
        </div>
      )}
    </div>
  );
}

function labelFor(path: string): string {
  if (path === ROOT_PATH) return '/';
  return path.replace(/^\/+|\/+$/g, '');
}
