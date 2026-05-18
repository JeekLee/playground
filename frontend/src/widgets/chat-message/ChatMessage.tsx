'use client';

import { Fragment, useCallback, type ReactNode } from 'react';
import { StopCircle } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import type { Citation, Role } from '@/entities/chat';

/**
 * ChatMessage — one chat turn (user or assistant).
 *
 * Per design doc §2.2 + §2.3:
 *  - User turn: small `you` eyebrow (text-muted, 11/600) + body (text 15/400),
 *    body wraps at ~820px max-width.
 *  - Assistant turn: small `assistant` eyebrow (accent, 11/600) + body with
 *    inline `[N]` markers turned into superscript pills (accent.soft bg +
 *    accent fg + font.eyebrow 11px + radius.pill). Click a pill → scroll +
 *    focus the matching CitationAccordion card.
 *  - Streaming: tail-of-body pulsing block cursor (`▍`, accent, opacity
 *    0.3 ↔ 1.0 at ~1s) + an in-message Stop button anchored bottom-right.
 *  - Aborted: body greyed out to text-muted + footer "Generation stopped".
 *
 * The `[N]` parser handles the spec §11 rule: orphan markers (no matching
 * citation) render as plain text, not pills.
 *
 * Per spec §11 Q7: marker-to-citation matching is scoped per-message; the
 * `citations` array passed in is THIS message's citations only.
 */

export type ChatMessageStatus = 'thinking' | 'streaming' | 'done' | 'error' | 'aborted';

export interface ChatMessageProps {
  role: Role;
  content: string;
  citations: Citation[];
  /** Assistant streaming/aborted decorations. Defaults to 'done'. */
  status?: ChatMessageStatus;
  /** Called when user clicks an inline `[N]` pill. */
  onCitationClick?: (n: number) => void;
  /** Hooked up only for assistant + streaming — Stop button. */
  onStop?: () => void;
  /**
   * Per-message accordion slot rendered below the assistant body. The
   * view layer composes it (FSD: widgets cannot depend on widgets, so
   * the parent passes the {@link CitationAccordion} element through).
   * Pass `null` to suppress the accordion (e.g., user turns).
   */
  accordion?: ReactNode;
}

export function ChatMessage({
  role,
  content,
  citations,
  status = 'done',
  onCitationClick,
  onStop,
  accordion = null,
}: ChatMessageProps) {
  const isUser = role === 'user';
  const isAssistant = role === 'assistant';
  const isStreaming = isAssistant && (status === 'streaming' || status === 'thinking');
  const isAborted = status === 'aborted';

  return (
    <article
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
      {isAssistant && accordion}
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
  onCitationClick?: (n: number) => void;
}

function MessageBody({
  content,
  citations,
  isAssistant,
  isStreaming,
  isAborted,
  isThinking,
  onCitationClick,
}: MessageBodyProps) {
  // The "thinking" placeholder before the first token / retrieval event.
  if (isThinking && content.length === 0) {
    return (
      <div className="flex items-center gap-sm text-body text-text-muted">
        <ThinkingDots />
        <span>Thinking…</span>
      </div>
    );
  }
  return (
    <p
      className={cn(
        'whitespace-pre-wrap break-words text-body',
        isAborted ? 'text-text-muted' : 'text-text',
      )}
    >
      {isAssistant
        ? renderAssistantBody(content, citations, onCitationClick)
        : content}
      {isStreaming && <PulsingCursor />}
    </p>
  );
}

/**
 * Replace inline `[N]` markers with citation pills. Orphan markers
 * (N has no matching citation) render as plain text per spec §11
 * "orphan marker" rule.
 *
 * Pure split-on-regex (no DOM walking, no remark-rehype pipeline) —
 * the assistant body for M4 P0 is plain text + `[N]` markers; the M4.1
 * markdown-render pass (spec §9) layers on top of this.
 */
function renderAssistantBody(
  text: string,
  citations: Citation[],
  onCitationClick?: (n: number) => void,
): React.ReactNode {
  const citationByN = new Map<number, Citation>();
  for (const c of citations) citationByN.set(c.n, c);

  const parts: React.ReactNode[] = [];
  const regex = /\[(\d+)\]/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let keyCounter = 0;
  while ((match = regex.exec(text)) !== null) {
    const n = Number(match[1]);
    const before = text.slice(lastIndex, match.index);
    if (before) {
      parts.push(<Fragment key={`t-${keyCounter++}`}>{before}</Fragment>);
    }
    if (citationByN.has(n)) {
      parts.push(
        <CitationPill key={`p-${keyCounter++}-${n}`} n={n} onClick={onCitationClick} />,
      );
    } else {
      // Orphan marker — render as plain text.
      parts.push(<Fragment key={`o-${keyCounter++}`}>{match[0]}</Fragment>);
    }
    lastIndex = match.index + match[0].length;
  }
  if (lastIndex < text.length) {
    parts.push(<Fragment key={`t-${keyCounter++}`}>{text.slice(lastIndex)}</Fragment>);
  }
  return parts;
}

function CitationPill({
  n,
  onClick,
}: {
  n: number;
  onClick?: (n: number) => void;
}) {
  const handler = useCallback(() => {
    onClick?.(n);
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
