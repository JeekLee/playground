'use client';

import { useCallback, useEffect, useState } from 'react';
import { Sidebar } from '@/widgets/sidebar';
import { Topbar } from '@/widgets/topbar';
import type { User } from '@/entities/user';

/**
 * ShellChrome — client wrapper that owns the sidebar open/closed state.
 *
 * The Next.js App Router layout is a server component (it awaits
 * `loadMe`), so the interactive toggle plus the keyboard shortcut have
 * to live in this client component. Layout passes pre-fetched
 * `user` + `children` through.
 *
 * Shortcut: ⌘\ on macOS / Ctrl+\ on Windows / Linux toggles the sidebar.
 * Sidebar starts open; M1 keeps the choice in-memory only (no
 * localStorage persistence — that's a follow-up).
 */
export interface ShellChromeProps {
  user: User | null;
  children: React.ReactNode;
}

export function ShellChrome({ user, children }: ShellChromeProps) {
  const [sidebarOpen, setSidebarOpen] = useState(true);

  const toggle = useCallback(() => setSidebarOpen((prev) => !prev), []);

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

  return (
    <div className="flex min-h-screen">
      {sidebarOpen && <Sidebar user={user} />}
      <div className="flex min-h-screen flex-1 flex-col">
        <Topbar
          breadcrumb="Home"
          user={user}
          sidebarOpen={sidebarOpen}
          onToggleSidebar={toggle}
        />
        <main className="flex-1">{children}</main>
      </div>
    </div>
  );
}
