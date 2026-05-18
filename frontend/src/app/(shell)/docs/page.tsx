import { fetchCommunityFeedServerSide } from '@/shared/api/docs.server';
import { CommunityFeedPage } from '@/views/community-feed';

/**
 * `/docs` — community feed route. Per M2 spec v5 §6.1 + §7.2:
 *  - auth-optional (anonymous OK; signed-in users land here too — the
 *    chrome adapts, the page itself doesn't)
 *  - server-side initial fetch of the first cursor page; client-side
 *    pagination from there
 *  - 429 from the gateway surfaces as a non-blocking banner per design
 *    doc §"Documents" empty/error states
 */
export const dynamic = 'force-dynamic';

export default async function CommunityFeedRoute() {
  const result = await fetchCommunityFeedServerSide();

  if (result.kind === 'ok') {
    return (
      <CommunityFeedPage
        initialItems={result.value.items}
        initialNextCursor={result.value.nextCursor}
      />
    );
  }
  if (result.kind === 'rate-limited') {
    return (
      <CommunityFeedPage initialItems={[]} initialNextCursor={null} rateLimited />
    );
  }
  // 5xx / network — render the shell with a non-blocking error banner so
  // the topbar + sidebar still let the user navigate elsewhere.
  const detail =
    result.kind === 'error'
      ? `Feed unavailable (status ${result.status}${result.message ? ` — ${result.message}` : ''}).`
      : "Couldn't reach the document service.";
  return (
    <CommunityFeedPage
      initialItems={[]}
      initialNextCursor={null}
      loadError={detail}
    />
  );
}
