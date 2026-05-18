import { redirect } from 'next/navigation';
import { loadMe } from '@/features/me';
import { DocNewPage } from '@/views/doc-new';

/**
 * `/docs/new` — authenticated route. Server-side, we confirm there's a
 * session before rendering the editor (anonymous → /login). The editor
 * itself is a client component because it owns the BlockNote instance
 * and the save loop.
 */
export const dynamic = 'force-dynamic';

export default async function DocNewRoute() {
  const me = await loadMe();
  if (me.kind === 'anonymous') {
    redirect('/login?next=' + encodeURIComponent('/docs/new'));
  }
  return <DocNewPage />;
}
