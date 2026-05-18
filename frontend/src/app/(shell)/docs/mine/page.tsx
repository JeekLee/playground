import { redirect } from 'next/navigation';
import { fetchMyDocsServerSide } from '@/shared/api/docs.server';
import { loadMe } from '@/features/me';
import { MyDocsPage } from '@/views/my-docs';

/**
 * `/docs/mine` — authenticated route. Fetches `/api/docs?scope=mine`
 * server-side with the inbound session cookie forwarded.
 *
 * Auth flow per ADR-07/10:
 * - Anonymous caller → gateway returns 401 → redirect to
 *   `/login?next=/docs/mine`.
 * - Authenticated → render the list (empty state if zero docs).
 */
export const dynamic = 'force-dynamic';

export default async function MyDocsRoute() {
  // Cheap belt-and-suspenders: confirm the session before issuing the
  // scoped docs request. If `/me` says anonymous we redirect without
  // ever hitting docs-api.
  const me = await loadMe();
  if (me.kind === 'anonymous') {
    redirect('/login?next=' + encodeURIComponent('/docs/mine'));
  }

  const result = await fetchMyDocsServerSide();

  if (result.kind === 'unauthorized') {
    redirect('/login?next=' + encodeURIComponent('/docs/mine'));
  }

  if (result.kind === 'ok') {
    return <MyDocsPage docs={result.value.items} />;
  }

  // 500 / network error — render the empty shell with a load-error banner
  // rather than crashing the route.
  const detail =
    result.kind === 'error'
      ? `gateway returned ${result.status}${result.message ? ` — ${result.message}` : ''}`
      : 'document service did not respond';
  return <MyDocsPage docs={[]} loadError={detail} />;
}
