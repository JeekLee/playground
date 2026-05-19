'use client';

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react';
import { createPortal } from 'react-dom';
import {
  ChevronDown,
  Edit2,
  MessageSquare,
  MoreHorizontal,
  Plus,
  Trash2,
} from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { formatRelative, type ChatSession } from '@/entities/chat';

/**
 * ChatSessionTabs — the top tab strip per design doc §2.1 + §2.4 + §2.5
 * and spec §7.2.
 *
 * Layout:
 *  - Active tab: 160–200×32, `surface` bg + `border` 1px + `radius-md`,
 *    label text 13/600, right edge holds the `⋯` overflow trigger that
 *    surfaces on hover.
 *  - Inactive tab: text-only, text-muted 13/500, hover → surface-soft bg
 *    + text-primary.
 *  - Overflow: max 7 visible by `updatedAt DESC`; the 8th+ go behind a
 *    `▾ N more` trigger (88×32). The dropdown header reads "OLDER
 *    SESSIONS" eyebrow; rows are "<title> · <relative date>".
 *  - `+` new-tab button: 32×32 surface-soft bg + radius-md.
 *  - Tab `⋯` menu: 180×88 floating panel (surface + border + radius-md +
 *    shadow-pop). Rows: `✎ Rename`, divider, `🗑 Delete…` (danger fg).
 *  - Rename inlines: the tab itself swaps to a text input; Enter commits
 *    via the parent's `onRename`; Escape reverts.
 *
 * Click-outside dismissal on both the overflow dropdown and the ⋯ menu.
 * Keyboard: Esc closes any open overlay; ↑/↓ navigates the overflow
 * dropdown; Enter selects.
 */

const MAX_VISIBLE_TABS = 7;

export interface ChatSessionTabsProps {
  sessions: ChatSession[];
  activeSessionId: string | null;
  onSelect: (id: string) => void;
  onCreate: () => void;
  onRename: (id: string, title: string) => void;
  onDeleteRequest: (id: string) => void;
}

