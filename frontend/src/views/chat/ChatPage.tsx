'use client';

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { ArrowDown } from 'lucide-react';
import { ChatSessionTabs } from '@/widgets/chat-session-tabs';
import { ChatComposer, type ChatComposerHandle, type ChatComposerMode } from '@/widgets/chat-composer';
import { ChatMessage, type ChatMessageStatus } from '@/widgets/chat-message';
import { CitationAccordion } from '@/widgets/citation-accordion';
import { ChatErrorBanner } from '@/widgets/chat-error';
import { ChatEmptyState } from '@/widgets/chat-empty-state';
import { ConfirmModal } from '@/widgets/confirm-modal';
import { useChatSessions } from '@/features/chat-sessions';
import { useChatStream } from '@/features/chat-stream';
import { fetchSessionMessages } from '@/shared/api/chat';
import type {
  ChatSession,
  ErrorPayload,
  Message,
  StreamingTurn,
} from '@/entities/chat';
import { cn } from '@/shared/lib/cn';

/**
 * ChatPage — viewport-locked Notion-style chat surface per design doc
 * §1.5 + §2.1–§2.7.
 *
 * Layout:
 *  - Top tab strip (52px, never scrolls).
 *  - Conversation pane: flex-1 + overflow-y-auto. Empty state OR message list.
 *  - Composer pinned to the bottom with 16px gap.
 *  - Error banners anchor 8px above the composer (frames 54:443, 54:509).
 *  - Auto-scroll: anchored to bottom while streaming; if the user scrolls
 *    up past 50px from the bottom, the auto-scroll pauses and a `Jump to
 *    latest` floating pill appears at the bottom-right (spec §9, designed
 *    in-house per design doc open-question #5).
 *
 * Lifecycle:
 *  - On mount with sessions[0] active, fetch its message history.
 *  - On send: append the user turn optimistically + start the stream.
 *  - On done: refetch the active session's messages (gets the persisted
 *    assistant row + cited subset per ADR-14 §10) and bump updatedAt so
 *    the tab strip resorts.
 *  - On abort/error: the partial assistant turn renders inline (greyed
 *    out / error chip) but is NOT persisted per ADR-14 §13.
 *  - On tab switch mid-stream: stop() aborts; the new session loads.
 *
 * The chat-error banner state (`rate-limited`, `gateway-down`) lives in
 * page state so its cooldown / retry behavior is owned in one place.
 */

export interface ChatPageProps {
  initialSessions: ChatSession[];
  /** Picked by the SSR layout — the most-recent session, or null when the user has none. */
  initialActiveSessionId: string | null;
  /** Pre-loaded message history for the initial active session (avoids client waterfall). */
  initialMessages: Message[];
  /** Forwarded to the empty-state widget for KO/EN suggestion picking. */
  locale?: string;
}

type ErrorState =
  | { kind: 'none' }
  | { kind: 'rate-limit'; retryAfter: number }
  | { kind: 'gateway-down' };

