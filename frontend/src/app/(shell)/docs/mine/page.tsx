import { redirect } from 'next/navigation';
import {
  fetchFoldersServerSide,
  fetchMyDocsServerSide,
} from '@/shared/api/docs.server';
import { loadMe } from '@/features/me';
import { MyDocsPage } from '@/views/my-docs';
import { normalizeFolderPath, ROOT_PATH } from '@/entities/document';

/**
 * `/docs/mine` — authenticated route. Per design doc M2-docs.md §"My
 * documents" + M2 docs BC spec §6.1:
 *   - Fetches `/api/docs?scope=mine&path={folder}` server-side for the
 *     right pane (list).
 *   - Fetches `/api/docs/folders` server-side for the left pane (tree).
 *   - URL shape: `/docs/mine?path={folder}&status={all|drafts|published}`
 *     The `status` filter is client-side only (the list endpoint
 *     returns all visibilities in M2; the design doc treats it as a UI
 *     toggle).
 *
 * Post-delete toast: the editor redirects here with
 * `?deleted=<title>` after a successful delete. The MyDocsPage surface
 * picks that up and renders the Undo-disabled toast per ADR-12 §13.
 *
 * Auth flow per ADR-07/10:
 *  - Anonymous caller → gateway returns 401 → redirect to
 *    `/login?next=/docs/mine`.
 *  - Authenticated → render the page (empty state if zero docs).
 */
export const dynamic = 'force-dynamic';

type PageProps = {
  searchParams: {
    path?: string;
    status?: string;
    deleted?: string;
  };
};

export default async function MyDocsRoute({ searchParams }: PageProps) {
  // Cheap belt-and-suspenders: confirm the session before issuing the
  // scoped docs request. If `/me` says anonymous we redirect without
  // ever hitting docs-api.
  const me = await loadMe();
  if (me.kind === 'anonymous') {
    redirect('/login?next=' + encodeURIComponent('/docs/mine'));
  }

  const requestedPath = normalizeFolderPath(searchParams.path);
  const passPath = requestedPath === ROOT_PATH ? undefined : requestedPath;

  // Parallel fetch — folders + scoped list share the cookie forward
  // path so latency stacks on the slower of the two.
  const [foldersResult, listResult] = await Promise.all([
    fetchFoldersServerSide(),
    fetchMyDocsServerSide({ path: passPath }),
  ]);

  if (listResult.kind === 'unauthorized' || foldersResult.kind === 'unauthorized') {
    redirect('/login?next=' + encodeURIComponent('/docs/mine'));
  }

  const folders = foldersResult.kind === 'ok' ? foldersResult.value.items : [];

  if (listResult.kind === 'ok') {
    return (
      <MyDocsPage
        docs={listResult.value.items}
        folders={folders}
        activePath={requestedPath}
        activeStatus={parseStatus(searchParams.status)}
        deletedTitle={searchParams.deleted}
      />
    );
  }

  // 500 / network error — render the shell with a load-error banner
  // rather than crashing the route.
  const detail =
    listResult.kind === 'error'
      ? `gateway returned ${listResult.status}${listResult.message ? ` — ${listResult.message}` : ''}`
      : 'document service did not respond';
  return (
    <MyDocsPage
      docs={[]}
      folders={folders}
      activePath={requestedPath}
      activeStatus={parseStatus(searchParams.status)}
      deletedTitle={searchParams.deleted}
      loadError={detail}
    />
  );
}

function parseStatus(raw: string | undefined): 'all' | 'drafts' | 'published' {
  if (raw === 'drafts') return 'drafts';
  if (raw === 'published') return 'published';
  return 'all';
}
