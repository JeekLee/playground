'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePathname } from 'next/navigation';
import { Sidebar } from '@/widgets/sidebar';
import { Topbar } from '@/widgets/topbar';
import type { User } from '@/entities/user';

/**
 * ShellChrome — client wrapper that owns the sidebar collapsed state.
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
 * Shortcut: ⌘\ on macOS / Ctrl+\ on Windows / Linux toggles collapsed.
 * Sidebar starts expanded; M1 keeps the choice in-memory only (no
 * localStorage persistence — that's a follow-up).
 */
export interface ShellChromeProps {
  user: User | null;
  children: React.ReactNode;
}

export function ShellChrome({ user, children }: ShellChromeProps) {
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
  // pin a short label; the (shell) group's nested routes (`/docs/*`)
  // carry their own breadcrumb logic at the view layer, but the topbar
  // surface itself reads a friendly default so SSR stays stable.
  const breadcrumb = useMemo(() => {
    if (pathname === '/') return 'Home';
    if (pathname === '/docs/mine') return 'My documents';
    if (pathname === '/docs/new') return 'Documents / New';
    if (pathname.startsWith('/docs/')) return 'Documents';
    if (pathname === '/docs') return 'Documents';
    return 'Home';
  }, [pathname]);

  return (
    <div className="flex min-h-screen">
      <Sidebar
        user={user}
        collapsed={sidebarCollapsed}
        onToggleCollapsed={toggle}
        pathname={pathname}
      />
      <div className="flex min-h-screen flex-1 flex-col">
        <Topbar breadcrumb={breadcrumb} user={user} />
        <main className="flex-1">{children}</main>
      </div>
    </div>
  );
}