export function ChatPage({
  initialSessions,
  initialActiveSessionId,
  initialMessages,
  locale,
}: ChatPageProps) {
  const sessionsApi = useChatSessions(initialSessions);
  const { sessions } = sessionsApi;
  const streamApi = useChatStream();

  const [activeId, setActiveId] = useState<string | null>(initialActiveSessionId);
  const [messages, setMessages] = useState<Message[]>(initialMessages);
  const [pendingUserText, setPendingUserText] = useState<string | null>(null);
  const [lastUserText, setLastUserText] = useState<string | null>(null);
  const [errorState, setErrorState] = useState<ErrorState>({ kind: 'none' });
  const [deleteRequestId, setDeleteRequestId] = useState<string | null>(null);
  const [deletePending, setDeletePending] = useState(false);

  const scrollRef = useRef<HTMLDivElement | null>(null);
  const composerRef = useRef<ChatComposerHandle | null>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  // ----- Active session bootstrap -----

  // Whenever the active session changes, fetch its message history.
  const loadMessages = useCallback(async (sessionId: string | null) => {
    if (!sessionId) {
      setMessages([]);
      return;
    }
    const res = await fetchSessionMessages(sessionId);
    if (res.kind === 'ok') {
      setMessages(res.value.messages);
    } else if (res.kind === 'not-found') {
      // Tab is stale; surface the spec §7.5 "Session 404" toast as an error state.
      setErrorState({ kind: 'gateway-down' }); // closest available variant for now
      setMessages([]);
    }
  }, []);

  // Switching tabs: abort any in-flight stream, then load.
  useEffect(() => {
    if (!activeId) return;
    // Only refetch when activeId changes after mount; the initial set was
    // SSR-hydrated.
    if (activeId === initialActiveSessionId && messages.length === initialMessages.length) {
      return;
    }
    streamApi.stop();
    streamApi.reset();
    setPendingUserText(null);
    setErrorState({ kind: 'none' });
    void loadMessages(activeId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  // Mid-stream re-join (spec §6.4): every time the active session becomes
  // available — initial mount OR tab switch — attempt to attach to a live
  // chat turn on the server. The endpoint answers 404 when nothing's in
  // flight, in which case `resume` reports `'no-active-turn'` and the
  // page stays on the loaded history. When a turn IS in flight, the
  // streaming bubble fills with the buffered retrieval + every token so
  // far + the live tail — identical UX to having stayed on the page.
  useEffect(() => {
    if (!activeId) return;
    let cancelled = false;
    const target = activeId;
    void (async () => {
      const status = await streamApi.resume(target);
      if (cancelled) return;
      if (status === 'no-active-turn') return;
      if (status === 'done') {
        await loadMessages(target);
        streamApi.reset();
        sessionsApi.bumpUpdatedAt(target);
        // Auto-title may have just settled — refresh the session list so
        // the tab strip picks up the new title (ADR-14 §6).
        void sessionsApi.refresh();
      } else if (status === 'error') {
        const err = streamApi.turn?.error;
        if (err) handleStreamError(err);
      }
      // 'aborted' = the user submitted a new turn / switched tabs / unmounted
      // while we were attached. No UI cleanup needed — the next effect or
      // submit() owns the state from here.
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId]);

  // ----- Send a turn -----

  const handleStreamError = useCallback((err: ErrorPayload) => {
    if (err.code === 'RATE_LIMIT') {
      const retryAfter = err.retryAfter ?? 60 * 13;
      setErrorState({ kind: 'rate-limit', retryAfter });
    } else if (err.code === 'GATEWAY_5XX' || err.code === 'GATEWAY_DOWN') {
      setErrorState({ kind: 'gateway-down' });
    }
    // Other codes render only inline in the streaming-turn chip.
  }, []);

  const submit = useCallback(
    async (text: string, opts?: { ensureSessionId?: string }) => {
      const sid = opts?.ensureSessionId ?? activeId;
      let sessionId = sid;

      // If the user has no sessions yet, create one in-line.
      if (!sessionId) {
        sessionId = await sessionsApi.create();
        if (!sessionId) return;
        setActiveId(sessionId);
      }

      setPendingUserText(text);
      setLastUserText(text);
      setErrorState({ kind: 'none' });
      setAutoScroll(true);

      const status = await streamApi.start(sessionId, text);

      if (status === 'done') {
        // Refetch the session's messages to pick up the persisted user +
        // assistant rows (with the cited subset per ADR-14 §10).
        await loadMessages(sessionId);
        streamApi.reset();
        setPendingUserText(null);
        sessionsApi.bumpUpdatedAt(sessionId);
        // Refetch the session list once to pick up the auto-title (ADR-14
        // §6); fire-and-forget so the UI doesn't wait.
        void sessionsApi.refresh();
      } else if (status === 'error') {
        const err = streamApi.turn?.error;
        if (err) {
          handleStreamError(err);
        }
        // Keep the pending user turn visible so the user sees what failed.
      } else {
        // Aborted: the partial assistant turn is greyed out in the
        // streamApi.turn; we leave the pending user turn visible until
        // the user sends another message.
      }
    },
    [activeId, sessionsApi, streamApi, loadMessages, handleStreamError],
  );

  const onRetryLastMessage = useCallback(() => {
    if (!lastUserText) return;
    setErrorState({ kind: 'none' });
    streamApi.reset();
    void submit(lastUserText);
  }, [lastUserText, streamApi, submit]);

  // ----- Tab actions -----

  const onCreateTab = useCallback(async () => {
    const newId = await sessionsApi.create();
    if (newId) setActiveId(newId);
  }, [sessionsApi]);

  const onSelectTab = useCallback((id: string) => {
    setActiveId(id);
  }, []);

  const onRenameTab = useCallback(
    (id: string, title: string) => {
      void sessionsApi.rename(id, title);
    },
    [sessionsApi],
  );

  const requestDelete = useCallback((id: string) => {
    setDeleteRequestId(id);
  }, []);

  const confirmDelete = useCallback(async () => {
    if (!deleteRequestId) return;
    setDeletePending(true);
    await sessionsApi.remove(deleteRequestId);
    setDeletePending(false);
    // If we deleted the active session, jump to the next-most-recent or to empty.
    if (deleteRequestId === activeId) {
      const remaining = sessions.filter((s) => s.id !== deleteRequestId);
      setActiveId(remaining[0]?.id ?? null);
      if (!remaining[0]) setMessages([]);
    }
    setDeleteRequestId(null);
  }, [deleteRequestId, sessionsApi, activeId, sessions]);

  // ----- Auto-scroll handling -----

  useEffect(() => {
    if (!autoScroll) return;
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [messages, streamApi.turn, autoScroll, pendingUserText]);

  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const distanceFromBottom = el.scrollHeight - el.clientHeight - el.scrollTop;
    setAutoScroll(distanceFromBottom < 50);
  }, []);

  // ----- Composer + streaming derived state -----

  const composerMode: ChatComposerMode = useMemo(() => {
    if (errorState.kind === 'rate-limit') return 'rate-limited';
    if (streamApi.isStreaming) return 'streaming';
    return 'ready';
  }, [errorState, streamApi.isStreaming]);

  const streamingAssistant: StreamingTurn | null = streamApi.turn;

  // Click-`[N]` scoping moved into ChatMessage (which now owns its own
  // per-message focusedN state + scroll-into-view of its own card). The
  // page no longer needs a shared focusedCitationN.

  const isStreaming = streamApi.isStreaming;
  const hasContent = messages.length > 0 || pendingUserText !== null || isStreaming;

  return (
    <div className="flex h-[calc(100vh-57px)] flex-col bg-bg">
      <ChatSessionTabs
        sessions={sessions}
        activeSessionId={activeId}
        onSelect={onSelectTab}
        onCreate={onCreateTab}
        onRename={onRenameTab}
        onDeleteRequest={requestDelete}
      />

      <div className="relative flex flex-1 flex-col overflow-hidden">
        <div
          ref={scrollRef}
          onScroll={onScroll}
          className="flex flex-1 flex-col overflow-y-auto px-[28px] py-lg"
        >
          {!hasContent ? (
            <div className="flex flex-1 items-center justify-center">
              <ChatEmptyState
                locale={locale}
                onSuggest={(text) => composerRef.current?.setValue(text)}
              />
            </div>
          ) : (
            <div className="mx-auto flex w-full max-w-[820px] flex-col gap-lg pb-md">
              {messages.map((m) => (
                <ChatMessage
                  key={m.id}
                  role={m.role}
                  content={m.content}
                  citations={m.citations}
                  status="done"
                  accordion={
                    m.role === 'assistant' && m.citations.length > 0
                      ? (focusedN) => (
                          <CitationAccordion
                            citations={m.citations}
                            focusN={focusedN}
                          />
                        )
                      : undefined
                  }
                />
              ))}

              {pendingUserText !== null && (
                <ChatMessage
                  role="user"
                  content={pendingUserText}
                  citations={[]}
                  status="done"
                />
              )}

              {streamingAssistant && (
                <ChatMessage
                  role="assistant"
                  content={streamingAssistant.content}
                  citations={streamingAssistant.citations}
                  status={mapStreamStatus(streamingAssistant.status)}
                  phaseLabel={streamingAssistant.phaseLabel}
                  onStop={streamApi.stop}
                  toolCards={streamingAssistant.toolCards}
                  accordion={
                    streamingAssistant.citations.length > 0 || streamingAssistant.status === 'done'
                      ? (focusedN) => (
                          <CitationAccordion
                            citations={streamingAssistant.citations}
                            focusN={focusedN}
                          />
                        )
                      : undefined
                  }
                />
              )}
            </div>
          )}
        </div>

        {!autoScroll && hasContent && (
          <button
            type="button"
            onClick={() => {
              setAutoScroll(true);
              const el = scrollRef.current;
              if (el) el.scrollTop = el.scrollHeight;
            }}
            className="absolute bottom-[112px] right-[36px] inline-flex h-[32px] items-center gap-xs rounded-pill border border-accent bg-surface px-[12px] text-[12px] font-semibold text-accent shadow-pop transition-colors duration-[140ms] hover:bg-accent-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
            aria-label="Jump to latest message"
          >
            <ArrowDown size={12} aria-hidden="true" />
            <span>Jump to latest</span>
          </button>
        )}

        <div className={cn('flex flex-col items-center gap-sm border-t border-border bg-bg px-[28px] pb-md pt-md')}>
          {errorState.kind === 'gateway-down' && (
            <ChatErrorBanner variant="gateway-down" onRetry={onRetryLastMessage} />
          )}
          {errorState.kind === 'rate-limit' && (
            <ChatErrorBanner
              variant="rate-limited"
              retryAfterSeconds={errorState.retryAfter}
              onCooldownComplete={() => setErrorState({ kind: 'none' })}
            />
          )}
          <ChatComposer
            ref={composerRef}
            mode={composerMode}
            onSubmit={(text) => void submit(text)}
            onStop={streamApi.stop}
          />
        </div>
      </div>

      <ConfirmModal
        open={deleteRequestId !== null}
        title="Delete this conversation?"
        body="This cannot be undone. All messages and citations in this chat will be removed."
        confirmLabel="Delete"
        cancelLabel="Cancel"
        variant="danger"
        pending={deletePending}
        onConfirm={() => void confirmDelete()}
        onClose={() => setDeleteRequestId(null)}
      />
    </div>
  );
}

function mapStreamStatus(status: StreamingTurn['status']): ChatMessageStatus {
  if (status === 'thinking') return 'thinking';
  if (status === 'streaming') return 'streaming';
  if (status === 'done') return 'done';
  if (status === 'aborted') return 'aborted';
  return 'error';
}
