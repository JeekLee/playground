'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatStreamEvent } from '@/shared/api/chat.sse';
import { resumeChatStream, startChatStream } from '@/shared/api/chat.sse';
import type {
  Citation,
  ErrorPayload,
  StreamingTurn,
  StreamingTurnStatus,
} from '@/entities/chat';

/**
 * `useChatStream` — React hook owning the lifecycle of one in-flight
 * chat turn (the `POST /api/rag/chat` SSE stream per spec §5.1).
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
 * Server-side persistence is decoupled from this SSE connection (spec
 * §6.1 step 12, revised 2026-05-18). Abort / unmount / navigate-away
 * close the stream on the client, but the server pipeline keeps
 * running to completion — the assistant message + citations land in
 * `chat.messages` either way. A returning client can attach to the
 * still-running flux via `resume(sessionId)` and continue seeing live
 * tokens (spec §6.4) — when there's nothing in flight the server
 * answers 404 and `resume` reports `'no-active-turn'` so the caller
 * just renders history.
 */

export interface UseChatStreamApi {
  /** The in-flight turn — null between streams. */
  turn: StreamingTurn | null;
  /** True while `start` / `resume` is awaiting its terminal event. */
  isStreaming: boolean;
  /**
   * Begin a turn. Resolves on terminal event with the final status the
   * UI should react to (mostly for tests; the same status is reflected
   * in `turn.status`).
   */
  start: (sessionId: string, message: string) => Promise<StreamingTurnStatus>;
  /**
   * Attach to an in-flight chat turn on the server. Returns
   * `'no-active-turn'` when no live turn exists for the session (the
   * caller renders history and returns to idle); otherwise resolves
   * with the terminal status once the live stream finishes.
   */
  resume: (sessionId: string) => Promise<StreamingTurnStatus | 'no-active-turn'>;
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
      const state = { terminalStatus: 'aborted' as StreamingTurnStatus };

      setIsStreaming(true);
      setTurn({
        clientId,
        role: 'assistant',
        content: '',
        citations: [],
        status: 'thinking',
        toolCards: [],
      });

      await startChatStream({
        sessionId,
        message,
        signal: controller.signal,
        onEvent: makeOnEvent(clientId, setTurn, state),
      });

      setIsStreaming(false);
      if (abortRef.current === controller) {
        abortRef.current = null;
      }
      return state.terminalStatus;
    },
    [stop],
  );

  const resume = useCallback(
    async (sessionId: string): Promise<StreamingTurnStatus | 'no-active-turn'> => {
      // Same single-stream invariant as `start` — a resume attempt aborts
      // any prior in-flight stream owned by this hook.
      stop();
      const controller = new AbortController();
      abortRef.current = controller;
      const clientId = newClientId();
      const state = { terminalStatus: 'aborted' as StreamingTurnStatus };

      // DO NOT promote the composer to "streaming" mode preemptively.
      // Every page-mount / tab-switch fires a resume attempt and most
      // return 404 because no turn is in flight — flashing the streaming
      // UI between request fire and the 404 response would briefly make
      // the composer reject Send clicks (it switches to Stop). Only flip
      // the streaming flag once we've actually seen a real SSE event,
      // and only flip it back if we flipped it forward.
      const onUnderlying = makeOnEvent(clientId, setTurn, state);
      let attached = false;
      const onEvent: typeof onUnderlying = (ev) => {
        if (!attached) {
          attached = true;
          setIsStreaming(true);
          setTurn({
            clientId,
            role: 'assistant',
            content: '',
            citations: [],
            status: 'thinking',
            toolCards: [],
          });
        }
        onUnderlying(ev);
      };

      const outcome = await resumeChatStream({
        sessionId,
        signal: controller.signal,
        onEvent,
      });

      if (abortRef.current === controller) {
        abortRef.current = null;
      }

      if (outcome === 'no-active-turn') {
        // Never attached — no UI to revert.
        return 'no-active-turn';
      }
      if (attached) {
        setIsStreaming(false);
      }
      return state.terminalStatus;
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

  return { turn, isStreaming, start, resume, stop, reset };
}

/**
 * Per-stream SSE event handler. Shared between `start` and `resume`
 * because the wire grammar (and therefore the state-machine for
 * accumulating tokens / promoting status / capturing the terminal
 * error) is identical regardless of how the stream was opened.
 */
function makeOnEvent(
  clientId: string,
  setTurn: (updater: (prev: StreamingTurn | null) => StreamingTurn | null) => void,
  state: { terminalStatus: StreamingTurnStatus },
): (ev: ChatStreamEvent) => void {
  let accumulated = '';
  return (ev) => {
    if (ev.type === 'phase') {
      // Progress update — display the server-supplied label inline
      // while the assistant body is still empty (spec §5.2 revised).
      // We stay in `thinking` status until the first token arrives.
      const label = ev.payload.label;
      setTurn((prev) =>
        prev && prev.clientId === clientId
          ? { ...prev, phaseLabel: label, status: 'thinking' }
          : prev,
      );
    } else if (ev.type === 'token') {
      accumulated += ev.payload.delta;
      const snapshot = accumulated;
      setTurn((prev) =>
        prev && prev.clientId === clientId
          ? { ...prev, content: snapshot, status: 'streaming', phaseLabel: undefined }
          : prev,
      );
    } else if (ev.type === 'done') {
      // Cited subset arrives with the terminal event (PR B). Apply it
      // to the streaming turn so the just-completed bubble shows its
      // citation cards before ChatPage swaps to the loaded history row.
      const citations: Citation[] = ev.payload.citations ?? [];
      state.terminalStatus = 'done';
      setTurn((prev) =>
        prev && prev.clientId === clientId
          ? { ...prev, citations, status: 'done', phaseLabel: undefined }
          : prev,
      );
    } else if (ev.type === 'error') {
      const terminalStatus: StreamingTurnStatus =
        ev.payload.code === 'ABORTED' ? 'aborted' : 'error';
      const terminalError: ErrorPayload = ev.payload;
      state.terminalStatus = terminalStatus;
      setTurn((prev) =>
        prev && prev.clientId === clientId
          ? {
              ...prev,
              status: terminalStatus,
              error: terminalError,
              phaseLabel: undefined,
            }
          : prev,
      );
    }
  };
}
