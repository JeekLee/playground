/**
 * SSE consumer for `POST /api/rag/chat/sessions/{id}/messages`.
 *
 * Why hand-rolled (not `EventSource`):
 * - `EventSource` only does `GET` and cannot carry a JSON request body.
 *   The chat turn endpoint is a POST with `{ message }` payload per spec
 *   §5.1, so we drive the SSE wire format ourselves via `fetch()` +
 *   `ReadableStream` and parse `event: foo\ndata: {…}\n\n` frames.
 * - This also lets us pass `AbortSignal` directly, which is the entire
 *   Stop-button contract (P95 ≤ 200ms abort per spec §12 / ADR-14 §13).
 *
 * Wire grammar per spec §5.2:
 *   one `retrieval` event, ≥0 `token` events, exactly one terminal
 *   (`done` OR `error`).
 *
 * Consumers (the React hook in `features/chat-stream/`) get a single
 * callback `onEvent(parsed)` for every emitted event and a Promise that
 * resolves on terminal (done / error / aborted) or rejects only on
 * transport-level failure (network drop without an `error` frame, or
 * a 4xx HTTP status before the stream opens).
 *
 * Pre-stream HTTP errors (the request itself returns 401 / 404 / 413 /
 * 415 / 429 / 503 per spec §5.1) surface as a synthetic `error` event
 * with the matching `code` so the consumer has a single channel for
 * "this turn failed" — the banner widgets don't need to know whether the
 * failure was pre-stream HTTP or mid-stream SSE.
 */

import type {
  DoneEventPayload,
  ErrorEventPayload,
  RetrievalEventPayload,
  SseErrorCode,
  TokenEventPayload,
} from './chat';

/** Discriminated union of the four SSE event types per spec §5.2. */
export type ChatStreamEvent =
  | { type: 'retrieval'; payload: RetrievalEventPayload }
  | { type: 'token'; payload: TokenEventPayload }
  | { type: 'done'; payload: DoneEventPayload }
  | { type: 'error'; payload: ErrorEventPayload };

export interface StartChatStreamOptions {
  sessionId: string;
  /** The user-turn text body — ≤ 4 KB per spec §5.1. */
  message: string;
  /** Wires the Stop / tab-switch abort path. */
  signal: AbortSignal;
  onEvent: (event: ChatStreamEvent) => void;
}

/**
 * Pre-stream HTTP error → synthetic `error` event so the consumer has a
 * single failure channel. Mapping per spec §5.1.
 */
function syntheticErrorFromStatus(status: number, retryAfterHeader: string | null): ErrorEventPayload {
  const retryAfter = retryAfterHeader ? Number(retryAfterHeader) : undefined;
  const finiteRetryAfter = retryAfter && Number.isFinite(retryAfter) ? retryAfter : undefined;
  let code: SseErrorCode = 'INTERNAL';
  let message = `HTTP ${status}`;
  if (status === 401) {
    code = 'INTERNAL';
    message = 'Sign in to continue chatting.';
  } else if (status === 404) {
    code = 'INTERNAL';
    message = 'This conversation no longer exists.';
  } else if (status === 413) {
    code = 'INTERNAL';
    message = 'Message is too long (max 4 KB).';
  } else if (status === 415) {
    code = 'INTERNAL';
    message = 'Streaming protocol error.';
  } else if (status === 429) {
    code = 'RATE_LIMIT';
    message = "You've hit your hourly limit.";
  } else if (status >= 500) {
    code = 'GATEWAY_DOWN';
    message = 'AI service is currently unavailable.';
  }
  return finiteRetryAfter !== undefined
    ? { code, message, retryAfter: finiteRetryAfter }
    : { code, message };
}

/** Read the XSRF token Spring Security exposes via the `XSRF-TOKEN` cookie. */
function getXsrfToken(): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return match && match[1] ? decodeURIComponent(match[1]) : null;
}

/**
 * Parse a single SSE frame (lines separated by `\n`, terminated by
 * `\n\n`) into a `ChatStreamEvent`. Unknown event types are dropped
 * silently (forward-compat — a future server might add `usage` or
 * `tool_call` events).
 */
