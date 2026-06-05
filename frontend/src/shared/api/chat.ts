/**
 * RAG-Chat API client (browser-safe). Routed through the gateway per
 * ADR-07 + ADR-14 §2:
 *   /api/rag/chat/**          → rag-chat-api  /api/rag/chat/**
 *
 * Wire shapes mirror the M4 spec
 * (`docs/superpowers/specs/2026-05-18-m4-rag-chat-design.md`) §5 + §10
 * verbatim. The single streaming endpoint (`POST /api/rag/chat` with body
 * `{ sessionId, message }`) is consumed via the SSE consumer in
 * `./chat.sse.ts`; this module owns the JSON DTO shapes + the
 * non-streaming session-CRUD calls.
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

/** Wire shape for a tool-produced file attachment on a historical message (ADR-20 §D4). */
export interface AttachmentWireDto {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  /** Gateway-relative download URL: `/api/rag/chat/attachments/{id}`. */
  downloadUrl: string;
  toolName: string;
  /** Document title of the brief that produced this artifact. Absent on legacy rows. */
  briefTitle?: string;
}

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
  /** Present on assistant rows that produced a tool artifact (ADR-20 §D4). */
  attachment?: AttachmentWireDto | null;
}

export interface SessionMessagesResponse {
  messages: MessageDto[];
}

// -------------------- SSE event payloads (spec §5.2 revised in PR B) -----

/**
 * `event: phase` — progress / status update during a chat turn. The
 * frontend renders the {@link label} verbatim while the stream
 * accumulates; {@link step} is the machine-readable discriminator the
 * UI may use to pick a specific icon (retrieval, tool_call, thinking,
 * generating…). {@link data} is BC-specific — rag-chat's
 * {@code retrieval} step carries {@code { count: number }}.
 */
export interface PhaseEventPayload {
  step: string;
  label: string;
  data?: Record<string, unknown>;
}

/** `event: token` — streamed token delta from the model. */
export interface TokenEventPayload {
  delta: string;
}

/**
 * `event: done` — terminal success. Citations now arrive here
 * (cited subset only — see spec §5.2 / §6.1 step 12 revised
 * 2026-05-19); the legacy `event: retrieval` is gone.
 */
export interface DoneEventPayload {
  messageId: string;
  tokensIn: number;
  tokensOut: number;
  citations?: MessageCitationDto[];
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

// -------------------- M7 / M8 tool-calling SSE payloads ------------------
//
// Per ADR-17 §3.1 (M7 invariant) the dispatcher emits three standalone
// events for every tool round-trip:
//
//   event: tool_call     {id, name, args}
//   event: tool_result   {id, name, summary, outputUrl?, programJson?, metadata?}
//   event: tool_error    {id, name, code, message}
//
// The `code` field on `tool_error` is the M7 wire-level enum (transport
// classification: 4xx/5xx/timeout/etc.). M8 layers its domain code on
// TOP of the message field via the prefix grammar pinned in ADR-18 §6
// ("<DOMAIN_CODE>: <human-readable>"). The frontend's
// `parseM8ErrorPrefix` helper in `features/chat-tool-card/` is the
// SINGLE site that splits the prefix off.
//
// The shape is intentionally generic (Record<string, unknown> for args /
// metadata / programJson) so future tool BCs (slide-gen, image-gen, …)
// don't need a wire-level schema change. The M8-specific narrowing
// happens in `features/chat-tool-card/`.

/**
 * Per ADR-17 §2 — wire-level classification the dispatcher applies
 * to every tool round-trip. 4xx maps to `UPSTREAM_4XX` (NOT counted
 * against the per-tool breaker per ADR-14 §4 invariant), 5xx / IO
 * exceptions / timeouts map to the matching codes (counted against
 * the breaker). M8 domain codes (`BRIEF_EXTRACTION_FAILED`, …) live
 * as a prefix on `message`, not in this enum.
 */
export type ToolErrorCode =
  | 'TIMEOUT'
  | 'CIRCUIT_OPEN'
  | 'MAX_DEPTH'
  | 'UPSTREAM_4XX'
  | 'UPSTREAM_5XX'
  | 'SCHEMA_INVALID'
  | 'INTERNAL';

/** `event: tool_call` — LLM decided to invoke a tool (M7). */
export interface ToolCallEventPayload {
  id: string;
  name: string;
  /** Arguments the LLM resolved against the descriptor's parameterSchema. */
  args: Record<string, unknown>;
}

/**
 * `event: tool_result` — tool returned successfully (M7).
 *
 * Wire shape from the backend is `{id, name, result: <tool-body>}`.
 * The SSE parser in `chat.sse.ts` flattens `result.*` into this payload
 * and maps `result.fileUrl` → `outputUrl`. For M8 the download URL is
 * `/api/rag/chat/attachments/{uuid}` per ADR-20 §D4.
 *
 * `programJson` / `metadata` are tool-specific opaque blobs extracted
 * from the result body. For M8 `programJson` matches the JSON Schema
 * pinned in ADR-18 §9.
 */
export interface ToolResultEventPayload {
  id: string;
  name: string;
  /** One-line user-facing summary. M8 emits Korean per ADR-18 §5. Optional for plain M7 tools. */
  summary?: string;
  /** Relative download URL for file-producing tools. */
  outputUrl?: string;
  /** Document title of the brief that produced this artifact (M8). */
  briefTitle?: string;
  /** Tool-specific structured payload. M8: see {@link MassingProgramJson}. */
  programJson?: Record<string, unknown>;
  /** Reserved for non-file tools (e.g. image-gen returning an inline preview). */
  metadata?: Record<string, unknown>;
}

/**
 * `event: tool_error` — tool failed (M7). The `code` is the wire-level
 * M7 enum; M8's domain code (e.g. `BRIEF_EXTRACTION_FAILED`) is encoded
 * inside `message` as a prefix grammar `<CODE>: <human-readable>` per
 * ADR-18 §6. Frontend MUST parse via `parseM8ErrorPrefix` only.
 */
export interface ToolErrorEventPayload {
  id: string;
  name: string;
  code: ToolErrorCode;
  message: string;
}

/**
 * M8 `programJson` shape per ADR-18 §9 (the
 * `programJson.schema.json` draft 2020-12 file).
 *
 * Room-split payloads (2026-06-05) carry per-room `zone` / `floor` /
 * `labelAnchor` — split-zone rows have `floor`, unsplit-zone rows don't.
 * Legacy payloads carry `{name, areaM2}` only; every new field is optional
 * so old cards render unchanged.
 *
 * The optional site / floor-height fields land here when the LLM
 * extracted them from the brief; if absent, they fell back to the
 * request params or numerical defaults (ADR-18 §8).
 */
export interface MassingProgramJson {
  rooms: MassingRoom[];
  siteWidthM?: number;
  siteDepthM?: number;
  floorHeightM?: number;
}

export interface MassingRoom {
  name: string;
  areaM2: number;
  /** Owning zone — set on all room-split payloads (color-slot ordering key). */
  zone?: string;
  /** 1-based floor (negative = basement). Present only on split-zone rows. */
  floor?: number;
  /** Hotspot anchor — glTF Y-up coords at the room box's top-face center. */
  labelAnchor?: { x: number; y: number; z: number };
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
