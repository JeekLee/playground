'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { ChevronDown, ChevronRight, FolderOpen, Folder } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import {
  buildFolderTree,
  flattenFolderTree,
  ROOT_PATH,
  totalDocCount,
  type FolderListItem,
  type FolderTreeNode,
} from '@/entities/document';

/**
 * FolderTree — left pane on `/docs/mine`. Per design doc M2-docs.md
 * §"My documents" §Key elements:
 *
 *  STATUS  (eyebrow)
 *    All       N  (active = accent-soft bg + accent label)
 *    Drafts    N
 *    Published N
 *
 *  FOLDERS (eyebrow)
 *    ▸ /                       (root, selectable)
 *    ▾ agents                  (expanded)
 *      ▾ build-log    5        (active row, accent-soft bg)
 *        · m1-cycle   2
 *      ▸ spec-notes
 *
 * Visual rhythm (slim 1px borders, `surface` card, `surface.soft` hover,
 * `accent.soft` active) mirrors the existing chrome tokens — no new
 * design language introduced. The pane fixes its width at 220px so the
 * list-pane reflow is predictable across breakpoints.
 *
 * The tree carries only the `path` filter; the parent owns the
 * `status` filter (UI-only client-side filter on top of the path scope,
 * since the backend list endpoint doesn't filter by visibility in M2).
 */

export type StatusFilter = 'all' | 'drafts' | 'published';

export interface FolderTreeProps {
  folders: FolderListItem[];
  /** Currently-selected folder path (canonical form, e.g. `'/agents/'`). */
  activePath: string;
  /** Currently-selected status filter. */
  activeStatus: StatusFilter;
  /**
   * Counts driving the STATUS section. Resolved by the parent from the
   * already-loaded list pane so the badge numbers match the rows
   * actually rendered.
   */
  statusCounts: {
    all: number;
    drafts: number;
    published: number;
  };
  /** Called when the user picks a different folder. */
  onSelectPath: (path: string) => void;
  /** Called when the user picks a different status filter. */
  onSelectStatus: (status: StatusFilter) => void;
}

export function FolderTree({
  folders,
  activePath,
  activeStatus,
  statusCounts,
  onSelectPath,
  onSelectStatus,
}: FolderTreeProps) {
  const tree = useMemo(() => buildFolderTree(folders), [folders]);
  const total = useMemo(() => totalDocCount(folders), [folders]);

  // Auto-expand every ancestor of the active path so the active row is
  // always visible when the user lands. Below that, the user toggles
  // expansion freely.
  const initialExpanded = useMemo(() => {
    const expanded = new Set<string>([ROOT_PATH]);
    let cursor = activePath;
    while (cursor && cursor !== ROOT_PATH) {
      expanded.add(cursor);
      const segments = cursor.split('/').filter(Boolean);
      segments.pop();
      cursor = segments.length === 0 ? ROOT_PATH : `/${segments.join('/')}/`;
    }
    return expanded;
  }, [activePath]);

  const [expanded, setExpanded] = useState<Set<string>>(initialExpanded);

  const toggle = (path: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  };

  const flat = useMemo(() => filteredFlatten(tree, expanded), [tree, expanded]);

  return (
    <aside
      className="flex w-[220px] flex-shrink-0 flex-col gap-md self-start rounded-md border border-border bg-surface px-sm py-md shadow-card"
      aria-label="Folder tree"
    >
      <section className="flex flex-col gap-xs">
        <p className="px-sm text-eyebrow text-text-subtle">Status</p>
        <ul className="flex flex-col gap-[2px]">
          <StatusRow
            label="All"
            count={statusCounts.all}
            active={activeStatus === 'all'}
            onSelect={() => onSelectStatus('all')}
          />
          <StatusRow
            label="Drafts"
            count={statusCounts.drafts}
            active={activeStatus === 'drafts'}
            onSelect={() => onSelectStatus('drafts')}
          />
          <StatusRow
            label="Published"
            count={statusCounts.published}
            active={activeStatus === 'published'}
            onSelect={() => onSelectStatus('published')}
          />
        </ul>
      </section>

      <div className="mx-sm border-t border-border" aria-hidden="true" />

      <section className="flex flex-col gap-xs">
        <div className="flex items-center justify-between px-sm">
          <p className="text-eyebrow text-text-subtle">Folders</p>
          <span className="font-mono text-[10px] text-text-subtle">{total}</span>
        </div>
        {folders.length === 0 ? (
          <p className="px-sm text-small text-text-subtle">
            No folders yet — every doc lives at <span className="font-mono">/</span>.
          </p>
        ) : (
          <ul className="flex flex-col gap-[2px]">
            {flat.map((node) => (
              <FolderRow
                key={node.path}
                node={node}
                active={node.path === activePath}
                expanded={expanded.has(node.path)}
                onSelect={() => onSelectPath(node.path)}
                onToggle={() => toggle(node.path)}
              />
            ))}
          </ul>
        )}
      </section>

      {/* Read-only hint card — keeps the pane from collapsing visually
          and surfaces the M2 P0 constraint per design doc §"New document
          (editor)" + ADR-12 §14 (path is set at create time only). */}
      <div className="mx-sm rounded-sm border border-dashed border-border bg-surface-soft px-sm py-xs text-[11px] leading-snug text-text-subtle">
        Move action ships in M2.1. Set folder at create time.
      </div>
    </aside>
  );
}

