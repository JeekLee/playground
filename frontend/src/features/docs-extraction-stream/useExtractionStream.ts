'use client';

import { useEffect, useRef, useState } from 'react';
import type { ExtractionStatus } from '@/entities/document';

/**
 * M6.1 ADR-12 §A12.5 — subscribe to a document's extraction lifecycle via
 * Server-Sent Events.
 *
 * The hook opens a single {@link EventSource} against
 * `GET /api/docs/{id}/extraction-stream`. The session cookie rides along
 * automatically because the request is same-origin (the gateway exposes the
 * stream under `/api/docs/**`). The server pushes the current state on
 * connect (snapshot) and every subsequent transition; on terminal status
 * (`extracted` / `failed`) the server completes the stream and the hook
 * closes the EventSource.
 *
 * Wire grammar (verbatim from {@code SseEmitterRegistry}):
 *   event: extracting         | extracted        | failed
 *   data:  {"status":"extracting","pageDone":3,"pageTotal":42}
 *
 * The event `name` always equals the wire `status` value — both are read
 * here, with the JSON payload winning when they disagree (defensive against
 * future server changes). Comment-only frames (`:ping`, sent every 30s as a
 * keepalive per ADR-12 §A12.14) never arrive on `EventSource.onmessage` or
 * the named listeners; the browser swallows them.
 *
 * Inputs:
 *   - `docId` — the document UUID. The hook is a no-op when null/undefined,
 *     so callers can mount it unconditionally and gate via the prop.
 *   - `initialStatus` — the status SSR'd from `GET /api/docs/{id}`. When
 *     terminal, the hook still opens the EventSource (the server's
 *     snapshot-on-connect re-confirms; the stream completes immediately).
 *     This keeps the hook the single source of truth for the live state,
 *     even when the page hydrated mid-flight and the initial snapshot is
 *     already stale.
 *   - `initialReason` — paired with `initialStatus === 'failed'` so the
 *     reader can render the failure card without waiting for an SSE event.
 *
 * Returns a stable `{ status, reason, pageDone, pageTotal, connectionLost }`
 * tuple. `connectionLost` is set to true when {@link EventSource}'s
 * `onerror` fires AFTER an initial successful connection AND the stream
 * never reached a terminal status — i.e., the browser will keep
 * reconnecting on its own (we don't unsubscribe in that case) but the user
 * deserves a hint that the displayed state may be stale.
 *
 * Cleanup: the EventSource is closed on (a) terminal status, (b) hook
 * unmount, (c) docId change. React Strict-Mode double-mount is harmless —
 * the second mount opens a parallel EventSource that the server tolerates
 * (multiple concurrent emitters per doc per {@code SseEmitterRegistry}).
 */

export interface ExtractionStreamState {
  status: ExtractionStatus | undefined;
  reason: string | null;
  pageDone: number | null;
  pageTotal: number | null;
  /**
   * Set to true if the EventSource dropped after at least one successful
   * connect AND the stream never finished. EventSource auto-reconnects in
   * the background; this flag is informational (the UI surfaces a small
   * "connection lost — refresh to retry" hint). Reset to false whenever a
   * fresh event arrives.
   */
  connectionLost: boolean;
}

export interface UseExtractionStreamOptions {
  docId: string | null | undefined;
  initialStatus: ExtractionStatus | undefined;
  initialReason?: string | null;
  /**
   * Optional callback fired once when the stream transitions to a terminal
   * status (`extracted` or `failed`). The doc-detail page uses this hook to
   * trigger a `router.refresh()` so the SSR'd body re-fetches with the
   * populated content.
   */
  onTerminal?: (final: ExtractionStatus) => void;
}

interface SsePayload {
  status?: string;
  reason?: string | null;
  pageDone?: number;
  pageTotal?: number;
}

function parseStatus(raw: unknown): ExtractionStatus | undefined {
  if (typeof raw !== 'string') return undefined;
  switch (raw) {
    case 'pending':
    case 'pending_extraction':
    case 'extracting':
    case 'extracted':
    case 'failed':
      return raw;
    default:
      return undefined;
  }
}

