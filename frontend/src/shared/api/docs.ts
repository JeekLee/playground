/**
 * Docs API client (browser-safe). Routed through the gateway per ADR-07:
 *   /api/docs/**   →  docs-api  /docs/**
 *
 * Same-origin relative paths — the browser attaches `PLAYGROUND_SESSION`
 * automatically. The SSR-side helpers (which forward the inbound cookie
 * via `next/headers`) live in the sibling `./docs.server.ts` module so
 * this file stays consumable from client components.
 *
 * Wire shapes mirror M2 docs BC spec v5 §6.4 verbatim. Field naming
 * stays camelCase end-to-end (docs-api serializes `MyDocListItem.path`
 * etc.; no transform layer).
 *
 * S1 scope: single-author CRUD + .md upload. The community-feed / search /
 * like / view shapes are wired here for forward compatibility but S1
 * views do not consume them yet (omitted per dispatch spec).
 */

// -------------------- DTOs (verbatim from spec §6.4) ----------------------

export type DocVisibility = 'private' | 'public';

export interface AuthorDto {
  id: string;
  displayName: string;
  avatarUrl: string | null;
}

export interface DocListItemDto {
  id: string;
  title: string;
  excerpt: string;
  visibility: 'public';
  path: string;
  author: AuthorDto;
  publishedAt: string;
  viewCount: number;
  likeCount: number;
  likedByMe?: boolean;
}

export interface DocDetailDto {
  id: string;
  title: string;
  body: string;
  excerpt: string;
  visibility: DocVisibility;
  path: string;
  author: AuthorDto;
  viewCount: number;
  likeCount: number;
  likedByMe?: boolean;
  publishedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface MyDocListItemDto {
  id: string;
  title: string;
  excerpt: string;
  visibility: DocVisibility;
  path: string;
  updatedAt: string;
  publishedAt?: string;
  viewCount: number;
  likeCount: number;
}

export interface CreateDocRequestDto {
  title: string;
  body?: string;
  path?: string;
}

export interface PatchDocRequestDto {
  title?: string;
  body?: string;
}

export interface MyDocListResponse {
  items: MyDocListItemDto[];
  nextCursor: string | null;
}

/** Community-feed + per-author public list response — spec §6.1. */
export interface DocListResponse {
  items: DocListItemDto[];
  nextCursor: string | null;
}

/** Search hit shape — spec §6.4. `snippet` arrives already with `<mark>` */
export interface SearchHitDto {
  documentId: string;
  title: string;
  visibility: DocVisibility;
  path: string | null;
  author: AuthorDto | null;
  snippet: string;
  publishedAt?: string;
  updatedAt: string;
}

export interface SearchResponseDto {
  items: SearchHitDto[];
  nextCursor: string | null;
}

export type SearchScope = 'mine' | 'public';

/** Owner-resolution endpoint — spec §6.3. */
export interface OwnerInfoDto {
  ownerUserId: string | null;
}

// -------------------- Result type -----------------------------------------

export type DocsResult<T> =
  | { kind: 'ok'; value: T }
  | { kind: 'unauthorized' }
  | { kind: 'not-found' }
  | { kind: 'too-large' }
  | { kind: 'service-unavailable' }
  | { kind: 'rate-limited' }
  | { kind: 'error'; status: number; message?: string };

export async function parseResult<T>(res: Response): Promise<DocsResult<T>> {
  if (res.status === 401) return { kind: 'unauthorized' };
  if (res.status === 404) return { kind: 'not-found' };
  if (res.status === 413) return { kind: 'too-large' };
  if (res.status === 429) return { kind: 'rate-limited' };
  if (res.status === 503) return { kind: 'service-unavailable' };
  if (res.ok) {
    if (res.status === 204) return { kind: 'ok', value: undefined as unknown as T };
    const value = (await res.json()) as T;
    return { kind: 'ok', value };
  }
  let message: string | undefined;
  try {
    const body = (await res.json()) as { message?: string };
    message = body.message;
  } catch {
    // ignore
  }
  return { kind: 'error', status: res.status, message };
}

// -------------------- Client-side helpers ---------------------------------

/**
 * Read the XSRF token Spring Security exposes via the `XSRF-TOKEN` cookie.
 * docs-api mutating routes (`POST`, `PATCH`, `DELETE`) require it per ADR-07
 * CSRF semantics.
 */
function getXsrfToken(): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return match && match[1] ? decodeURIComponent(match[1]) : null;
}

function browserHeaders(extra?: Record<string, string>): HeadersInit {
  const token = getXsrfToken();
  return {
    accept: 'application/json',
    ...(token ? { 'X-XSRF-TOKEN': token } : {}),
    ...(extra ?? {}),
  };
}

