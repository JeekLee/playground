'use client';

import { Sparkles } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { pickEmptyStateSuggestions } from '@/entities/chat';

/**
 * ChatEmptyState — the empty-state hero per design doc §2.1 and ADR-14 §12.
 *
 *  - Title: "What do you want to know about your corpus?" (h2 / 22px / 600).
 *  - Subtitle: "Ask anything about your public + private docs. Citations
 *    link back to the source chunk." (small / 14 / 400 / text-muted).
 *  - Three suggestion chips, vertically stacked, surface bg + border 1 +
 *    radius-md + font.small 13 / 500 / text. Korean primary; English
 *    fallback per ADR-14 §12.
 *
 * Click a chip → pre-fills the composer (does NOT auto-send, per spec
 * §7.5). The parent passes `onSuggest` which calls the composer ref's
 * setValue.
 */

export interface ChatEmptyStateProps {
  onSuggest: (text: string) => void;
  /** Used to pick KO vs EN list per ADR-14 §12. */
  locale?: string;
}

export function ChatEmptyState({ onSuggest, locale }: ChatEmptyStateProps) {
  const suggestions = pickEmptyStateSuggestions(locale);
  return (
    <section
      className="flex w-full max-w-[640px] flex-col items-center gap-lg text-center"
      aria-labelledby="chat-empty-title"
    >
      <div className="flex h-[44px] w-[44px] items-center justify-center rounded-pill bg-accent-soft text-accent">
        <Sparkles size={20} aria-hidden="true" />
      </div>
      <div className="flex flex-col gap-sm">
        <h2 id="chat-empty-title" className="text-h2 text-text">
          What do you want to know about your corpus?
        </h2>
        <p className="text-small text-text-muted">
          Ask anything about your public + private docs. Citations link back to the source chunk.
        </p>
      </div>
      <ul className="flex w-full flex-col items-center gap-sm">
        {suggestions.map((s) => (
          <li key={s}>
            <button
              type="button"
              onClick={() => onSuggest(s)}
              className={cn(
                'inline-flex max-w-full items-center gap-sm rounded-md border border-border bg-surface px-md py-[8px] text-[13px] font-medium text-text transition-colors duration-[140ms]',
                'hover:border-border-strong hover:bg-surface-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
              )}
            >
              <span className="text-text-subtle">→</span>
              <span className="truncate">{s}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
