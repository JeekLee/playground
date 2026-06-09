'use client';

import type { ToolCardState } from '@/entities/chat';
import { GenericErrorCard } from './GenericErrorCard';
import { GenericResultCard } from './GenericResultCard';
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
 *       - any other name     → `GenericResultCard` / `GenericErrorCard`
 *                              (agentic-search spec D3) — the generic
 *                              fallback so an unregistered tool
 *                              (search_documents …) shows its completed /
 *                              failed state instead of rendering nothing.
 *                              A registered tool BC still forks its own
 *                              rich card here in its milestone PR.
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
        // `generate_massing` as the only tool with a rich, registered
        // card; every other tool falls through to the generic fallback
        // (agentic-search spec D3) so a completed/failed search renders
        // instead of nothing.
        if (card.toolCall.name !== 'generate_massing' && card.toolCall.name !== 'refine_massing') {
          if (card.kind === 'error') {
            return <GenericErrorCard key={key} state={card} />;
          }
          return <GenericResultCard key={key} state={card} />;
        }
        if (card.kind === 'error') {
          return <MassingErrorCard key={key} state={card} />;
        }
        return <MassingResultCard key={key} state={card} />;
      })}
    </div>
  );
}