function StatusRow({
  label,
  count,
  active,
  onSelect,
}: {
  label: string;
  count: number;
  active: boolean;
  onSelect: () => void;
}) {
  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        aria-pressed={active}
        className={cn(
          'flex w-full items-center justify-between rounded-sm px-sm py-[5px] text-small transition-colors duration-[140ms]',
          active
            ? 'bg-accent-soft font-semibold text-accent'
            : 'text-text hover:bg-surface-soft',
        )}
      >
        <span>{label}</span>
        <span
          className={cn(
            'font-mono text-[11px]',
            active ? 'text-accent' : 'text-text-subtle',
          )}
        >
          {count}
        </span>
      </button>
    </li>
  );
}

function FolderRow({
  node,
  active,
  expanded,
  onSelect,
  onToggle,
}: {
  node: FolderTreeNode;
  active: boolean;
  expanded: boolean;
  onSelect: () => void;
  onToggle: () => void;
}) {
  const hasChildren = node.children.length > 0;
  const isRoot = node.path === ROOT_PATH;
  const Caret = expanded ? ChevronDown : ChevronRight;
  // Synthetic intermediate nodes (count=0, no backing row) are still
  // selectable — the parent page handles the empty list with an empty-
  // state per the design doc. So we don't disable them.
  return (
    <li>
      <div
        className={cn(
          'group flex items-center rounded-sm pr-sm transition-colors duration-[140ms]',
          active
            ? 'bg-accent-soft text-accent'
            : 'hover:bg-surface-soft',
        )}
        style={{ paddingLeft: `${4 + node.depth * 10}px` }}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={onToggle}
            aria-label={expanded ? `Collapse ${node.label}` : `Expand ${node.label}`}
            className="flex h-[20px] w-[14px] items-center justify-center rounded-[3px] text-text-subtle hover:text-text"
          >
            <Caret size={11} aria-hidden="true" />
          </button>
        ) : (
          <span className="inline-block h-[20px] w-[14px]" aria-hidden="true" />
        )}
        <button
          type="button"
          onClick={onSelect}
          aria-current={active ? 'true' : undefined}
          className={cn(
            'flex flex-1 items-center gap-xs px-xs py-[5px] text-left text-small',
            active ? 'font-semibold' : 'text-text',
          )}
        >
          {isRoot ? (
            <FolderOpen size={12} aria-hidden="true" className="text-text-subtle" />
          ) : (
            <Folder size={12} aria-hidden="true" className="text-text-subtle" />
          )}
          <span className="truncate font-mono text-[12px]">
            {isRoot ? '/' : node.label}
          </span>
        </button>
        <span
          className={cn(
            'font-mono text-[11px]',
            active ? 'text-accent' : 'text-text-subtle',
            node.count === 0 && 'opacity-60',
          )}
        >
          {node.count}
        </span>
      </div>
    </li>
  );
}

/**
 * Flatten the tree honoring the expanded set — collapsed subtrees are
 * pruned. Root is always present.
 */
function filteredFlatten(
  root: FolderTreeNode,
  expanded: Set<string>,
): FolderTreeNode[] {
  const out: FolderTreeNode[] = [];
  const walk = (node: FolderTreeNode) => {
    out.push(node);
    if (!expanded.has(node.path)) return;
    for (const child of node.children) walk(child);
  };
  walk(root);
  return out;
}

export { flattenFolderTree as flattenForTests };
