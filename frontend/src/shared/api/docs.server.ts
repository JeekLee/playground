import 'server-only';
import { headers } from 'next/headers';
import {
  parseResult,
  type DocDetailDto,
  type DocListResponse,
  type DocsResult,
  type MyDocListResponse,
  type OwnerInfoDto,
  type SearchResponseDto,
  type SearchScope,
} from './docs';

/**
 * Docs API client — server-only helpers. Same gateway routing as
 * `./docs.ts`, but with inbound-cookie forwarding so the gateway can
 * resolve `PLAYGROUND_SESSION` during SSR.
 *
 * Marked with `server-only` so any accidental import from a client
 * component fails the Next.js build immediately, instead of silently
 * shipping `next/headers` into the browser bundle.
 */

function gatewayBaseUrl(): string {
  return process.env.GATEWAY_INTERNAL_URL ?? 'http://gateway:18080';
}

function inboundCookieHeader(): string {
  return headers().get('cookie') ?? '';
}

async function serverFetch<T>(path: string): Promise<DocsResult<T>> {
  try {
    const res = await fetch(`${gatewayBaseUrl()}${path}`, {
      headers: {
        accept: 'application/json',
        cookie: inboundCookieHeader(),
      },
      cache: 'no-store',
    });
    return parseResult<T>(res);
  } catch (err) {
    // Network / DNS / refused — gateway is down or unreachable. Surface as
    // a structured error so the caller can render a load-error card
    // instead of throwing through Next.js.
    return {
      kind: 'error',
      status: 0,
      message: err instanceof Error ? err.message : 'network error',
    };
  }
}

/**
 * Fetch the caller's own documents (server-side; cookie forwarded).
 * Returns `unauthorized` if `/me` says anonymous — caller decides whether
 * to redirect to `/login`.
 */
export async function fetchMyDocsServerSide(): Promise<DocsResult<MyDocListResponse>> {
  return serverFetch<MyDocListResponse>('/api/docs?scope=mine');
}

/**
 * Fetch a single document by id (server-side).
 * - 200 → `ok` (caller may be owner or public reader; UI decides via
 *   comparing `author.id` to the current `/me`).
 * - 404 → `not-found` (private doc viewed by non-owner, or doc deleted).
 * - network failure → `error` (renders DocNotFound for now, but caller
 *   can distinguish).
 */
export async function fetchDocByIdServerSide(id: string): Promise<DocsResult<DocDetailDto>> {
  return serverFetch<DocDetailDto>(`/api/docs/${encodeURIComponent(id)}`);
}

/**
 * Community feed — `GET /api/docs` (and the per-author variant when
 * `author` is passed). Auth-optional; cookie forwarded so the gateway
 * can attach `X-User-Id` to surface `likedByMe` for authenticated
 * callers.
 */
export async function fetchCommunityFeedServerSide(options?: {
  cursor?: string;
  author?: string;
  limit?: number;
}): Promise<DocsResult<DocListResponse>> {
  const qs = new URLSearchParams();
  if (options?.cursor) qs.set('cursor', options.cursor);
  if (options?.author) qs.set('author', options.author);
  if (options?.limit !== undefined) qs.set('limit', String(options.limit));
  const path = qs.toString() ? `/api/docs?${qs.toString()}` : '/api/docs';
  return serverFetch<DocListResponse>(path);
}

/**
 * Full-text search SSR variant. The `/docs/search` route renders an
 * initial result set server-side from `?q=` for share-ability + SEO;
 * subsequent typing requeries client-side via `searchDocs`.
 */
export async function searchDocsServerSide(options: {
  q: string;
  scope: SearchScope;
  cursor?: string;
}): Promise<DocsResult<SearchResponseDto>> {
  const qs = new URLSearchParams({ q: options.q, scope: options.scope });
  if (options.cursor) qs.set('cursor', options.cursor);
  return serverFetch<SearchResponseDto>(`/api/docs/search?${qs.toString()}`);
}

/**
 * Owner resolution — used at home-render time to decide whether to mount
 * the `Latest published docs` section. Returns `null` on any failure so
 * the home stays renderable.
 */
export async function fetchOwnerServerSide(): Promise<DocsResult<OwnerInfoDto>> {
  return serverFetch<OwnerInfoDto>('/api/docs/owner');
}
