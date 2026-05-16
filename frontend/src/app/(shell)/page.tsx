import { HomePublicPage } from '@/views/home-public';
import { HomeSignedInPage } from '@/views/home-signed-in';
import { loadMe } from '@/features/me';

/**
 * `/` route — branches between Public Home and Signed-in Home based on
 * `/me`. Per ADR-09 the home is a **public route**, so the gateway never
 * returns 401 here — logged-out callers see the Public Home; logged-in
 * callers see the Signed-in Home with their identity in the chrome.
 *
 * The layout already fetches `/me` to drive the sidebar/topbar; this
 * page fetches it again as a fresh server call so the body composition
 * is in lockstep with the chrome (Next.js dedupes the request in the
 * same render pass).
 */
export default async function HomeRoute() {
  const me = await loadMe();
  if (me.kind === 'authenticated') {
    return <HomeSignedInPage user={me.user} />;
  }
  return <HomePublicPage />;
}
