import { HomePublicPage } from '@/views/home-public';
import { HomeSignedInPage } from '@/views/home-signed-in';
import { loadMe } from '@/features/me';
import { fetchCommunityFeedServerSide } from '@/shared/api/docs.server';
import type { DocumentListItem } from '@/entities/document';

/**
 * `/` route — branches between Public Home and Signed-in Home based on
 * `/me`. Per ADR-09 the home is a **public route**, so the gateway never
 * returns 401 here — logged-out callers see the Public Home; logged-in
 * callers see the Signed-in Home with their identity in the chrome.
 *
 * The `Latest published docs` section sources the community-wide feed
 * (`GET /api/docs` with no author filter) so the home surfaces every
 * author's latest public work, not just the platform owner's. Empty
 * state falls back gracefully when there are zero published docs.
 */
export const dynamic = 'force-dynamic';

const HOME_LATEST_LIMIT = 6;

async function loadLatestCommunityDocs(): Promise<DocumentListItem[]> {
  const feedResult = await fetchCommunityFeedServerSide({
    limit: HOME_LATEST_LIMIT,
  });
  if (feedResult.kind !== 'ok') {
    return [];
  }
  return feedResult.value.items.slice(0, HOME_LATEST_LIMIT);
}

export default async function HomeRoute() {
  const [me, latestDocs] = await Promise.all([loadMe(), loadLatestCommunityDocs()]);
  if (me.kind === 'authenticated') {
    return <HomeSignedInPage user={me.user} latestDocs={latestDocs} />;
  }
  return <HomePublicPage latestDocs={latestDocs} />;
}
