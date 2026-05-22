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

/**
 * Source-file MIME type for a document — M6 adds `application/pdf` alongside
 * the M2 default `text/markdown`. The field is optional on the wire so the
 * frontend keeps rendering pre-M6 backends (where the column hasn't been
 * added yet) without crashing — `undefined` is treated as markdown via
 * {@link isPdfSourced}. Once the backend ships `mimeType` on every row the
 * field still stays optional in the type (forward-compat with future
 * source types — `application/json`, `text/html`, …) but every real row
 * will carry one of the two strings below.
 */
export type DocMimeType = 'text/markdown' | 'application/pdf';

/**
 * Async extraction lifecycle per M6.1 ADR-12 §A12.3 / §A12.5.
 *
 * - `pending` — body materialized synchronously at create time; no async
 *   work required. Treated as "extracted" for UI purposes.
 * - `pending_extraction` — INSERTed with empty body; worker has not yet
 *   picked it up.
 * - `extracting` — worker in progress. PDF rows transition through this
 *   page-by-page; SSE may emit `pageDone`/`pageTotal` while in this state.
 * - `extracted` — body materialized; the document is fully readable.
 * - `failed` — worker errored; `extractionReason` carries a user-readable
 *   reason. The original blob is retained in MinIO so the user can still
 *   download the source.
 *
 * The field is optional on the wire for forward compatibility — pre-M6.1
 * backends and never-extracted (markdown-authored) rows can omit it; the
 * frontend treats `undefined` as terminal/`extracted` via {@link isExtractionTerminal}.
 */
export type ExtractionStatus =
  | 'pending'
  | 'pending_extraction'
  | 'extracting'
  | 'extracted'
  | 'failed';

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
  /** M6 — present once the backend ships PDF support; absent means markdown. */
  mimeType?: DocMimeType;
}

