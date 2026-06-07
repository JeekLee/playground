'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatStreamEvent } from '@/shared/api/chat.sse';
import { resumeChatStream, startChatStream } from '@/shared/api/chat.sse';
import type {
  Citation,
  ErrorPayload,
  StreamingTurn,
  StreamingTurnStatus,
  ToolCardState,
} from '@/entities/chat';

/**
 * `useChatStream` — React hook owning the lifecycle of one in-flight
 * chat turn (the `POST /api/chat` SSE stream per spec §5.1).
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
    } else if (ev.type === 'tool_call') {
      // LLM dispatched a tool — append an in-flight card. The matching
      // `tool_result` / `tool_error` event (correlated by `id`) lifts
      // it into the populated / error state in place. Until then, the
      // skeleton card with `Running…` summary + spinner sits below the
      // assistant body so the user sees that something is happening.
      const calledAt = Date.now();
      const card: ToolCardState = {
        kind: 'in_flight',
        toolCall: ev.payload,
        calledAt,
        displayName: ev.payload.displayName,
      };
      // Retrieval is over by the time the LLM dispatches a tool, but no
      // token has arrived yet (the first response IS the tool call), so the
      // last `phase` label ("참고 문서 확인 중") would sit stale above the
      // progress card for the whole tool run — clear it here; the tool
      // card's own stage label takes over the progress narration.
      setTurn((prev) =>
        prev && prev.clientId === clientId
          ? { ...prev, toolCards: [...prev.toolCards, card], phaseLabel: undefined }
          : prev,
      );
    } else if (ev.type === 'tool_progress') {
      // Merge the latest progress into the matching in-flight card (keyed
      // by tool-call `id`). The generic ToolRunCard reads `progress` to
      // render the current stage label + pip bar. Non-in_flight cards (the
      // call already resolved) are left untouched — a late progress event
      // after tool_result is a no-op.
      setTurn((prev) =>
        prev && prev.clientId === clientId
          ? {
              ...prev,
              toolCards: prev.toolCards.map((c) =>
                c.kind === 'in_flight' && c.toolCall.id === ev.payload.id
                  ? { ...c, progress: ev.payload }
                  : c,
              ),
            }
          : prev,
      );
    } else if (ev.type === 'tool_result') {
      const resolvedAt = Date.now();
      setTurn((prev) =>
        prev && prev.clientId === clientId
          ? { ...prev, toolCards: applyToolResult(prev.toolCards, ev.payload, resolvedAt) }
          : prev,
      );
    } else if (ev.type === 'tool_error') {
      const resolvedAt = Date.now();
      setTurn((prev) =>
        prev && prev.clientId === clientId
          ? { ...prev, toolCards: applyToolError(prev.toolCards, ev.payload, resolvedAt) }
          : prev,
      );
    }
  };
}

/**
 * Match a `tool_result` payload to its in-flight predecessor by `id`
 * and transition that card to the `result` state. If no in-flight
 * card with the same `id` exists (e.g., the result arrived before
 * the call — should not happen per ADR-17 §3.1's ordering invariant,
 * but defensive), we append a fresh `result` card with a synthetic
 * `toolCall` derived from the result so the UI degrades gracefully.
 */
function applyToolResult(
  cards: ToolCardState[],
  payload: import('@/entities/chat').ToolResultPayload,
  resolvedAt: number,
): ToolCardState[] {
  const idx = cards.findIndex((c) => c.toolCall.id === payload.id && c.kind === 'in_flight');
  if (idx === -1) {
    return [
      ...cards,
      {
        kind: 'result',
        toolCall: { id: payload.id, name: payload.name, args: {} },
        toolResult: payload,
        calledAt: resolvedAt,
        resolvedAt,
      },
    ];
  }
  const next = cards.slice();
  const previous = next[idx] as Extract<ToolCardState, { kind: 'in_flight' }>;
  next[idx] = {
    kind: 'result',
    toolCall: previous.toolCall,
    toolResult: payload,
    calledAt: previous.calledAt,
    resolvedAt,
    // Preserve the server-supplied display name so the generic result
    // card titles an unregistered tool the same way ToolRunCard did.
    displayName: previous.displayName,
  };
  return next;
}

function applyToolError(
  cards: ToolCardState[],
  payload: import('@/entities/chat').ToolErrorPayload,
  resolvedAt: number,
): ToolCardState[] {
  const idx = cards.findIndex((c) => c.toolCall.id === payload.id && c.kind === 'in_flight');
  if (idx === -1) {
    return [
      ...cards,
      {
        kind: 'error',
        toolCall: { id: payload.id, name: payload.name, args: {} },
        toolError: payload,
        calledAt: resolvedAt,
        resolvedAt,
      },
    ];
  }
  const next = cards.slice();
  const previous = next[idx] as Extract<ToolCardState, { kind: 'in_flight' }>;
  next[idx] = {
    kind: 'error',
    toolCall: previous.toolCall,
    toolError: payload,
    calledAt: previous.calledAt,
    resolvedAt,
    // Preserve the server-supplied display name (see applyToolResult).
    displayName: previous.displayName,
  };
  return next;
}
