import { ShellChrome } from './ShellChrome';
import { loadMe } from '@/features/me';
import { fetchMyDocsServerSide } from '@/shared/api/docs.server';
import type { User } from '@/entities/user';

/**
 * Shelled layout — sidebar + topbar wrap for routes inside the `(shell)`
 * route group. Bare-layout routes (`/login`, `/401`) live outside this
 * group and skip the chrome.
 *
 * This layout is a server component (it awaits `loadMe`). The
 * interactive sidebar toggle + keyboard shortcut live inside the
 * {@link ShellChrome} client component below.
 *
 * Auth state hydration:
 *   - 200 from /me → signed-in shell (`user` populated).
 *   - 401         → public shell (`user = null`).
 *   - other       → log + public shell (handled by `loadMe`).
 *
 * Sidebar `Docs` badge (per design doc M2-docs.md §"Sidebar"):
 *   when the caller is signed in, fetch their doc list once at layout
 *   render time to derive the `published/total` numeric badge shown in
 *   `accent` on the sidebar's Docs row. The badge is omitted for
 *   anonymous shells and when the docs API doesn't respond.
 */
export default async function ShellLayout({ children }: { children: React.ReactNode }) {
  const me = await loadMe();
  const user: User | null = me.kind === 'authenticated' ? me.user : null;

  let docsBadge: { published: number; total: number } | null = null;
  if (user) {
    const list = await fetchMyDocsServerSide();
    if (list.kind === 'ok') {
      const total = list.value.items.length;
      const published = list.value.items.filter((d) => d.visibility === 'public').length;
      docsBadge = { published, total };
    }
  }

  return (
    <ShellChrome user={user} docsBadge={docsBadge}>
      {children}
    </ShellChrome>
  );
}
