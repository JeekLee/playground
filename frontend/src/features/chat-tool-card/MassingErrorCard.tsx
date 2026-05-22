'use client';

import { useMemo } from 'react';
import { ArrowUpRight, RefreshCw, TriangleAlert } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import type { ToolCardState, ToolErrorCode } from '@/entities/chat';
import { ToolResultCard } from './ToolResultCard';
import { parseM8ErrorPrefix } from './parseM8ErrorPrefix';

/**
 * `MassingErrorCard` — M8 `tool_error` card per design doc §2.5 (frame
 * `78:1437`).
 *
 * Visual deltas vs the happy-path:
 *   - `variant="warning"` on the underlying `ToolResultCard` shell
 *     (warning.soft bg + warning border + warning fg on the title row).
 *   - Icon: `⚠` (lucide-react `TriangleAlert`) in warning.
 *   - Primary action slot is REPLACED by a secondary action button
 *     (surface bg + warning border + warning fg). Label is driven by
 *     the M8 domain code via `parseM8ErrorPrefix` per ADR-18 §6.
 *   - Footer slot is REPLACED by a code/timing line (`code: <CODE> ·
 *     <elapsed>s elapsed`) in `font-small` 12/500 `text-muted`.
 *
 * Error code grammar — ADR-18 §6: the M7 wire shape stays unchanged,
 * the M8 domain code rides as a prefix inside `tool_error.message`.
 * The M7-level enum (`UPSTREAM_4XX` / `TIMEOUT` / etc.) determines
 * the **palette** (always warning here), the M8 prefix determines
 * the **secondary action label**. When the prefix is absent (pure M7
 * failures like `TIMEOUT` that pre-date the M8 BC's involvement),
 * the M7 code is surfaced verbatim and the action falls back to
 * `↻ Retry` per ADR-18 §6 mapping table.
 */

export interface MassingErrorCardProps {
  state: Extract<ToolCardState, { kind: 'error' }>;
}

export function MassingErrorCard({ state }: MassingErrorCardProps) {
  const { toolError } = state;
  const parsed = useMemo(() => parseM8ErrorPrefix(toolError.message), [toolError.message]);
  const elapsedSec = useMemo(() => {
    const ms = Math.max(0, state.resolvedAt - state.calledAt);
    return (ms / 1000).toFixed(1);
  }, [state.calledAt, state.resolvedAt]);

  // The displayed code: prefer the M8 domain code when present
  // (`BRIEF_EXTRACTION_FAILED`, `MASSING_ALGORITHM_FAILED`, …);
  // fall back to the M7 wire code (`TIMEOUT`, `UPSTREAM_5XX`, …)
  // for pure-M7 failures.
  const displayedCode = parsed.m8Code ?? toolError.code;
  const action = resolveSecondaryAction(parsed.m8Code, toolError.code);

  return (
    <ToolResultCard
      variant="warning"
      ariaLabel={`Tool error: generate_massing (${displayedCode})`}
      icon={
        <TriangleAlert
          size={20}
          strokeWidth={2.25}
          aria-hidden="true"
          className="text-warning"
        />
      }
      name={
        <span className="font-mono text-[14px] font-semibold text-warning">
          generate_massing
        </span>
      }
      summary={
        // The message body (sans M8 prefix when parsed) is the
        // human-readable failure reason. Renders verbatim — backend
        // owns localization per ADR-18 §6.
        <span className="font-medium text-text">{parsed.message}</span>
      }
      primaryAction={<SecondaryActionButton action={action} />}
      footer={
        <p className="text-[12px] font-medium text-text-muted">
          <span className="font-mono">code: {displayedCode}</span>
          <span aria-hidden="true">{' · '}</span>
          <span>{elapsedSec}s elapsed</span>
        </p>
      }
    />
  );
}

// ---------------------------------------------------------------------------
// Secondary action resolution (ADR-18 §6 mapping table)
// ---------------------------------------------------------------------------

interface SecondaryActionSpec {
  label: string;
  /** `RefreshCw` for retry, `ArrowUpRight` for "Try / Open …" navigations. */
  icon: 'refresh' | 'external';
  /**
   * `href` for `<a>`-style actions; `onClick` semantic for in-page
   * actions. For P0 the design doc only specifies navigation targets;
   * the implementer note (PRD §Story 7) says `↻ Retry` is acceptable
   * as a "return focus to the composer" no-op for now. We surface the
   * choice via the kind discriminator so a future hookup can wire
   * onClick callbacks without changing the shape.
   */
  kind: 'link-new-tab' | 'noop';
  href?: string;
}

function resolveSecondaryAction(
  m8Code: string | null,
  m7Code: ToolErrorCode,
): SecondaryActionSpec {
  // Branch on M8 domain code first (§2.5 + ADR-18 §6 mapping table).
  switch (m8Code) {
    case 'BRIEF_EXTRACTION_FAILED':
      return { label: 'Try a different brief', icon: 'external', kind: 'link-new-tab', href: '/docs/new' };
    case 'BRIEF_NOT_FOUND':
    case 'BRIEF_NOT_ACCESSIBLE':
    case 'BRIEF_NOT_READY':
      // Per ADR-18 §6 mapping table — "↗ Open brief" links to /docs/{briefDocId}.
      // The brief id is not surfaced through the tool_error payload at
      // this layer (it lives in the inflight tool_call.args.briefDocId
      // which we deliberately keep loosely typed here). For P0 we route
      // the architect to the docs list so they can pick the right brief
      // — same end state, one extra click.
      return { label: 'Open documents', icon: 'external', kind: 'link-new-tab', href: '/docs/mine' };
    case 'MASSING_ALGORITHM_FAILED':
      return { label: 'Retry with different inputs', icon: 'refresh', kind: 'noop' };
    case null:
    default:
      break;
  }
  // Fallback: pure-M7 codes that pre-date M8 involvement
  // (TIMEOUT / CIRCUIT_OPEN / UPSTREAM_5XX / SCHEMA_INVALID / INTERNAL)
  // → generic ↻ Retry.
  switch (m7Code) {
    case 'TIMEOUT':
    case 'CIRCUIT_OPEN':
    case 'UPSTREAM_4XX':
    case 'UPSTREAM_5XX':
    case 'MAX_DEPTH':
    case 'SCHEMA_INVALID':
    case 'INTERNAL':
    default:
      return { label: 'Retry', icon: 'refresh', kind: 'noop' };
  }
}

function SecondaryActionButton({ action }: { action: SecondaryActionSpec }) {
  const IconGlyph = action.icon === 'refresh' ? RefreshCw : ArrowUpRight;
  const classNames = cn(
    'inline-flex h-[32px] items-center gap-xs rounded-md border border-warning bg-surface px-[14px]',
    'text-[13px] font-semibold leading-none text-warning',
    'transition-colors duration-[140ms] hover:bg-warning hover:text-surface',
    'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-warning',
  );
  if (action.kind === 'link-new-tab' && action.href) {
    return (
      <a href={action.href} className={classNames}>
        <IconGlyph size={13} aria-hidden="true" strokeWidth={2.25} />
        <span>{action.label}</span>
      </a>
    );
  }
  // P0 `↻ Retry` is a no-op visual today — the architect retries by
  // typing in the composer. PRD §Story 7 explicitly allows this for
  // P0 ("for P0, just return focus to the composer"). Future M8.1
  // wires the click into `submit(lastUserText)` on ChatPage.
  return (
    <button type="button" className={classNames} disabled aria-disabled="true">
      <IconGlyph size={13} aria-hidden="true" strokeWidth={2.25} />
      <span>{action.label}</span>
    </button>
  );
}
