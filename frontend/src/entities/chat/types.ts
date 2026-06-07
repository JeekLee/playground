/**
 * Chat entity slice — domain types aliased to the wire DTOs from
 * `shared/api/chat.ts`. The wire shapes are the source of truth (M4
 * spec §5 + §10); this slice re-exports them so widgets / features
 * import domain names without reaching across FSD layers improperly.
 *
 * FSD rule: `entities` may depend on `shared`, never the other way.
 */

import type {
  CitationVisibility,
  DoneEventPayload,
  ErrorEventPayload,
  MassingProgramJson,
  MassingRoom,
  MessageCitationDto,
  MessageDto,
  MessageRole,
  PhaseEventPayload,
  SessionListItemDto,
  SseErrorCode,
  TokenEventPayload,
  ToolCallEventPayload,
  ToolErrorCode,
  ToolErrorEventPayload,
  ToolProgressEventPayload,
  ToolResultEventPayload,
} from '@/shared/api/chat';
import type { ChatStreamEvent } from '@/shared/api/chat.sse';

export type Role = MessageRole;
export type Visibility = CitationVisibility;
export type ChatSession = SessionListItemDto;
export type Citation = MessageCitationDto;
export type Message = MessageDto;
export type SseEvent = ChatStreamEvent;
export type PhasePayload = PhaseEventPayload;
export type TokenPayload = TokenEventPayload;
export type DonePayload = DoneEventPayload;
export type ErrorPayload = ErrorEventPayload;
export type ToolCallPayload = ToolCallEventPayload;
export type ToolProgressPayload = ToolProgressEventPayload;
export type ToolResultPayload = ToolResultEventPayload;
export type ToolErrorPayload = ToolErrorEventPayload;
export type { SseErrorCode, ToolErrorCode, MassingProgramJson, MassingRoom };

/**
 * Per-tool-invocation state captured during a chat turn. The streaming
 * turn accumulates an ordered `ToolCardState[]` (one entry per `id` in
 * the `tool_call` / `tool_result` / `tool_error` triplet) and the
 * `MassingResultCard` / `MassingErrorCard` widgets render one card per
 * entry below the assistant body.
 *
 * Lifecycle:
 *   `tool_call`   → state = `in_flight` (skeleton card with spinner)
 *   `tool_result` → state = `result`     (populated card with primary action)
 *   `tool_error`  → state = `error`      (warning palette + secondary action)
 *
 * `calledAt` lets the error card display the `<elapsed>s elapsed` line
 * per design doc §2.5 — diff against the wall-clock when the matching
 * `tool_error` arrives. Stored as `Date.now()` to keep the math simple
 * and timezone-free.
 */
export type ToolCardState =
  | {
      kind: 'in_flight';
      toolCall: ToolCallPayload;
      calledAt: number;
      /** Server-supplied display name (tool_call.displayName) — rendered by
       *  the generic ToolRunCard. Absent on a pre-streaming backend. */
      displayName?: string;
      /** Latest tool_progress for this call — absent until the first
       *  progress event lands, in which case the card shows "Running…". */
      progress?: ToolProgressPayload;
    }
  | {
      kind: 'result';
      toolCall: ToolCallPayload;
      toolResult: ToolResultPayload;
      calledAt: number;
      resolvedAt: number;
      /** Carried over from the in_flight tool_call.displayName so the
       *  generic result card can title an unregistered tool the same way
       *  ToolRunCard did in-flight. Absent on a pre-streaming backend. */
      displayName?: string;
    }
  | {
      kind: 'error';
      toolCall: ToolCallPayload;
      toolError: ToolErrorPayload;
      calledAt: number;
      resolvedAt: number;
      /** Carried over from the in_flight tool_call.displayName (see the
       *  result variant). Absent on a pre-streaming backend. */
      displayName?: string;
    };

/**
 * Domain view of an in-flight assistant turn — the live "streaming
 * message" the UI renders before any DB row exists. Once the `done`
 * event fires this collapses into a regular {@link Message}; if `error`
 * fires (or the user clicks Stop), the partial is greyed out per
 * ADR-14 §13 and the row is NOT persisted.
 */
export type StreamingTurnStatus = 'thinking' | 'streaming' | 'done' | 'error' | 'aborted';

export interface StreamingTurn {
  /**
   * Optimistic client-generated id; only stable for the lifetime of the
   * stream. Replaced with the server `messageId` from the `done` event
   * once persistence completes.
   */
  clientId: string;
  role: Role;
  content: string;
  citations: Citation[];
  status: StreamingTurnStatus;
  /**
   * Current progress label from the latest `phase` SSE event — rendered
   * inline above the assistant bubble while {@link content} is empty
   * (and replaced by the streamed text once the first token arrives).
   * Null between turns and once the answer body starts filling in.
   */
  phaseLabel?: string;
  /** When status === 'error' — the SSE error payload. */
  error?: ErrorPayload;
  /**
   * Ordered list of tool invocations the LLM dispatched during this
   * turn. Each entry is keyed by the `id` field on the SSE
   * `tool_call` / `tool_result` / `tool_error` triplet so the
   * in-flight skeleton can transition into a populated or error card
   * without reordering. Mirrors the citation pattern — empty array
   * means no tool was called this turn (the common case for
   * pure-RAG questions).
   */
  toolCards: ToolCardState[];
}

/** Stale citation predicate per ADR-14 §11 (title null after server JOIN). */
export function isStaleCitation(c: Citation): boolean {
  return c.title === null;
}

/**
 * The three pinned suggestion strings per ADR-14 §12. Korean primary,
 * English fallback. The frontend picks the locale based on
 * `navigator.language` (or a future i18n provider); for now we expose
 * both lists and let the consuming view choose.
 */
export const EMPTY_STATE_SUGGESTIONS_KO = [
  '최근에 올린 문서를 요약해 줘',
  '이 주제에 대해 내 공개 문서에 뭐가 있어?',
  'ADR-13의 chunking 정책이 어떻게 되지?',
] as const;

export const EMPTY_STATE_SUGGESTIONS_EN = [
  'Summarize my recent uploads',
  'What do my public docs say about this topic?',
  'What is the chunking policy in ADR-13?',
] as const;

/**
 * Pick the locale-appropriate empty-state suggestion list. Defaults to
 * Korean primary per the design doc §2.1 ("Korean primary, English
 * fallback per `Accept-Language`").
 */
export function pickEmptyStateSuggestions(locale: string | undefined): readonly string[] {
  if (locale && locale.toLowerCase().startsWith('en')) {
    return EMPTY_STATE_SUGGESTIONS_EN;
  }
  return EMPTY_STATE_SUGGESTIONS_KO;
}

/** Stable relative time formatter for the overflow dropdown's date column. */
export function formatRelative(iso: string, now: Date = new Date()): string {
  const then = new Date(iso);
  const diffMs = now.getTime() - then.getTime();
  const sec = Math.floor(diffMs / 1000);
  if (sec < 60) return 'just now';
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day}d ago`;
  const wk = Math.floor(day / 7);
  if (wk < 5) return `${wk}w ago`;
  const mo = Math.floor(day / 30);
  if (mo < 12) return `${mo}mo ago`;
  const yr = Math.floor(day / 365);
  return `${yr}y ago`;
}
