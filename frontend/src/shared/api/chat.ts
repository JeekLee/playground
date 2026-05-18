/**
 * RAG-Chat API client (browser-safe). Routed through the gateway per
 * ADR-07 + ADR-14 §2:
 *   /api/rag/chat/**          → rag-chat-api  /api/rag/chat/**
 *
 * Wire shapes mirror the M4 spec
 * (`docs/superpowers/specs/2026-05-18-m4-rag-chat-design.md`) §5 + §10
 * verbatim. The single streaming endpoint (`POST /api/rag/chat/sessions/{id}/messages`)
 * is consumed via the SSE consumer in `./chat.sse.ts`; this module owns
 * the JSON DTO shapes + the non-streaming session-CRUD calls.
 *
 * Same-origin relative paths — the browser attaches `PLAYGROUND_SESSION`
 * automatically. SSR helpers (which forward the inbound cookie via
 * `next/headers`) live in `./chat.server.ts` so this file stays
 * consumable from client components.
 *
 * Auth: every endpoint requires `X-User-Id` (gateway-injected). Anon
 * callers receive 401, which the FSD layer surfaces as `unauthorized`
 * and the page redirects to `/login?next=/chat`.
 */

// -------------------- DTOs (verbatim from spec §5 + §10) -----------------

/** Spec §5.3 — `POST /api/rag/chat/sessions`. */
export interface CreateSessionResponseDto {
  sessionId: string;
  title: string;
}

/** Spec §5.3 — `GET /api/rag/chat/sessions`. */
export interface SessionListItemDto {
  id: string;
  title: string;
  updatedAt: string;
  messageCount: number;
}

export interface SessionListResponse {
  sessions: SessionListItemDto[];
}

/** Spec §5.3 — `PATCH /api/rag/chat/sessions/{id}` body. */
export interface PatchSessionRequestDto {
  title: string;
}

/**
 * Spec §5.3 — `GET /api/rag/chat/sessions/{id}/messages`. Citations carry
 * the resolved title + excerpt (server-side JOIN to `docs.documents` +
 * `rag.document_chunks` per ADR-14 §3, §11). When the cited doc was
 * deleted (stale citation per ADR-14 §11), `title` is null and `excerpt`
 * is absent; the frontend renders `(deleted) — 이 문서는 더 이상 사용할 수
 * 없습니다`.
 */
export type CitationVisibility = 'public' | 'private';

export interface MessageCitationDto {
  /** 1-indexed; matches `[N]` markers in the message body. */
  n: number;
  documentId: string;
  chunkIndex: number;
  /** Null when the source document has been deleted post-cite (ADR-14 §11). */
  title: string | null;
  /** Absent for deleted citations. */
  excerpt?: string;
  /** Absent for deleted citations. */
  visibility?: CitationVisibility;
}

export type MessageRole = 'user' | 'assistant';

export interface MessageDto {
  id: string;
  role: MessageRole;
  /** Raw text. Assistant turns may contain `[N]` markers. */
  content: string;
  tokensIn?: number | null;
  tokensOut?: number | null;
  retrievalK?: number | null;
  createdAt: string;
  /** Empty array on user rows. */
  citations: MessageCitationDto[];
}

export interface SessionMessagesResponse {
  messages: MessageDto[];
}

// -------------------- SSE event payloads (spec §5.2) ---------------------

/**
 * `event: retrieval` — emitted first per spec §5.2. Drives the citation
 * accordion skeleton. Always sent, even when `citations` is empty
 * (`RETRIEVAL_EMPTY` case — the accordion renders `▾ Citations · none`).
 */
export interface RetrievalEventPayload {
  citations: MessageCitationDto[];
}

/** `event: token` — streamed token delta from the model. */
export interface TokenEventPayload {
  delta: string;
}

/** `event: done` — terminal success. */
export interface DoneEventPayload {
  messageId: string;
  tokensIn: number;
  tokensOut: number;
}