export interface DocDetailDto {
  id: string;
  title: string;
  body: string;
  excerpt: string;
  visibility: DocVisibility;
  path: string;
  authorId: string;
  // Spec §6.4 declares this non-nullable, but defensive code paths fall
  // back to `authorId` when the cross-BC identity lookup misses (e.g.,
  // identity-api transient outage). Backend returns a placeholder
  // {id: authorId, displayName: 'Unknown', avatarUrl: null} so callers
  // can treat `author` as always present in normal operation.
  author: AuthorDto;
  viewCount: number;
  likeCount: number;
  likedByMe?: boolean;
  publishedAt?: string;
  createdAt: string;
  updatedAt: string;
  /** M6 — present once the backend ships PDF support; absent means markdown. */
  mimeType?: DocMimeType;
  /**
   * M6.1 — async extraction lifecycle. Absent on pre-M6.1 rows; present and
   * one of {@link ExtractionStatus} once the migration lands. The frontend
   * treats `undefined` as `extracted` (terminal) so legacy rows render as
   * before.
   */
  extractionStatus?: ExtractionStatus;
  /**
   * M6.1 — user-readable reason set when `extractionStatus === 'failed'`.
   * `null` on every other status (and absent on legacy rows).
   */
  extractionReason?: string | null;
  /**
   * M6.1 follow-up — true when a MinIO-retained source blob exists for this
   * document (i.e., `GET /api/docs/{id}/original` will stream it back).
   * Absent on pre-M6.1 rows; backend always sets it on M6.1+ rows
   * (true for multipart uploads — PDF *and* MD — and false for JSON POSTs).
   */
  hasOriginal?: boolean;
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
  /** M6 — present once the backend ships PDF support; absent means markdown. */
  mimeType?: DocMimeType;
  /**
   * M6.1 — async extraction lifecycle. The backend surfaces this on every
   * M6.1+ row; absent on pre-M6.1 rows (treated as `extracted` / terminal
   * by the list row). Drives the "(분석 중)" coexistence hint + title
   * dim on `/docs/mine`.
   */
  extractionStatus?: ExtractionStatus;
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
  /** M6 — present once the backend ships PDF support; absent means markdown. */
  mimeType?: DocMimeType;
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

/**
 * Folder listing — spec §6.1 / §6.4. One row per distinct `path` the
 * caller owns; `count` is the number of docs at that exact path. Roots
 * implied by deeper paths (e.g. `/agents/` from `/agents/build-log/`)
 * may or may not appear in the backend response — the tree widget
 * derives the parent layer client-side either way.
 */
export interface FolderListItemDto {
  path: string;
  count: number;
}

export interface FolderListResponse {
  items: FolderListItemDto[];
}

// -------------------- Result type -----------------------------------------

/**
 * M6 — backend-issued upload error codes for `.md` / `.pdf` imports.
 *
 * Wire shape: the docs-api returns these as JSON bodies on 400/413:
 *   { code: 'PDF_TOO_MANY_PAGES', message: 'PDF exceeds 200-page limit' }
 *
 * The frontend keys off `code` (stable contract) rather than the wire
 * `message` (operator-friendly, may change) so the user-facing toast copy
 * stays under design control. See {@link UPLOAD_ERROR_COPY} below for the
 * one-to-one mapping used by `NewDocButton` and `DragDropImportOverlay`.
 */
export type DocUploadErrorCode =
  | 'INVALID_FILE_TYPE'
  | 'PDF_CORRUPTED'
  | 'PDF_ENCRYPTED'
  | 'PDF_TOO_MANY_PAGES'
  | 'PDF_TOO_MANY_OCR_PAGES'
  | 'FILE_TOO_LARGE';

export type DocsResult<T> =
  | { kind: 'ok'; value: T }
  | { kind: 'unauthorized' }
  | { kind: 'not-found' }
  | { kind: 'too-large'; code?: DocUploadErrorCode }
  | { kind: 'service-unavailable' }
  | { kind: 'rate-limited' }
  | { kind: 'upload-rejected'; code: DocUploadErrorCode; message?: string }
  | { kind: 'error'; status: number; message?: string };

export async function parseResult<T>(res: Response): Promise<DocsResult<T>> {
  if (res.status === 401) return { kind: 'unauthorized' };
  if (res.status === 404) return { kind: 'not-found' };
  if (res.status === 429) return { kind: 'rate-limited' };
  if (res.status === 503) return { kind: 'service-unavailable' };
  if (res.ok) {
    if (res.status === 204) return { kind: 'ok', value: undefined as unknown as T };
    const value = (await res.json()) as T;
    return { kind: 'ok', value };
  }
  // 400/413 may carry an M6 upload error code; surface it structurally so
  // the caller can pick the right toast copy without parsing message text.
  let body: { code?: string; message?: string } | undefined;
  try {
    body = (await res.json()) as { code?: string; message?: string };
  } catch {
    // No JSON body — fall through to the generic shapes below.
  }
  const code = body?.code as DocUploadErrorCode | undefined;
  if (res.status === 413) {
    return { kind: 'too-large', code };
  }
  if (res.status === 400 && isUploadErrorCode(code)) {
    return { kind: 'upload-rejected', code, message: body?.message };
  }
  return { kind: 'error', status: res.status, message: body?.message };
}

function isUploadErrorCode(code: string | undefined): code is DocUploadErrorCode {
  return (
    code === 'INVALID_FILE_TYPE' ||
    code === 'PDF_CORRUPTED' ||
    code === 'PDF_ENCRYPTED' ||
    code === 'PDF_TOO_MANY_PAGES' ||
    code === 'PDF_TOO_MANY_OCR_PAGES' ||
    code === 'FILE_TOO_LARGE'
  );
}

/**
 * M6 — user-facing copy for each upload error code. Pinned by the
 * Stage 3 dispatch (M6 design doc §6.2) so toast strings stay
 * design-controlled. The 413 (`FILE_TOO_LARGE`) entry doubles as the
 * fallback when the gateway/CDN truncates a too-large request without a
 * body (no `code` field), so the user never sees a generic "Upload
 * failed" line for the size cap.
 */
export const UPLOAD_ERROR_COPY: Record<DocUploadErrorCode, string> = {
  INVALID_FILE_TYPE: 'Could not read this file — only .md or .pdf are supported.',
  PDF_CORRUPTED: 'Could not read this PDF — try a different file.',
  PDF_ENCRYPTED:
    'This PDF is password-protected. Please remove the password and try again.',
  PDF_TOO_MANY_PAGES: 'PDF too long (max 200 pages).',
  PDF_TOO_MANY_OCR_PAGES: 'Scanned PDF too long (max 30 pages for OCR).',
  FILE_TOO_LARGE: 'File too large (max 25 MB).',
};

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
 * Multipart file upload variant of `POST /api/docs`. Per spec §6.2 the
 * server accepts a file part + optional `title` + optional `path`.
 *
 * M6 widens the accepted MIME branches from `.md` only to `{.md, .pdf}` —
 * the backend (docs-api) detects the type via PDF magic bytes + falls
 * through to markdown for `.md` per ADR-16. Wire shape on success is
 * identical (`DocDetailDto`); the new `mimeType` field on the response
 * tells the caller which branch the server took.
 *
 * The function name keeps the M2 alias (`importMarkdownDocument`) so older
 * call sites don't churn; the actual semantics are "import a markdown or
 * PDF source file." A future refactor can rename it to `importSourceFile`
 * once every caller is touched.
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
 * post-create / post-delete refresh). Optional `path` narrows to a folder
 * per spec §6.1 (`GET /api/docs?scope=mine&path={folder}`).
 */
