import 'server-only';
import { headers } from 'next/headers';
import {
  parseResult,
  type DocDetailDto,
  type DocsResult,
  type MyDocListResponse,
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
