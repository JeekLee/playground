import { redirect } from 'next/navigation';
import { loadMe } from '@/features/me';
import { searchDocsServerSide } from '@/shared/api/docs.server';
import { DocsSearchPage } from '@/views/docs-search';
import type { DocSearchScope } from '@/entities/document';

/**
 * `/docs/search` — full-page search route.
 *
 * Per M2 spec v5 §6.1 / §6.2 the route accepts `?q=` and `?scope=`. The
 * `mine` scope requires auth (`X-User-Id`); `public` works anonymously.
 *
 * Default scope per design doc + dispatch:
 *  - authenticated → `mine` (most common use)
 *  - anonymous     → `public` (only valid scope)
 *
 * SSR contract: when a query string lands with `?q=…`, we kick off the
 * initial search server-side so the first paint already has hits (great
 * for share-ability + SEO). Subsequent typing requeries client-side.
 *
 * `?scope=mine` with an anonymous caller is gracefully bumped to the
 * login page with the search URL preserved as `next`.
 */
export const dynamic = 'force-dynamic';

interface PageProps {
  searchParams: { q?: string; scope?: string };
}

export default async function DocsSearchRoute({ searchParams }: PageProps) {
  const me = await loadMe();
  const isAuthenticated = me.kind === 'authenticated';

  const rawScope = searchParams.scope;
  const requestedScope: DocSearchScope =
    rawScope === 'public' || rawScope === 'mine' ? rawScope : isAuthenticated ? 'mine' : 'public';
  const defaultScope: DocSearchScope = isAuthenticated ? 'mine' : 'public';

  // Anonymous caller asking for `mine` scope — push them to login with
  // the original URL preserved so they bounce back after sign-in.
  if (!isAuthenticated && requestedScope === 'mine') {
    const qsParts = new URLSearchParams();
    if (searchParams.q) qsParts.set('q', searchParams.q);
    qsParts.set('scope', 'mine');
    const target = `/docs/search?${qsParts.toString()}`;
    redirect('/login?next=' + encodeURIComponent(target));
  }

  const query = searchParams.q ?? '';
  if (!query.trim()) {
    return (
      <DocsSearchPage
        initialQuery=""
        initialScope={requestedScope}
        initialResults={[]}
        defaultScope={defaultScope}
      />
    );
  }

  const result = await searchDocsServerSide({ q: query, scope: requestedScope });
  if (result.kind === 'ok') {
    return (
      <DocsSearchPage
        initialQuery={query}
        initialScope={requestedScope}
        initialResults={result.value.items}
        defaultScope={defaultScope}
      />
    );
  }
  if (result.kind === 'service-unavailable') {
    return (
      <DocsSearchPage
        initialQuery={query}
        initialScope={requestedScope}
        initialResults={[]}
        initialUnavailable
        defaultScope={defaultScope}
      />
    );
  }
  // Other failures land on the empty results state with a banner — the
  // client-side requery picks up cleanly when the user edits the input.
  return (
    <DocsSearchPage
      initialQuery={query}
      initialScope={requestedScope}
      initialResults={[]}
      defaultScope={defaultScope}
    />
  );
}