export function useExtractionStream(options: UseExtractionStreamOptions): ExtractionStreamState {
  const { docId, initialStatus, initialReason = null, onTerminal } = options;
  const [state, setState] = useState<ExtractionStreamState>({
    status: initialStatus,
    reason: initialReason,
    pageDone: null,
    pageTotal: null,
    connectionLost: false,
  });

  // Keep the latest onTerminal in a ref so the effect doesn't re-tear down
  // the EventSource each time the parent rerenders with a new closure.
  const onTerminalRef = useRef(onTerminal);
  useEffect(() => {
    onTerminalRef.current = onTerminal;
  }, [onTerminal]);

  // Last-seen status — used to suppress duplicate setState on idempotent
  // re-emits (EventSource auto-reconnect → snapshot → re-snapshot).
  const lastStatusRef = useRef<ExtractionStatus | undefined>(initialStatus);

  useEffect(() => {
    if (!docId) return;
    // Reset the local state when the doc id changes so we don't carry over
    // a previous doc's `pageDone` into the new one.
    lastStatusRef.current = initialStatus;
    setState({
      status: initialStatus,
      reason: initialReason,
      pageDone: null,
      pageTotal: null,
      connectionLost: false,
    });

    // EventSource is browser-only; SSR / Node test environments lack it.
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') return;

    const url = `/api/docs/${encodeURIComponent(docId)}/extraction-stream`;
    // `withCredentials: true` ensures the same-origin session cookie rides
    // along on the streaming GET — cross-origin deployments (browser →
    // gateway on a different host) need this; same-origin is unaffected.
    const source = new EventSource(url, { withCredentials: true });

    let hasOpened = false;
    let closed = false;

    const handlePayload = (raw: string) => {
      let payload: SsePayload;
      try {
        payload = JSON.parse(raw) as SsePayload;
      } catch {
        return;
      }
      const status = parseStatus(payload.status);
      if (!status) return;
      const reason = payload.reason ?? null;
      const pageDone = typeof payload.pageDone === 'number' ? payload.pageDone : null;
      const pageTotal = typeof payload.pageTotal === 'number' ? payload.pageTotal : null;

      const previous = lastStatusRef.current;
      lastStatusRef.current = status;

      setState((prev) => {
        // Suppress duplicate updates when status + counters all match — a
        // reconnect's snapshot re-fire shouldn't ping listeners or rerender.
        if (
          prev.status === status &&
          prev.reason === reason &&
          prev.pageDone === pageDone &&
          prev.pageTotal === pageTotal &&
          !prev.connectionLost
        ) {
          return prev;
        }
        return {
          status,
          reason,
          pageDone,
          pageTotal,
          connectionLost: false,
        };
      });

      if (
        (status === 'extracted' || status === 'failed') &&
        previous !== status &&
        onTerminalRef.current
      ) {
        onTerminalRef.current(status);
      }

      if (status === 'extracted' || status === 'failed') {
        closed = true;
        source.close();
      }
    };

    const onNamedEvent = (event: MessageEvent) => handlePayload(event.data);

    source.addEventListener('extracting', onNamedEvent);
    source.addEventListener('pending_extraction', onNamedEvent);
    source.addEventListener('extracted', onNamedEvent);
    source.addEventListener('failed', onNamedEvent);
    // Fallback for any unnamed 'message' frames (server currently names
    // every event, but tolerate the un-named variant for forward compat).
    source.onmessage = onNamedEvent;

    source.onopen = () => {
      hasOpened = true;
      // Recovering from a drop — clear the "connection lost" hint; the
      // snapshot-on-connect will re-deliver the current state.
      setState((prev) => (prev.connectionLost ? { ...prev, connectionLost: false } : prev));
    };

    source.onerror = () => {
      // EventSource fires `onerror` on every transport hiccup — including
      // the natural close after a terminal `extracted`/`failed` event when
      // the server completes the stream. Only treat it as a "lost
      // connection" when we never finalized AND we had an open before.
      if (closed) return;
      if (!hasOpened) return;
      // Server is reachable but the stream dropped; the browser will keep
      // reconnecting on its own — surface the staleness to the user.
      setState((prev) => (prev.connectionLost ? prev : { ...prev, connectionLost: true }));
    };

    return () => {
      closed = true;
      source.close();
    };
    // initialStatus / initialReason are intentionally only consumed on doc
    // change; the SSE stream is the live source of truth after mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId]);

  return state;
}
