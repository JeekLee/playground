import 'server-only';
import { headers } from 'next/headers';
import {
  parseChatResult,
  type ChatResult,
  type SessionListResponse,
  type SessionMessagesResponse,
} from './chat';

/**
 * Chat API — server-only helpers. Same gateway routing as
 * `./chat.ts`, but with inbound-cookie forwarding so the gateway can
 * resolve `PLAYGROUND_SESSION` during SSR.
 *
 * Marked with `server-only` so any accidental import from a client
 * component fails the Next.js build instantly.
 */

function gatewayBaseUrl(): string {
  return process.env.GATEWAY_INTERNAL_URL ?? 'http://playground-backend-gateway:18080';
}

function inboundCookieHeader(): string {
  return headers().get('cookie') ?? '';
}

async function serverFetch<T>(path: string): Promise<ChatResult<T>> {
  try {
    const res = await fetch(`${gatewayBaseUrl()}${path}`, {
      headers: {
        accept: 'application/json',
        cookie: inboundCookieHeader(),
      },
      cache: 'no-store',
    });
    return parseChatResult<T>(res);
  } catch (err) {
    return {
      kind: 'error',
      status: 0,
      message: err instanceof Error ? err.message : 'network error',
    };
  }
}

/**
 * Fetch the caller's session list (server-side). Used by the `/chat`
 * SSR pass to render the top tab strip on first paint with no client
 * waterfall.
 */
export async function fetchSessionsServerSide(): Promise<ChatResult<SessionListResponse>> {
  return serverFetch<SessionListResponse>('/api/chat/sessions');
}

/**
 * Fetch one session's full message + citation history (server-side).
 * Used when the SSR pass already knows which session is active (e.g.,
 * `/chat?sessionId={uuid}` deeplink or "most-recent" default).
 */
export async function fetchSessionMessagesServerSide(
  sessionId: string,
): Promise<ChatResult<SessionMessagesResponse>> {
  return serverFetch<SessionMessagesResponse>(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
  );
}
