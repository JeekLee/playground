import { HomePublicPage } from '@/views/home-public';
import { HomeSignedInPage } from '@/views/home-signed-in';
import { loadMe } from '@/features/me';
import {
  fetchCommunityFeedServerSide,
  fetchOwnerServerSide,
} from '@/shared/api/docs.server';
import type { DocumentListItem } from '@/entities/document';

/**
 * `/` route — branches between Public Home and Signed-in Home based on
 * `/me`. Per ADR-09 the home is a **public route**, so the gateway never
 * returns 401 here — logged-out callers see the Public Home; logged-in
 * callers see the Signed-in Home with their identity in the chrome.
 *
 * M2 S2 deltas:
 *  - Resolve the platform owner via `GET /api/docs/owner` (cached at
 *    request scope by Next.js — the call also runs from
 *    `fetchCommunityFeedServerSide` so we keep both lookups serial; the
 *    fallback is graceful when owner-resolution fails).
 *  - Fetch owner-curated public docs via
 *    `GET /api/docs?author={ownerUserId}` and surface them in the
 *    `Latest published docs` section. If the env var is unset, the
 *    section is hidden entirely (spec §6.3 fail-closed).
 */
export const dynamic = 'force-dynamic';

const HOME_LATEST_LIMIT = 6;

async function loadLatestOwnerDocs(): Promise<DocumentListItem[]> {
  const ownerResult = await fetchOwnerServerSide();
  if (ownerResult.kind !== 'ok' || !ownerResult.value.ownerUserId) {
    return [];
  }
  const feedResult = await fetchCommunityFeedServerSide({
    author: ownerResult.value.ownerUserId,
    limit: HOME_LATEST_LIMIT,
  });
  if (feedResult.kind !== 'ok') {
    return [];
  }
  return feedResult.value.items.slice(0, HOME_LATEST_LIMIT);
}

export default async function HomeRoute() {
  const [me, latestDocs] = await Promise.all([loadMe(), loadLatestOwnerDocs()]);
  if (me.kind === 'authenticated') {
    return <HomeSignedInPage user={me.user} latestDocs={latestDocs} />;
  }
  return <HomePublicPage latestDocs={latestDocs} />;
}