export function ChatSessionTabs({
  sessions,
  activeSessionId,
  onSelect,
  onCreate,
  onRename,
  onDeleteRequest,
}: ChatSessionTabsProps) {
  const [overflowOpen, setOverflowOpen] = useState(false);
  const [menuOpenFor, setMenuOpenFor] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState('');
  const overflowRef = useRef<HTMLDivElement | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);

  // Always promote the active tab into the visible window (per spec §7.2
  // "selecting one from the dropdown pulls it back to the head"). If the
  // backend sorts by `updatedAt DESC` and the active session was just
  // bumped, it naturally lands in front; otherwise we force it.
  const { visible, overflow } = useMemo(() => {
    const sorted = [...sessions].sort(
      (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
    );
    const activeIdx = sorted.findIndex((s) => s.id === activeSessionId);
    if (activeIdx >= MAX_VISIBLE_TABS) {
      const active = sorted.splice(activeIdx, 1)[0]!;
      sorted.unshift(active);
    }
    return {
      visible: sorted.slice(0, MAX_VISIBLE_TABS),
      overflow: sorted.slice(MAX_VISIBLE_TABS),
    };
  }, [sessions, activeSessionId]);

  const closeAllOverlays = useCallback(() => {
    setOverflowOpen(false);
    setMenuOpenFor(null);
  }, []);

  // Click-outside.
  useEffect(() => {
    if (!overflowOpen && !menuOpenFor) return;
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      if (overflowRef.current && overflowRef.current.contains(target)) return;
      if (menuRef.current && menuRef.current.contains(target)) return;
      closeAllOverlays();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [overflowOpen, menuOpenFor, closeAllOverlays]);

  // Esc.
  useEffect(() => {
    if (!overflowOpen && !menuOpenFor && !renamingId) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        closeAllOverlays();
        if (renamingId) setRenamingId(null);
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [overflowOpen, menuOpenFor, renamingId, closeAllOverlays]);

  const startRename = useCallback(
    (s: ChatSession) => {
      setRenamingId(s.id);
      setRenameDraft(s.title);
      setMenuOpenFor(null);
    },
    [],
  );

  const commitRename = useCallback(() => {
    if (!renamingId) return;
    const next = renameDraft.trim();
    const current = sessions.find((s) => s.id === renamingId)?.title;
    if (next && next !== current) {
      onRename(renamingId, next);
    }
    setRenamingId(null);
  }, [renamingId, renameDraft, sessions, onRename]);

  return (
    <div
      role="tablist"
      aria-label="Chat sessions"
      className="flex h-[52px] items-center gap-sm border-b border-border bg-bg px-[28px]"
    >
      <div className="flex min-w-0 flex-1 items-center gap-sm overflow-x-auto">
        {visible.length === 0 ? (
          <span className="text-[13px] text-text-muted">No chats yet — start a new one →</span>
        ) : (
          visible.map((s) => {
            const isActive = s.id === activeSessionId;
            const isRenaming = renamingId === s.id;
            return (
              <TabRow
                key={s.id}
                session={s}
                isActive={isActive}
                isRenaming={isRenaming}
                renameDraft={renameDraft}
                setRenameDraft={setRenameDraft}
                commitRename={commitRename}
                cancelRename={() => setRenamingId(null)}
                onMenuToggle={() =>
                  setMenuOpenFor((cur) => (cur === s.id ? null : s.id))
                }
                onSelect={() => onSelect(s.id)}
                isMenuOpen={menuOpenFor === s.id}
                menuRef={menuRef}
                onRename={() => startRename(s)}
                onDelete={() => {
                  setMenuOpenFor(null);
                  onDeleteRequest(s.id);
                }}
              />
            );
          })
        )}
      </div>

      {overflow.length > 0 && (
        <div ref={overflowRef} className="relative shrink-0">
          <button
            type="button"
            onClick={() => setOverflowOpen((prev) => !prev)}
            aria-expanded={overflowOpen}
            aria-label={`Show ${overflow.length} older chats`}
            className={cn(
              'inline-flex h-[32px] items-center gap-xs rounded-md border border-border bg-surface px-[12px] text-[12px] font-semibold text-text transition-colors duration-[140ms] hover:bg-surface-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
            )}
          >
            <ChevronDown
              size={12}
              aria-hidden="true"
              className={cn('transition-transform duration-[140ms]', overflowOpen && 'rotate-180')}
            />
            <span>{overflow.length} more</span>
          </button>
          {overflowOpen && (
            <div
              role="menu"
              aria-label="Older chats"
              className="absolute right-0 top-[40px] z-30 w-[280px] overflow-hidden rounded-md border border-border bg-surface p-md shadow-pop"
            >
              <p className="mb-sm text-eyebrow text-text-muted">Older sessions</p>
              <ul className="flex flex-col gap-[2px]">
                {overflow.map((s) => (
                  <li key={s.id}>
                    <button
                      type="button"
                      onClick={() => {
                        onSelect(s.id);
                        setOverflowOpen(false);
                      }}
                      className="flex w-full items-center justify-between gap-md rounded-sm px-sm py-[6px] text-left transition-colors duration-[140ms] hover:bg-surface-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-accent"
                    >
                      <span className="truncate text-[13px] font-medium text-text">{s.title}</span>
                      <span className="shrink-0 font-mono text-[11px] text-text-muted">
                        {formatRelative(s.updatedAt)}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      <button
        type="button"
        onClick={onCreate}
        aria-label="New chat"
        className="inline-flex h-[32px] w-[32px] shrink-0 items-center justify-center rounded-md bg-surface-soft text-text transition-colors duration-[140ms] hover:bg-accent-soft hover:text-accent focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
      >
        <Plus size={15} aria-hidden="true" />
      </button>
    </div>
  );
}

/**
 * Per-tab row. The {@code ⋯} menu is rendered into a body-level portal
 * with absolute coordinates anchored to the tab — necessary because the
 * tab-strip's scroll wrapper has {@code overflow-x: auto}, which (per
 * CSS spec) forces overflow-y to {@code auto} as well and clips any
 * descendant whose absolute box would extend below the strip. The
 * portal escapes the clipping while keeping the open/close state owned
 * by the parent.
 */
function TabRow({
  session,
  isActive,
  isRenaming,
  renameDraft,
  setRenameDraft,
  commitRename,
  cancelRename,
  onMenuToggle,
  onSelect,
  isMenuOpen,
  menuRef,
  onRename,
  onDelete,
}: {
  session: ChatSession;
  isActive: boolean;
  isRenaming: boolean;
  renameDraft: string;
  setRenameDraft: (text: string) => void;
  commitRename: () => void;
  cancelRename: () => void;
  onMenuToggle: () => void;
  onSelect: () => void;
  isMenuOpen: boolean;
  menuRef: RefObject<HTMLDivElement | null>;
  onRename: () => void;
  onDelete: () => void;
}) {
  const anchorRef = useRef<HTMLDivElement | null>(null);
  return (
    <div ref={anchorRef} className="relative flex shrink-0 items-center">
      {isActive ? (
        <ActiveTab
          session={session}
          isRenaming={isRenaming}
          renameDraft={renameDraft}
          setRenameDraft={setRenameDraft}
          commitRename={commitRename}
          cancelRename={cancelRename}
          onMenuToggle={onMenuToggle}
        />
      ) : (
        <InactiveTab session={session} onClick={onSelect} />
      )}
      {isMenuOpen && (
        <TabActionsMenu anchorRef={anchorRef} menuRef={menuRef}>
          <button
            type="button"
            role="menuitem"
            onClick={onRename}
            className="flex w-full items-center gap-sm px-md py-sm text-left text-[13px] font-medium text-text transition-colors duration-[140ms] hover:bg-surface-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-accent"
          >
            <Edit2 size={13} aria-hidden="true" />
            <span>Rename</span>
          </button>
          <div role="separator" className="h-px bg-border" />
          <button
            type="button"
            role="menuitem"
            onClick={onDelete}
            className="flex w-full items-center gap-sm px-md py-sm text-left text-[13px] font-medium text-danger transition-colors duration-[140ms] hover:bg-danger-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-danger"
          >
            <Trash2 size={13} aria-hidden="true" />
            <span>Delete…</span>
          </button>
        </TabActionsMenu>
      )}
    </div>
  );
}

function TabActionsMenu({
  anchorRef,
  menuRef,
  children,
}: {
  anchorRef: RefObject<HTMLDivElement | null>;
  menuRef: RefObject<HTMLDivElement | null>;
  children: ReactNode;
}) {
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);

  useLayoutEffect(() => {
    if (!anchorRef.current) return;
    const update = () => {
      const rect = anchorRef.current?.getBoundingClientRect();
      if (rect) {
        setPos({ top: rect.bottom + 4, left: rect.left });
      }
    };
    update();
    window.addEventListener('resize', update);
    window.addEventListener('scroll', update, true);
    return () => {
      window.removeEventListener('resize', update);
      window.removeEventListener('scroll', update, true);
    };
  }, [anchorRef]);

  if (typeof window === 'undefined' || pos === null) return null;
  return createPortal(
    <div
      ref={menuRef}
      role="menu"
      aria-label="Tab actions"
      style={{ position: 'fixed', top: pos.top, left: pos.left }}
      className="z-50 w-[180px] overflow-hidden rounded-md border border-border bg-surface shadow-pop"
    >
      {children}
    </div>,
    document.body,
  );
}

function ActiveTab({
  session,
  isRenaming,
  renameDraft,
  setRenameDraft,
  commitRename,
  cancelRename,
  onMenuToggle,
}: {
  session: ChatSession;
  isRenaming: boolean;
  renameDraft: string;
  setRenameDraft: (text: string) => void;
  commitRename: () => void;
  cancelRename: () => void;
  onMenuToggle: () => void;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  useEffect(() => {
    if (isRenaming) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  }, [isRenaming]);

  const onInputKey = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      commitRename();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      cancelRename();
    }
  };

  return (
    <div
      role="tab"
      aria-selected="true"
      className="group inline-flex h-[32px] min-w-[160px] max-w-[220px] items-center gap-sm rounded-md border border-border bg-surface pl-sm pr-[6px]"
    >
      <MessageSquare size={13} aria-hidden="true" className="shrink-0 text-accent" />
      {isRenaming ? (
        <input
          ref={inputRef}
          value={renameDraft}
          onChange={(e) => setRenameDraft(e.target.value)}
          onKeyDown={onInputKey}
          onBlur={commitRename}
          className="min-w-0 flex-1 bg-transparent text-[13px] font-semibold text-text outline-none placeholder:text-text-subtle"
          aria-label="Rename chat"
        />
      ) : (
        <span className="min-w-0 flex-1 truncate text-[13px] font-semibold text-text">
          {session.title}
        </span>
      )}
      {!isRenaming && (
        <button
          type="button"
          onClick={onMenuToggle}
          aria-label="Tab actions"
          // On hover-capable pointers (desktop) the menu trigger stays
          // hidden until the tab is hovered or the trigger itself is
          // keyboard-focused; touch devices have no :hover state, so we
          // keep it visible there — otherwise the menu is permanently
          // unreachable on mobile / tablet (spec §7.2 calls for the
          // ⋯ menu on every tab).
          className="inline-flex h-[22px] w-[22px] shrink-0 items-center justify-center rounded-sm text-text-muted opacity-100 transition-opacity duration-[140ms] hover:bg-surface-soft hover:text-text focus-visible:opacity-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent [@media(hover:hover)]:opacity-0 [@media(hover:hover)]:group-hover:opacity-100"
        >
          <MoreHorizontal size={14} aria-hidden="true" />
        </button>
      )}
    </div>
  );
}

function InactiveTab({
  session,
  onClick,
}: {
  session: ChatSession;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected="false"
      onClick={onClick}
      className="inline-flex h-[32px] max-w-[200px] items-center rounded-md px-sm text-[13px] font-medium text-text-muted transition-colors duration-[140ms] hover:bg-surface-soft hover:text-text focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
    >
      <span className="truncate">{session.title}</span>
    </button>
  );
}
