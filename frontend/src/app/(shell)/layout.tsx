import { ShellChrome } from './ShellChrome';
import { loadMe } from '@/features/me';
import type { User } from '@/entities/user';

/**
 * Shelled layout — sidebar + topbar wrap for routes inside the `(shell)`
 * route group (currently just `/`). Bare-layout routes (`/login`, `/401`)
 * live outside this group and skip the chrome by design (per design
 * context "no sidebar, no topbar" notes).
 *
 * This layout is a server component (it awaits `loadMe`). The interactive
 * sidebar toggle + keyboard shortcut live inside the {@link ShellChrome}
 * client component below — children are forwarded through unchanged.
 *
 * Auth state hydration:
 *   - 200 from /me → signed-in shell (`user` populated).
 *   - 401         → public shell (`user = null`).
 *   - other       → log + public shell (handled by `loadMe`).
 */
export default async function ShellLayout({ children }: { children: React.ReactNode }) {
  const me = await loadMe();
  const user: User | null = me.kind === 'authenticated' ? me.user : null;

  return <ShellChrome user={user}>{children}</ShellChrome>;
}
