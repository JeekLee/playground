'use client';

import type { ToolCardState } from '@/entities/chat';
import { MassingErrorCard } from './MassingErrorCard';
import { MassingResultCard } from './MassingResultCard';
import { ToolRunCard } from './ToolRunCard';

/**
 * `ToolCardList` — composer that renders one card per `ToolCardState`
 * an assistant turn accumulated. Dispatch:
 *
 *   - `in_flight` (any tool) → `ToolRunCard` — the generic, wire-driven
 *                              progress card (tool-streaming spec D3).
 *                              New tools get progress rendering for free.
 *   - `result` / `error`     → per-tool card, keyed by tool `name`:
 *       - `generate_massing` → `MassingResultCard` / `MassingErrorCard`
 *       - any other name     → currently dropped (M8 is the only
 *                              registered tool BC; future tool BCs add
 *                              their own result/error branches here in
 *                              their own milestone PRs)
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
        // In-flight cards are fully wire-driven — the generic ToolRunCard
        // renders any tool's progress regardless of name.
        if (card.kind === 'in_flight') {
          return <ToolRunCard key={key} state={card} />;
        }
        // Resolved cards (result / error) route by tool name. M8 ships
        // `generate_massing` as the only registered tool; future tool BCs
        // fork here, and anything else is dropped so an unknown tool
        // doesn't crash the chat.
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
