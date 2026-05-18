'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { startChatStream } from '@/shared/api/chat.sse';
import type {
  Citation,
  ErrorPayload,
  StreamingTurn,
  StreamingTurnStatus,
} from '@/entities/chat';

/**
 * `useChatStream` — React hook owning the lifecycle of one in-flight
 * chat turn (the `POST /api/rag/chat/sessions/{id}/messages` SSE stream).
 *
 * Contract:
 * - `start(sessionId, message)` opens the SSE; returns a `Promise<void>`
 *   that resolves on the terminal event (done / error / aborted).
 * - `stop()` aborts the in-flight stream (P95 ≤ 200ms per spec §12 /
 *   ADR-14 §13). Idempotent; safe to call when no stream is active.
 * - `turn` is the in-flight turn (status, accumulated content,
 *   citations from the `retrieval` event, terminal error if any).
 *   Null when no stream is active.
 * - Cleanup: on unmount we abort any active stream — covers the
 *   "user switches tab during stream" case per ADR-14 §14.
 *
 * Per ADR-14 §13: partial assistant text is NOT persisted on abort.
 * The hook does not write to the DB; persistence happens server-side on
 * the `done` event only. Callers that want the persisted message back
 * should refetch via `fetchSessionMessages` after `done` fires.
 */

export interface UseChatStreamApi {
  /** The in-flight turn — null between streams. */
  turn: StreamingTurn | null;
  /** True while `start` is awaiting its terminal event. */
  isStreaming: boolean;
  /**
   * Begin a turn. Resolves on terminal event with the final status the
   * UI should react to (mostly for tests; the same status is reflected
   * in `turn.status`).
   */
  start: (sessionId: string, message: string) => Promise<StreamingTurnStatus>;
  /** Abort the in-flight stream. No-op when nothing is in flight. */
  stop: () => void;
  /** Clear the current turn — call after the consumer has handed off
   *  the persisted message to its history store. */
  reset: () => void;
}

function newClientId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `stream-${crypto.randomUUID()}`;
  }
  return `stream-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

export function useChatStream(): UseChatStreamApi {
  const [turn, setTurn] = useState<StreamingTurn | null>(null);
  const [isStreaming, setIsStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  const stop = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
  }, []);

  const reset = useCallback(() => {
    setTurn(null);
  }, []);

  const start = useCallback(
    async (sessionId: string, message: string): Promise<StreamingTurnStatus> => {
      // If another stream is in flight (it shouldn't be — spec §10 caps
      // concurrent streams per user at 1), abort it first.
      stop();
      const controller = new AbortController();
      abortRef.current = controller;
      const clientId = newClientId();
      let accumulated = '';
      let citations: Citation[] = [];
      let terminalStatus: StreamingTurnStatus = 'aborted';
      let terminalError: ErrorPayload | undefined;

      setIsStreaming(true);
      setTurn({
        clientId,
        role: 'assistant',
        content: '',
        citations: [],
        status: 'thinking',
      });

      await startChatStream({
        sessionId,
        message,
        signal: controller.signal,
        onEvent: (ev) => {
          if (ev.type === 'retrieval') {
            citations = ev.payload.citations;
            setTurn((prev) =>
              prev && prev.clientId === clientId
                ? { ...prev, citations, status: 'streaming' }
                : prev,
            );
          } else if (ev.type === 'token') {
            accumulated += ev.payload.delta;
            const snapshot = accumulated;
            setTurn((prev) =>
              prev && prev.clientId === clientId
                ? { ...prev, content: snapshot, status: 'streaming' }
                : prev,
            );
          } else if (ev.type === 'done') {
            terminalStatus = 'done';
            setTurn((prev) =>
              prev && prev.clientId === clientId
                ? { ...prev, status: 'done' }
                : prev,
            );
          } else if (ev.type === 'error') {
            terminalStatus = ev.payload.code === 'ABORTED' ? 'aborted' : 'error';
            terminalError = ev.payload;
            setTurn((prev) =>
              prev && prev.clientId === clientId
                ? { ...prev, status: terminalStatus, error: terminalError }
                : prev,
            );
          }
        },
      });

      setIsStreaming(false);
      if (abortRef.current === controller) {
        abortRef.current = null;
      }
      return terminalStatus;
    },
    [stop],
  );

  // Abort on unmount — covers the tab-switch case per ADR-14 §14.
  useEffect(() => {
    return () => {
      if (abortRef.current) {
        abortRef.current.abort();
        abortRef.current = null;
      }
    };
  }, []);

  return { turn, isStreaming, start, stop, reset };
}
