'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePathname } from 'next/navigation';
import { Sidebar } from '@/widgets/sidebar';
import { Topbar } from '@/widgets/topbar';
import { CommandPalette, openCommandPalette } from '@/widgets/command-palette';
import type { User } from '@/entities/user';

/**
 * ShellChrome — client wrapper that owns the sidebar collapsed state +
 * mounts the global ⌘K command palette on every shelled route.
 *
 * The Next.js App Router layout is a server component (it awaits
 * `loadMe`), so the interactive toggle plus the keyboard shortcut have
 * to live in this client component. Layout passes pre-fetched
 * `user` + `children` through.
 *
 * Sidebar is always rendered; only its width flips between the
 * 232px expanded mode and the 64px icon-only rail, mirroring
 * Obsidian / VSCode.
 *
 * Shortcuts handled here:
 *  - ⌘\ / Ctrl+\ — toggle sidebar collapsed state.
 *  - ⌘K / Ctrl+K — open the command palette (handled by the palette
 *    itself via `useCommandPalette`; the topbar search pill click also
 *    calls `openCommandPalette` to surface the same overlay).
 *
 * The palette is mounted on every shelled route, authed or anonymous —
 * anonymous callers get a public-scope-only palette per spec §6.1.
 */
export interface ShellChromeProps {
  user: User | null;
  /**
   * Optional `published/total` doc counts surfaced as a numeric badge on
   * the sidebar's Docs row (per design doc M2-docs.md §"Sidebar"). Null
   * for anonymous shells or when the docs API doesn't respond.
   */
  docsBadge?: { published: number; total: number } | null;
  children: React.ReactNode;
}

export function ShellChrome({ user, docsBadge, children }: ShellChromeProps) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const pathname = usePathname() ?? '/';

  const toggle = useCallback(() => setSidebarCollapsed((prev) => !prev), []);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === '\\' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        toggle();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [toggle]);

  // Topbar breadcrumb adapts to the current route. Top-level destinations
  // pin a short label; the (shell) group's nested routes carry their own
  // breadcrumb logic at the view layer, but the topbar surface itself
  // reads a friendly default so SSR stays stable.
  const breadcrumb = useMemo(() => {
    if (pathname === '/') return 'Home';
    if (pathname === '/docs') return 'Documents';
    if (pathname === '/docs/mine') return 'My documents';
    if (pathname === '/docs/new') return 'Documents / New';
    if (pathname === '/docs/search') return 'Documents / Search';
    if (pathname.startsWith('/docs/')) return 'Documents';
    if (pathname === '/chat' || pathname.startsWith('/chat/')) return 'Home / Chat';
    return 'Home';
  }, [pathname]);

  const isAuthenticated = user !== null;

  return (
    <div className="flex min-h-screen">
      <Sidebar
        user={user}
        collapsed={sidebarCollapsed}
        onToggleCollapsed={toggle}
        pathname={pathname}
        docsBadge={docsBadge ?? null}
      />
      <div className="flex min-h-screen flex-1 flex-col">
        <Topbar
          breadcrumb={breadcrumb}
          user={user}
          onOpenSearch={openCommandPalette}
        />
        <main className="flex-1">{children}</main>
      </div>
      <CommandPalette isAuthenticated={isAuthenticated} />
    </div>
  );
}