export async function fetchMyDocs(options?: {
  path?: string;
}): Promise<DocsResult<MyDocListResponse>> {
  const qs = new URLSearchParams({ scope: 'mine' });
  if (options?.path) qs.set('path', options.path);
  const res = await fetch(`/api/docs?${qs.toString()}`, {
    method: 'GET',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<MyDocListResponse>(res);
}

/**
 * Caller's folder tree — `GET /api/docs/folders` per spec §6.1.
 * Authenticated; surfaces 401 / network errors via the standard
 * `DocsResult` shape.
 */
export async function fetchFolders(): Promise<DocsResult<FolderListResponse>> {
  const res = await fetch('/api/docs/folders', {
    method: 'GET',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<FolderListResponse>(res);
}

/**
 * Like / unlike toggle — `POST` / `DELETE /api/docs/{id}/like` per spec
 * §6.1. Both verbs return 204 on success; both are idempotent at the
 * server. The caller (LikeButton) keeps optimistic count + likedByMe
 * state and rolls back on non-`ok` results.
 */
export async function likeDocument(id: string): Promise<DocsResult<void>> {
  const res = await fetch(`/api/docs/${encodeURIComponent(id)}/like`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<void>(res);
}

export async function unlikeDocument(id: string): Promise<DocsResult<void>> {
  const res = await fetch(`/api/docs/${encodeURIComponent(id)}/like`, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseResult<void>(res);
}

/**
 * Fire-and-forget view increment — `POST /api/docs/{id}/view` per spec
 * §6.1. Anonymous-OK; backend dedups via the `PLAYGROUND_ANON` cookie
 * (24h TTL per ADR-12 §10). Returns 204 regardless of outcome; we don't
 * surface errors to the reader.
 */
export async function incrementView(id: string): Promise<void> {
  try {
    await fetch(`/api/docs/${encodeURIComponent(id)}/view`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: browserHeaders(),
      keepalive: true,
    });
  } catch {
    // Intentional swallow — view-counter failures must not surface to
    // the reader (spec §10.1 robustness rule).
  }
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

/**
 * M6 — multipart upload size cap. PDFs in particular are large; the
 * backend (ADR-16) enforces 25 MB for the source file. The .md path stays
 * subject to {@link MAX_BODY_BYTES} once extracted, but on the wire the
 * upload itself is gated by this byte budget. Client-side check keeps an
 * over-cap file from leaving the browser.
 */
export const MAX_UPLOAD_BYTES = 25 * 1_048_576; // 25 MB

export function bodyByteSize(body: string): number {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(body).length;
  }
  // Fallback (older runtimes) — approximate; client always has TextEncoder.
  return Buffer.byteLength(body, 'utf8');
}

/**
 * M6 — narrow predicate for "this document was uploaded as a PDF."
 * Treats any unknown / absent `mimeType` as markdown so the frontend stays
 * stable against pre-M6 backend responses (the field rolls out under a
 * forward-compatible schema change — the column exists by the time M6 PR
 * lands, but a re-deployed older snapshot still serves rows without it).
 */
export function isPdfSourced(doc: { mimeType?: DocMimeType }): boolean {
  return doc.mimeType === 'application/pdf';
}

// -------------------- M6.1 extraction-status helpers ----------------------

/**
 * In-flight (not-yet-terminal) extraction state — body is empty, the reader
 * surface should render the Analyzing skeleton instead of the markdown
 * pipeline.
 */
export function isExtractionInFlight(status: ExtractionStatus | undefined): boolean {
  return status === 'pending_extraction' || status === 'extracting';
}

/**
 * The extraction failed and the body never materialized. Distinct from
 * "in-flight" because the UI shows an error card (with the reason +
 * download-original affordance) rather than a skeleton.
 */
export function isExtractionFailed(status: ExtractionStatus | undefined): boolean {
  return status === 'failed';
}

/**
 * Terminal success — body is materialized; render normally. Pre-M6.1 rows
 * (status absent) and synchronous-path uploads (`pending` / `extracted`)
 * both land here.
 */
export function isExtractionTerminal(status: ExtractionStatus | undefined): boolean {
  return status === undefined || status === 'extracted' || status === 'pending';
}

/**
 * Does the document carry an original blob in MinIO that the user can
 * download? Reads the explicit `hasOriginal` field the M6.1 backend
 * surfaces. Pre-M6.1 rows (absent field) fall back to the legacy PDF-only
 * heuristic so freshly-deployed clients on still-old data render sensibly.
 */
export function hasOriginalBlob(doc: {
  mimeType?: DocMimeType;
  hasOriginal?: boolean;
}): boolean {
  if (typeof doc.hasOriginal === 'boolean') {
    return doc.hasOriginal;
  }
  return isPdfSourced(doc);
}
