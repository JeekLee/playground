'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { StopCircle } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { MarkdownReader } from '@/features/docs-reader';
import type { Citation, Role } from '@/entities/chat';

/**
 * ChatMessage — one chat turn (user or assistant).
 *
 * Per design doc §2.2 + §2.3:
 *  - User turn: small `you` eyebrow (text-muted, 11/600) + body (text 15/400),
 *    body wraps at ~820px max-width.
 *  - Assistant turn: small `assistant` eyebrow (accent, 11/600) + body
 *    rendered through {@link MarkdownReader} (GFM, shiki code blocks,
 *    sanitized HTML), with inline `[N]` markers swapped to clickable
 *    citation pills. Click a pill → opens this message's
 *    accordion + highlights the matching card; click scope is
 *    per-message, not page-wide.
 *  - Streaming: tail-of-body pulsing block cursor + Stop button.
 *  - Aborted: body greyed out to text-muted + footer "Generation stopped".
 *
 * Per spec §11 Q7: marker-to-citation matching is scoped per-message;
 * the {@code citations} array passed in is THIS message's citations only.
 */

export type ChatMessageStatus = 'thinking' | 'streaming' | 'done' | 'error' | 'aborted';

export interface ChatMessageProps {
  role: Role;
  content: string;
  citations: Citation[];
  /** Assistant streaming/aborted decorations. Defaults to 'done'. */
  status?: ChatMessageStatus;
  /**
   * Server-supplied progress label from the latest `phase` SSE event
   * (PR B grammar) — rendered next to the thinking spinner while the
   * assistant body is still empty. Falls back to "Thinking…" when not
   * provided. Null once tokens start filling in.
   */
  phaseLabel?: string;
  /** Hooked up only for assistant + streaming — Stop button. */
  onStop?: () => void;
  /**
   * Per-message accordion slot. ChatMessage owns the focused-N state so
   * clicking `[N]` in THIS message's body only affects THIS message's
   * accordion (no page-wide cross-talk). The view layer constructs the
   * accordion element with the supplied {@code focusedN} so the
   * inversion stays one-way (widget owns state, view owns markup).
   * Returns null to suppress the accordion (user turns).
   */
  accordion?: (focusedN: number | null) => ReactNode;
}

export function ChatMessage({
  role,
  content,
  citations,
  status = 'done',
  phaseLabel,
  onStop,
  accordion,
}: ChatMessageProps) {
  const isUser = role === 'user';
  const isAssistant = role === 'assistant';
  const isStreaming = isAssistant && (status === 'streaming' || status === 'thinking');
  const isAborted = status === 'aborted';

  // Per-message focus state. Set when the user clicks an inline `[N]`
  // pill; auto-clears after 2s so the highlight ring fades. The
  // accordion's `open` state survives the clear (intentional — only
  // the highlight is transient).
  const [focusedN, setFocusedN] = useState<number | null>(null);
  const articleRef = useRef<HTMLElement | null>(null);
  const onCitationClick = useCallback(
    (n: number) => {
      setFocusedN(n);
      // Scroll the matching card into view within THIS message's subtree
      // so we don't accidentally jump to another message's card-N.
      queueMicrotask(() => {
        const target = articleRef.current?.querySelector<HTMLElement>(
          `[data-citation-n="${n}"]`,
        );
        target?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      });
    },
    [],
  );
  useEffect(() => {
    if (focusedN === null) return;
    const t = window.setTimeout(() => setFocusedN(null), 2000);
    return () => window.clearTimeout(t);
  }, [focusedN]);

  return (
    <article
      ref={articleRef}
      className={cn(
        'flex w-full max-w-[820px] flex-col gap-sm',
        // Right-align user turns so the conversation reads with a visual hierarchy.
        isUser ? 'self-end items-end' : 'self-start items-start',
      )}
    >
      <span
        className={cn(
          'text-eyebrow',
          isUser ? 'text-text-muted' : 'text-accent',
        )}
      >
        {isUser ? 'you' : 'assistant'}
      </span>
      <div
        className={cn(
          'relative w-full',
          isUser
            ? 'rounded-md border border-border bg-surface-soft px-md py-sm'
            : 'rounded-md',
        )}
      >
        <MessageBody
          content={content}
          citations={citations}
          isAssistant={isAssistant}
          isStreaming={isStreaming}
          isAborted={isAborted}
          onCitationClick={onCitationClick}
          isThinking={status === 'thinking'}
          phaseLabel={phaseLabel}
        />
        {isAssistant && status === 'streaming' && onStop && (
          <div className="mt-md flex justify-end">
            <button
              type="button"
              onClick={onStop}
              className="inline-flex h-[28px] items-center gap-xs rounded-md border border-border-strong bg-surface px-[10px] text-[13px] font-medium text-text transition-colors duration-[140ms] hover:bg-surface-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
              aria-label="Stop generating"
            >
              <StopCircle size={14} aria-hidden="true" />
              <span>Stop</span>
            </button>
          </div>
        )}
      </div>
      {isAssistant && status === 'aborted' && (
        <p className="text-[12px] text-text-muted">Generation stopped</p>
      )}
      {isAssistant && accordion?.(focusedN)}
    </article>
  );
}