function parseFrame(rawFrame: string): ChatStreamEvent | null {
  let eventName: string | null = null;
  const dataLines: string[] = [];
  for (const line of rawFrame.split('\n')) {
    if (line.startsWith(':')) continue; // comment / keep-alive
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      // SSE spec: a data line's leading single space is stripped if present.
      const raw = line.slice('data:'.length);
      dataLines.push(raw.startsWith(' ') ? raw.slice(1) : raw);
    }
  }
  if (eventName === null || dataLines.length === 0) return null;
  const dataText = dataLines.join('\n');
  let parsed: unknown;
  try {
    parsed = JSON.parse(dataText);
  } catch {
    return null;
  }
  switch (eventName) {
    case 'retrieval':
      return { type: 'retrieval', payload: parsed as RetrievalEventPayload };
    case 'token':
      return { type: 'token', payload: parsed as TokenEventPayload };
    case 'done':
      return { type: 'done', payload: parsed as DoneEventPayload };
    case 'error':
      return { type: 'error', payload: parsed as ErrorEventPayload };
    default:
      return null;
  }
}

/**
 * Subscribe to a chat turn. Returns a Promise that resolves when the
 * stream ends (terminal `done`/`error`, abort, or transport drop). The
 * `signal` parameter is the abort channel — wire it to the Stop button
 * and to the React effect cleanup (tab switch / unmount).
 *
 * The promise never rejects under normal operation — every failure mode
 * (HTTP pre-stream, mid-stream drop, abort) is reported through
 * `onEvent` as a terminal `error` event so the UI has a single source
 * of truth.
 */
export async function startChatStream(options: StartChatStreamOptions): Promise<void> {
  const { sessionId, message, signal, onEvent } = options;
  const xsrf = getXsrfToken();

  let res: Response;
  try {
    res = await fetch(`/api/rag/chat/sessions/${encodeURIComponent(sessionId)}/messages`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        accept: 'text/event-stream',
        'content-type': 'application/json',
        ...(xsrf ? { 'X-XSRF-TOKEN': xsrf } : {}),
      },
      body: JSON.stringify({ message }),
      signal,
    });
  } catch (err) {
    // Network drop OR abort before the request opened. Distinguish.
    if (signal.aborted) {
      onEvent({ type: 'error', payload: { code: 'ABORTED', message: 'Generation stopped' } });
      return;
    }
    const text = err instanceof Error ? err.message : 'Network error';
    onEvent({ type: 'error', payload: { code: 'INTERNAL', message: text } });
    return;
  }

  if (!res.ok || !res.body) {
    onEvent({ type: 'error', payload: syntheticErrorFromStatus(res.status, res.headers.get('Retry-After')) });
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let terminalSeen = false;

  try {
    // Reading until the stream closes; aborts surface as a thrown
    // `AbortError` from `reader.read()`.
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // SSE frames separated by a blank line — handle both \n\n and \r\n\r\n.
      let sep: number;
      while ((sep = findFrameBoundary(buffer)) !== -1) {
        const rawFrame = buffer.slice(0, sep);
        buffer = buffer.slice(sep).replace(/^(\r?\n){1,2}/, '');
        const ev = parseFrame(rawFrame);
        if (!ev) continue;
        onEvent(ev);
        if (ev.type === 'done' || ev.type === 'error') {
          terminalSeen = true;
        }
      }
    }
  } catch (err) {
    if (signal.aborted) {
      if (!terminalSeen) {
        onEvent({ type: 'error', payload: { code: 'ABORTED', message: 'Generation stopped' } });
      }
      return;
    }
    if (!terminalSeen) {
      const text = err instanceof Error ? err.message : 'Stream interrupted';
      onEvent({ type: 'error', payload: { code: 'INTERNAL', message: text } });
    }
    return;
  }

  // Stream ended without a terminal frame — treat as transport drop.
  if (!terminalSeen) {
    onEvent({ type: 'error', payload: { code: 'INTERNAL', message: 'Stream ended unexpectedly' } });
  }
}

/** Find the end of the first complete SSE frame in the buffer. */
function findFrameBoundary(buf: string): number {
  const nn = buf.indexOf('\n\n');
  const rnrn = buf.indexOf('\r\n\r\n');
  if (nn === -1) return rnrn;
  if (rnrn === -1) return nn;
  return Math.min(nn, rnrn);
}
