import type { FolderListItem } from './types';

/**
 * Folder-tree assembly — turns the flat `FolderListItem[]` from
 * `GET /api/docs/folders` (one row per distinct `path` the caller
 * actually has docs under) into a nested tree the UI can render.
 *
 * The backend response is intentionally flat per M2 docs BC spec §6.1:
 *   `[ { path: '/agents/', count: 8 }, { path: '/agents/build-log/', count: 5 }, ... ]`
 *
 * Root `/` is always present in the rendered tree (it counts the docs
 * the caller has at root), even if the response omits it.
 *
 * The implicit-folder rule (spec §4.1) means a parent folder like
 * `/agents/` is materialized only when at least one doc lives there.
 * Deeper folders like `/agents/build-log/` may exist without `/agents/`
 * being in the response — in that case the tree still threads through
 * `/agents/` as a synthetic, count-0 intermediate node so the
 * indentation reads correctly. Synthetic nodes are non-selectable in
 * the UI (clicking them is a no-op).
 */

export interface FolderTreeNode {
  /** The canonical path string, e.g. `'/'`, `'/agents/'`. */
  path: string;
  /** Last segment for display (e.g. `'agents'` for `/agents/`). */
  label: string;
  /** Sum of docs at exactly this path; 0 for synthetic intermediates. */
  count: number;
  /** True when no `FolderListItem` row backs this node. */
  synthetic: boolean;
  /** Depth from root (root = 0). */
  depth: number;
  /** Direct children (already sorted alphabetically). */
  children: FolderTreeNode[];
}

const ROOT_PATH = '/';

/**
 * Normalize a path string to the canonical form:
 *   - always starts AND ends with `/`
 *   - empty / null collapses to root `/`
 *   - whitespace trimmed
 */
export function normalizeFolderPath(input: string | null | undefined): string {
  if (!input) return ROOT_PATH;
  const trimmed = input.trim();
  if (trimmed === '' || trimmed === '/') return ROOT_PATH;
  const withLeading = trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
  return withLeading.endsWith('/') ? withLeading : `${withLeading}/`;
}

/**
 * Display label for a path — for root `/` returns `'/'`; for nested
 * paths returns the last non-empty segment (`/agents/build-log/` →
 * `'build-log'`).
 */
export function folderLabel(path: string): string {
  if (path === ROOT_PATH) return '/';
  const segments = path.split('/').filter(Boolean);
  return segments[segments.length - 1] ?? '/';
}

/**
 * All ancestor paths of `path`, root-first, excluding `path` itself.
 * `/agents/build-log/` → `[ '/', '/agents/' ]`.
 */
function ancestorsOf(path: string): string[] {
  if (path === ROOT_PATH) return [];
  const segments = path.split('/').filter(Boolean);
  const out: string[] = [ROOT_PATH];
  for (let i = 0; i < segments.length - 1; i++) {
    const prefix = segments.slice(0, i + 1).join('/');
    out.push(`/${prefix}/`);
  }
  return out;
}

/**
 * Build a folder tree from the flat folder response. Sort siblings
 * alphabetically by label (case-insensitive); root always first.
 */
export function buildFolderTree(items: FolderListItem[]): FolderTreeNode {
  const byPath = new Map<string, FolderTreeNode>();

  const ensureNode = (path: string, count: number, synthetic: boolean): FolderTreeNode => {
    const existing = byPath.get(path);
    if (existing) {
      // Backend-rounded count wins over a synthetic placeholder.
      if (!synthetic) {
        existing.count = count;
        existing.synthetic = false;
      }
      return existing;
    }
    const node: FolderTreeNode = {
      path,
      label: folderLabel(path),
      count,
      synthetic,
      depth: path === ROOT_PATH ? 0 : path.split('/').filter(Boolean).length,
      children: [],
    };
    byPath.set(path, node);
    return node;
  };

  // Always materialize root, even if the response omits it.
  ensureNode(ROOT_PATH, 0, true);

  for (const item of items) {
    const normalized = normalizeFolderPath(item.path);
    ensureNode(normalized, item.count, false);
    for (const ancestor of ancestorsOf(normalized)) {
      ensureNode(ancestor, 0, true);
    }
  }

  // Wire parent → child relationships.
  for (const node of byPath.values()) {
    if (node.path === ROOT_PATH) continue;
    const parentPath = parentOf(node.path);
    const parent = byPath.get(parentPath);
    if (parent && !parent.children.find((c) => c.path === node.path)) {
      parent.children.push(node);
    }
  }

  // Sort siblings alphabetically (case-insensitive).
  for (const node of byPath.values()) {
    node.children.sort((a, b) =>
      a.label.toLowerCase().localeCompare(b.label.toLowerCase()),
    );
  }

  return byPath.get(ROOT_PATH) ?? {
    path: ROOT_PATH,
    label: '/',
    count: 0,
    synthetic: true,
    depth: 0,
    children: [],
  };
}

/**
 * The immediate parent path of `path` (root has no parent and returns
 * itself).
 */
export function parentOf(path: string): string {
  if (path === ROOT_PATH) return ROOT_PATH;
  const segments = path.split('/').filter(Boolean);
  if (segments.length <= 1) return ROOT_PATH;
  return `/${segments.slice(0, -1).join('/')}/`;
}

/**
 * Flatten the tree in pre-order so the UI can render it as a single
 * `<ul>` with `depth`-driven indentation. Root is included.
 */
export function flattenFolderTree(root: FolderTreeNode): FolderTreeNode[] {
  const out: FolderTreeNode[] = [];
  const walk = (node: FolderTreeNode) => {
    out.push(node);
    for (const child of node.children) walk(child);
  };
  walk(root);
  return out;
}

/**
 * Total doc count across every (non-synthetic) folder. Used by the
 * sidebar's "X/Y" published-vs-total badge fallback and the "All" status
 * filter count.
 */
export function totalDocCount(items: FolderListItem[]): number {
  return items.reduce((sum, item) => sum + item.count, 0);
}

export { ROOT_PATH };