interface MessageBodyProps {
  content: string;
  citations: Citation[];
  isAssistant: boolean;
  isStreaming: boolean;
  isAborted: boolean;
  isThinking: boolean;
  phaseLabel?: string;
  onCitationClick: (n: number) => void;
}

function MessageBody({
  content,
  citations,
  isAssistant,
  isStreaming,
  isAborted,
  isThinking,
  phaseLabel,
  onCitationClick,
}: MessageBodyProps) {
  // The "thinking" placeholder before the first token. PR B grammar:
  // every progress update sets `phaseLabel`; we render that verbatim
  // (e.g. "참고 문서 확인 중", "공개 문서 검색 중", "사고 중") so the user
  // sees what the agent is doing. Falls back to "Thinking…".
  if (isThinking && content.length === 0) {
    return (
      <div className="flex items-center gap-sm text-body text-text-muted">
        <ThinkingDots />
        <span>{phaseLabel ?? 'Thinking…'}</span>
      </div>
    );
  }

  if (isAssistant) {
    return (
      <AssistantBody
        content={content}
        citations={citations}
        onCitationClick={onCitationClick}
        isStreaming={isStreaming}
        isAborted={isAborted}
      />
    );
  }

  return (
    <p
      className={cn(
        'whitespace-pre-wrap break-words text-body',
        isAborted ? 'text-text-muted' : 'text-text',
      )}
    >
      {content}
      {isStreaming && <PulsingCursor />}
    </p>
  );
}

function AssistantBody({
  content,
  citations,
  onCitationClick,
  isStreaming,
  isAborted,
}: {
  content: string;
  citations: Citation[];
  onCitationClick: (n: number) => void;
  isStreaming: boolean;
  isAborted: boolean;
}) {
  const validNs = useMemo(() => new Set(citations.map((c) => c.n)), [citations]);
  const pill = useMemo(
    () => ({
      validNs,
      render: (n: number) => <CitationPill n={n} onClick={onCitationClick} />,
    }),
    [validNs, onCitationClick],
  );
  return (
    <div className={cn('text-body', isAborted ? 'text-text-muted' : 'text-text')}>
      <MarkdownReader body={content} citationPill={pill} />
      {isStreaming && <PulsingCursor />}
    </div>
  );
}

function CitationPill({
  n,
  onClick,
}: {
  n: number;
  onClick: (n: number) => void;
}) {
  const handler = useCallback(() => {
    onClick(n);
  }, [n, onClick]);
  return (
    <button
      type="button"
      onClick={handler}
      aria-label={`Show citation ${n}`}
      className="mx-[2px] inline-flex h-[16px] min-w-[18px] items-center justify-center rounded-pill bg-accent-soft px-[5px] align-[2px] font-mono text-[10px] font-semibold leading-none text-accent transition-colors duration-[140ms] hover:bg-accent hover:text-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
    >
      {n}
    </button>
  );
}

function PulsingCursor() {
  return (
    <span
      aria-hidden="true"
      className="ml-[2px] inline-block h-[1em] w-[8px] translate-y-[2px] animate-chat-cursor bg-accent align-baseline"
    />
  );
}

function ThinkingDots() {
  return (
    <span aria-hidden="true" className="inline-flex gap-[3px]">
      <span className="h-[5px] w-[5px] animate-chat-dot rounded-pill bg-accent [animation-delay:0ms]" />
      <span className="h-[5px] w-[5px] animate-chat-dot rounded-pill bg-accent [animation-delay:140ms]" />
      <span className="h-[5px] w-[5px] animate-chat-dot rounded-pill bg-accent [animation-delay:280ms]" />
    </span>
  );
}