/** `event: error` — terminal failure. Codes per spec §6.5. */
export type SseErrorCode =
  | 'GATEWAY_5XX'
  | 'GATEWAY_DOWN'
  | 'RATE_LIMIT'
  | 'RETRIEVAL_EMPTY'
  | 'ABORTED'
  | 'INTERNAL';

export interface ErrorEventPayload {
  code: SseErrorCode;
  message: string;
  /** Seconds — set on RATE_LIMIT and (sometimes) GATEWAY_DOWN. */
  retryAfter?: number;
}

// -------------------- Result type (mirrors docs.ts) ----------------------

export type ChatResult<T> =
  | { kind: 'ok'; value: T }
  | { kind: 'unauthorized' }
  | { kind: 'not-found' }
  | { kind: 'too-large' }
  | { kind: 'rate-limited'; retryAfter?: number }
  | { kind: 'service-unavailable' }
  | { kind: 'error'; status: number; message?: string };

export async function parseChatResult<T>(res: Response): Promise<ChatResult<T>> {
  if (res.status === 401) return { kind: 'unauthorized' };
  if (res.status === 404) return { kind: 'not-found' };
  if (res.status === 413) return { kind: 'too-large' };
  if (res.status === 429) {
    const ra = res.headers.get('Retry-After');
    const retryAfter = ra ? Number(ra) : undefined;
    return { kind: 'rate-limited', retryAfter: Number.isFinite(retryAfter) ? retryAfter : undefined };
  }
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

// -------------------- Client-side helpers (XSRF parity with docs.ts) -----

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

// -------------------- Session CRUD (spec §5.3) ---------------------------

/** Spec §5.3 — `POST /api/rag/chat/sessions`. Returns the freshly-created session. */
export async function createSession(): Promise<ChatResult<CreateSessionResponseDto>> {
  const res = await fetch('/api/rag/chat/sessions', {
    method: 'POST',
    credentials: 'same-origin',
    headers: browserHeaders({ 'content-type': 'application/json' }),
    body: '{}',
  });
  return parseChatResult<CreateSessionResponseDto>(res);
}

/** Spec §5.3 — `GET /api/rag/chat/sessions`. The caller's full list, `updated_at DESC`. */
export async function listSessions(): Promise<ChatResult<SessionListResponse>> {
  const res = await fetch('/api/rag/chat/sessions', {
    method: 'GET',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseChatResult<SessionListResponse>(res);
}

/** Spec §5.3 — `PATCH /api/rag/chat/sessions/{id}` for manual rename. */
export async function renameSession(
  id: string,
  title: string,
): Promise<ChatResult<SessionListItemDto>> {
  const res = await fetch(`/api/rag/chat/sessions/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: browserHeaders({ 'content-type': 'application/json' }),
    body: JSON.stringify({ title } satisfies PatchSessionRequestDto),
  });
  return parseChatResult<SessionListItemDto>(res);
}

/** Spec §5.3 — `DELETE /api/rag/chat/sessions/{id}`. CASCADE per ADR-14 §F. */
export async function deleteSession(id: string): Promise<ChatResult<void>> {
  const res = await fetch(`/api/rag/chat/sessions/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseChatResult<void>(res);
}

/**
 * Spec §5.3 — `GET /api/rag/chat/sessions/{id}/messages`. Loads the full
 * message + citation history for a session. Stale citations (post-doc-delete)
 * carry `title: null` per ADR-14 §11.
 */
export async function fetchSessionMessages(
  id: string,
): Promise<ChatResult<SessionMessagesResponse>> {
  const res = await fetch(`/api/rag/chat/sessions/${encodeURIComponent(id)}/messages`, {
    method: 'GET',
    credentials: 'same-origin',
    headers: browserHeaders(),
  });
  return parseChatResult<SessionMessagesResponse>(res);
}

// -------------------- Constants ------------------------------------------

/** Spec §5.1 — user message cap, enforced both client + server (413 from server). */
export const MAX_MESSAGE_BYTES = 4 * 1024;

export function messageByteSize(message: string): number {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(message).length;
  }
  return Buffer.byteLength(message, 'utf8');
}