export async function createDocument(
  payload: CreateDocRequestDto,
): Promise<DocsResult<DocDetailDto>> {
  const res = await fetch('/api/docs', {
    method: 'POST',
    credentials: 'same-origin',
    headers: browserHeaders({ 'content-type': 'application/json' }),
    body: JSON.stringify(payload),
  });
  return parseResult<DocDetailDto>(res);
}

/**
 * Multipart .md file upload variant of `POST /api/docs`. Per spec §6.2 the
 * server accepts a `.md` file part + optional `title` + optional `path`.
 */
export async function importMarkdownDocument(
  file: File,
  options?: { title?: string; path?: string },
): Promise<DocsResult<DocDetailDto>> {
  const form = new FormData();
  form.append('file', file, file.name);
  if (options?.title) form.append('title', options.title);
  if (options?.path) form.append('path', options.path);
  const res = await fetch('/api/docs', {
    method: 'POST',
    credentials: 'same-origin',
    headers: browserHeaders(),
    body: form,
  });
  return parseResult<DocDetailDto>(res);
}

export async function patchDocument(
  id: string,
  payload: PatchDocRequestDto,
): Promise<DocsResult<DocDetailDto>> {
  const res = await fetch(`/api/docs/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: browserHeaders({ 'content-type': 'application/json' }),
    body: JSON.stringify(payload),
  });
  return parseResult<DocDetailDto>(res);
}

export async function publishDocument(id: string): Promise<DocsResult<DocDetailDto>> {
  const res = await fetch(`/api/docs/${encodeURIComponent(id)}/publish`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<DocDetailDto>(res);
}

export async function unpublishDocument(id: string): Promise<DocsResult<DocDetailDto>> {
  const res = await fetch(`/api/docs/${encodeURIComponent(id)}/unpublish`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<DocDetailDto>(res);
}

export async function deleteDocument(id: string): Promise<DocsResult<void>> {
  const res = await fetch(`/api/docs/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<void>(res);
}

/**
 * Client-side fetch of the caller's documents (used by `/docs/mine` for
 * post-create / post-delete refresh). Same `?scope=mine` shape.
 */
export async function fetchMyDocs(): Promise<DocsResult<MyDocListResponse>> {
  const res = await fetch('/api/docs?scope=mine', {
    method: 'GET',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<MyDocListResponse>(res);
}

/**
 * Community-feed fetch — `GET /api/docs` per spec §6.1. Auth-optional.
 * Pass `cursor` to load the next page; pass `author` to scope to a single
 * author (used by the home for the owner feed).
 */
export async function fetchCommunityFeed(options?: {
  cursor?: string;
  author?: string;
  limit?: number;
}): Promise<DocsResult<DocListResponse>> {
  const qs = new URLSearchParams();
  if (options?.cursor) qs.set('cursor', options.cursor);
  if (options?.author) qs.set('author', options.author);
  if (options?.limit !== undefined) qs.set('limit', String(options.limit));
  const path = qs.toString() ? `/api/docs?${qs.toString()}` : '/api/docs';
  const res = await fetch(path, {
    method: 'GET',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<DocListResponse>(res);
}

/**
 * Full-text search — `GET /api/docs/search` per spec §6.1 / §6.2.
 * The `mine` scope requires a session; `public` is auth-optional.
 * Empty `q` is short-circuited at the call site so we never burn a
 * network round-trip on no-op input.
 */
export async function searchDocs(options: {
  q: string;
  scope: SearchScope;
  cursor?: string;
}): Promise<DocsResult<SearchResponseDto>> {
  const qs = new URLSearchParams({ q: options.q, scope: options.scope });
  if (options.cursor) qs.set('cursor', options.cursor);
  const res = await fetch(`/api/docs/search?${qs.toString()}`, {
    method: 'GET',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<SearchResponseDto>(res);
}

/**
 * Owner resolution — `GET /api/docs/owner` per spec §6.3. Returns
 * `{ ownerUserId: null }` when `PLAYGROUND_OWNER_GOOGLE_SUB` is unset on
 * the docs service (fail-closed). Callers MUST hide the owner-curated
 * surface when this returns null.
 */
export async function fetchOwner(): Promise<DocsResult<OwnerInfoDto>> {
  const res = await fetch('/api/docs/owner', {
    method: 'GET',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<OwnerInfoDto>(res);
}

// -------------------- Body size cap ---------------------------------------

/** Per ADR-12 §4 — 1 MB raw Markdown body cap, enforced client + server. */
export const MAX_BODY_BYTES = 1_048_576;

export function bodyByteSize(body: string): number {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(body).length;
  }
  // Fallback (older runtimes) — approximate; client always has TextEncoder.
  return Buffer.byteLength(body, 'utf8');
}
