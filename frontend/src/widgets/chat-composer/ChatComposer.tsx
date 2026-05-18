'use client';

import { useEffect, useImperativeHandle, useRef, useState, forwardRef } from 'react';
import { CornerDownLeft, StopCircle } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { MAX_MESSAGE_BYTES, messageByteSize } from '@/shared/api/chat';

/**
 * ChatComposer — viewport-bottom-pinned input + Send/Stop toggle.
 *
 * Per design doc §1.5 + §2.1 + §2.2 + spec §7.4:
 *  - 1112×56 area (auto width on responsive); `surface` bg + `border-strong`
 *    1px + `radius-md` (≈12px wireframe).
 *  - Placeholder: "Ask anything about your corpus…" (text-subtle 14px).
 *  - `Enter` submits, `Shift+Enter` inserts newline. Auto-grow up to 8 lines.
 *  - During a stream: bg flips to `surface-soft`, placeholder becomes the
 *    pinned Korean string "응답이 생성 중입니다…", Send button recolors to
 *    `danger` + label flips to "Stop". Both Stop affordances wire to the
 *    same abort handler (in-message Stop + composer Stop).
 *  - Rate-limited: disabled + placeholder "Rate limit reached — disabled
 *    until cooldown ends".
 *  - Focus: outline `2px accent` per §6.2.
 *  - 4 KB cap (spec §5.1): client-side validation; the Send button greys
 *    out + a small "max 4 KB" hint slips in below the composer when over.
 */

export type ChatComposerMode = 'ready' | 'streaming' | 'rate-limited' | 'unavailable';

export interface ChatComposerProps {
  mode: ChatComposerMode;
  /** Called with the trimmed message text when Send fires. */
  onSubmit: (text: string) => void;
  /** Called when the composer's own Stop affordance fires (mirror of in-message). */
  onStop: () => void;
  /** Used by the empty-state suggestion chips to pre-fill the input. */
  initialText?: string;
}

export interface ChatComposerHandle {
  focus: () => void;
  setValue: (text: string) => void;
}

const STREAMING_PLACEHOLDER_KO = '응답이 생성 중입니다…';
const READY_PLACEHOLDER = 'Ask anything about your corpus…';
const RATE_LIMIT_PLACEHOLDER = 'Rate limit reached — disabled until cooldown ends';
const UNAVAILABLE_PLACEHOLDER = 'Chat is unavailable right now.';

export const ChatComposer = forwardRef<ChatComposerHandle, ChatComposerProps>(
  function ChatComposer({ mode, onSubmit, onStop, initialText = '' }, ref) {
    const [value, setValue] = useState(initialText);
    const textareaRef = useRef<HTMLTextAreaElement | null>(null);

    useImperativeHandle(ref, () => ({
      focus: () => textareaRef.current?.focus(),
      setValue: (text: string) => {
        setValue(text);
        // Defer focus to after the controlled value commits.
        queueMicrotask(() => textareaRef.current?.focus());
      },
    }));

    // Auto-grow to max 8 lines (≈ 192px at 24 px line-height).
    useEffect(() => {
      const el = textareaRef.current;
      if (!el) return;
      el.style.height = 'auto';
      const next = Math.min(el.scrollHeight, 192);
      el.style.height = `${next}px`;
    }, [value]);

    const isStreaming = mode === 'streaming';
    const isRateLimited = mode === 'rate-limited';
    const isUnavailable = mode === 'unavailable';
    const disabled = isStreaming || isRateLimited || isUnavailable;
    const overSizeLimit = messageByteSize(value) > MAX_MESSAGE_BYTES;
    const canSend = value.trim().length > 0 && !overSizeLimit && !disabled;

    const handleSend = () => {
      const text = value.trim();
      if (!text || overSizeLimit) return;
      onSubmit(text);
      setValue('');
    };

    const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      // Enter submits, Shift+Enter newline (per spec §7.4).
      if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
        e.preventDefault();
        if (isStreaming) {
          onStop();
        } else if (canSend) {
          handleSend();
        }
      }
    };

    const placeholder = isStreaming
      ? STREAMING_PLACEHOLDER_KO
      : isRateLimited
        ? RATE_LIMIT_PLACEHOLDER
        : isUnavailable
          ? UNAVAILABLE_PLACEHOLDER
          : READY_PLACEHOLDER;

    return (
      <div className="flex w-full max-w-[1112px] flex-col gap-xs">
        <div
          className={cn(
            'flex items-end gap-sm rounded-[12px] border px-md py-[10px] shadow-card transition-colors duration-[140ms]',
            isStreaming
              ? 'border-border bg-surface-soft'
              : isRateLimited || isUnavailable
                ? 'border-border bg-surface-soft'
                : 'border-border-strong bg-surface focus-within:border-accent focus-within:ring-2 focus-within:ring-accent-soft',
          )}
        >
          <textarea
            ref={textareaRef}
            rows={1}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={placeholder}
            disabled={disabled && !isStreaming}
            // While streaming the textarea visually disables but stays
            // focusable so the user can keep Enter as a Stop affordance.
            aria-label="Chat message"
            className={cn(
              'flex-1 resize-none bg-transparent text-body leading-snug text-text outline-none placeholder:text-text-subtle',
              'min-h-[24px] max-h-[192px]',
              (isRateLimited || isUnavailable) && 'cursor-not-allowed',
            )}
          />
          {isStreaming ? (
            <button
              type="button"
              onClick={onStop}
              aria-label="Stop generating"
              className="inline-flex h-[32px] shrink-0 items-center gap-xs rounded-md border border-danger bg-danger px-[12px] text-[13px] font-medium text-surface transition-colors duration-[140ms] hover:bg-danger-hover hover:border-danger-hover focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
            >
              <StopCircle size={14} aria-hidden="true" />
              <span>Stop</span>
            </button>
          ) : (
            <button
              type="button"
              onClick={handleSend}
              disabled={!canSend}
              aria-label="Send message"
              className={cn(
                'inline-flex h-[32px] shrink-0 items-center gap-xs rounded-md px-[12px] text-[13px] font-medium transition-colors duration-[140ms] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
                canSend
                  ? 'border border-accent bg-accent text-surface hover:bg-accent-hover hover:border-accent-hover'
                  : 'border border-border bg-surface-soft text-text-subtle cursor-not-allowed',
              )}
            >
              <CornerDownLeft size={13} aria-hidden="true" />
              <span>Send</span>
            </button>
          )}
        </div>
        {overSizeLimit && (
          <p className="px-md text-[11px] text-danger">
            Message exceeds the 4 KB limit. Trim before sending.
          </p>
        )}
      </div>
    );
  },
);
