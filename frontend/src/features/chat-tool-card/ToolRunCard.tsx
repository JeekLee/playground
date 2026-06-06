'use client';

import { Cog } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import type { ToolCardState } from '@/entities/chat';
import { ToolResultCard } from './ToolResultCard';

/**
 * Generic in-flight tool card — entirely wire-driven (tool-streaming
 * spec D3): the name is `tool_call.displayName ?? name`, the body is the
 * latest `tool_progress` Korean label + `(시도 N)`, and the pip bar is
 * driven by `stageCount` / `stageIndex`. When no progress event has
 * landed yet (a pre-streaming tool, or before the first event arrives)
 * it falls back to the `Running…` spinner — visually identical to the
 * old MassingResultCard in-flight skeleton.
 *
 * New tools get progress rendering for free — only the result card is
 * registered per-tool in `ToolCardList`.
 */
export function ToolRunCard({
  state,
}: {
  state: Extract<ToolCardState, { kind: 'in_flight' }>;
}) {
  const name = state.displayName ?? state.toolCall.name;
  const p = state.progress;
  return (
    <ToolResultCard
      ariaLabel={`Tool call in flight: ${name}`}
      icon={<Cog size={18} aria-hidden="true" strokeWidth={1.75} />}
      name={<span className="text-[14px] font-semibold text-text">{name}</span>}
      summary={
        <span className="inline-flex items-center gap-sm text-text-muted">
          <Spinner />
          <span>{p ? `${p.label}…` : 'Running…'}</span>
          {p?.attempt != null && p.attempt >= 2 && (
            <span className="text-[11px] text-text-subtle">(시도 {p.attempt})</span>
          )}
        </span>
      }
      primaryAction={null}
      footer={
        p ? (
          <div className="flex items-center gap-xs" aria-hidden="true">
            {Array.from({ length: p.stageCount }, (_, i) => (
              <span
                key={i}
                className={cn(
                  'h-[4px] w-[22px] rounded-full',
                  i + 1 < p.stageIndex
                    ? 'bg-accent/60'
                    : i + 1 === p.stageIndex
                      ? 'animate-pulse bg-accent'
                      : 'bg-border',
                )}
              />
            ))}
          </div>
        ) : null
      }
    />
  );
}

function Spinner() {
  // Moved here from MassingResultCard — deleting that file's in-flight
  // branch made ToolRunCard the sole consumer of the spinner.
  return (
    <span
      aria-hidden="true"
      className="inline-block h-[12px] w-[12px] animate-tool-spinner rounded-full border-2 border-border border-t-accent"
    />
  );
}
