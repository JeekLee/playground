'use client';

import type { ToolCardState } from '@/entities/chat';
import { MassingErrorCard } from './MassingErrorCard';
import { MassingResultCard } from './MassingResultCard';

/**
 * `ToolCardList` — composer that renders one card per `ToolCardState`
 * an assistant turn accumulated. Dispatches by tool `name`:
 *
 *   - `generate_massing` → `MassingResultCard` / `MassingErrorCard`
 *   - any other name     → currently dropped (M8 is the only registered
 *                          tool BC; future tool BCs add their own
 *                          render branches here in their own milestone PRs)
 *
 * Layout: a column with `gap-md` between cards, matching the chat-message
 * `gap-lg` rhythm one level out. The list lives BELOW the assistant
 * body inside the same `<article>` so it scrolls with the messages
 * (per design doc §1.2 — "the tool_result card scrolls with the
 * messages — it is NOT pinned").
 */

export interface ToolCardListProps {
  cards: ToolCardState[];
}

export function ToolCardList({ cards }: ToolCardListProps) {
  if (cards.length === 0) return null;
  return (
    <div className="mt-md flex w-full max-w-[820px] flex-col gap-md">
      {cards.map((card) => {
        const key = card.toolCall.id;
        // The dispatch contract pins `generate_massing` as the only
        // tool name M8 ships. Future tool BCs will fork here; today
        // we route all variants of `generate_massing` to the M8
        // renderers and pass through anything else as a no-op so
        // an unknown tool doesn't crash the chat.
        if (card.toolCall.name !== 'generate_massing') {
          return null;
        }
        if (card.kind === 'error') {
          return <MassingErrorCard key={key} state={card} />;
        }
        return <MassingResultCard key={key} state={card} />;
      })}
    </div>
  );
}
