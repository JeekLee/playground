'use client';

import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, ExternalLink } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { MarkdownReader } from '@/features/docs-reader';
import { isStaleCitation, type Citation } from '@/entities/chat';

/**
 * CitationAccordion — inline, per-assistant-message expandable card list.
 *
 * Per design doc §2.3 + §4.2 + ADR-14 §11:
 *  - Collapsed: `▸ Citations · N` in `text-muted` 12px / 500.
 *  - Expanded:  `▾ Citations · N` in `accent` 12px / 600, with a card list
 *               (`surface` bg + `border` + `radius-md`, 1px dividers).
 *  - Each card: `[n] <title>` (text 13 / 600) left, `↗ open` accent link right,
 *               excerpt (`text-muted` 12 / 400, max 2 lines, ellipsis).
 *  - Stale citation (title null): `[n] (deleted) — 이 문서는 더 이상…`,
 *               NO ↗ open link, NO chunk_index shown.
 *  - RETRIEVAL_EMPTY (N=0): header reads `▾ Citations · none`; expanded
 *               body is single line "(no citations — answer was unsupported)".
 *
 * `focusN` lets the parent message body (which renders the `[N]` pills as
 * click targets) scroll the matching card into view + apply a brief
 * highlight ring.
 */

export interface CitationAccordionProps {
  citations: Citation[];
  /** Forces the open state — used by the click-`[N]` handler in the parent. */
  defaultOpen?: boolean;
  /**
   * When set, the matching card is scrolled into view + highlighted on mount
   * (and whenever this value changes). The parent passes the `[N]` the user
   * clicked.
   */
  focusN?: number | null;
}

const STALE_KOREAN = '이 문서는 더 이상 사용할 수 없습니다';

export function CitationAccordion({ citations, defaultOpen = false, focusN = null }: CitationAccordionProps) {
  const [open, setOpen] = useState(defaultOpen || citations.length === 0);
  // Reactively open the accordion when the parent message reports a
  // newly-clicked `[N]`. `defaultOpen` only seeds the initial state;
  // subsequent prop changes wouldn't otherwise flip `open` back to true.
  useEffect(() => {
    if (focusN !== null && focusN !== undefined) {
      setOpen(true);
    }
  }, [focusN]);
  const count = citations.length;
  const isEmpty = count === 0;

  return (
    <div className="mt-md flex flex-col gap-sm">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        className={cn(
          'inline-flex items-center gap-xs self-start rounded-md px-xs py-[2px] text-[12px] transition-colors duration-[140ms]',
          'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
          open
            ? 'font-semibold text-accent hover:bg-accent-soft'
            : 'font-medium text-text-muted hover:bg-surface-soft hover:text-text',
        )}
      >
        {open ? (
          <ChevronDown size={12} aria-hidden="true" />
        ) : (
          <ChevronRight size={12} aria-hidden="true" />
        )}
        <span>Citations · {isEmpty ? 'none' : count}</span>
      </button>

      {open && (
        <div
          role="region"
          aria-label={`Citations (${isEmpty ? 'none' : count})`}
          className="w-full max-w-[820px] overflow-hidden rounded-md border border-border bg-surface shadow-card"
        >
          {isEmpty ? (
            <p className="px-md py-md text-[12px] text-text-muted">
              (no citations — answer was unsupported)
            </p>
          ) : (
            <ul className="divide-y divide-border">
              {citations.map((c) => (
                <CitationCard key={`${c.n}-${c.documentId}-${c.chunkIndex}`} citation={c} focused={focusN === c.n} />
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

interface CitationCardProps {
  citation: Citation;
  focused: boolean;
}

function CitationCard({ citation, focused }: CitationCardProps) {
  const stale = isStaleCitation(citation);
  // Brief highlight ring when this card was just scrolled-to via [N] click.
  const focusRing = focused ? 'bg-accent-soft' : '';
  return (
    <li
      className={cn(
        'flex flex-col gap-xs px-md pb-[12px] pt-[14px] transition-colors duration-[140ms]',
        focusRing,
      )}
      data-citation-n={citation.n}
    >
      <div className="flex items-baseline gap-md">
        <p className="flex-1 truncate text-[13px] font-semibold text-text">
          {stale ? (
            <>
              <CitationIndex n={citation.n} stale />
              <span className="ml-xs text-text-muted">(deleted) — {STALE_KOREAN}</span>
            </>
          ) : (
            <>
              <CitationIndex n={citation.n} />
              <span className="ml-xs">{citation.title}</span>
            </>
          )}
        </p>
        {!stale && (
          <a
            href={`/docs/${encodeURIComponent(citation.documentId)}`}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-[3px] text-[12px] font-medium text-accent transition-opacity hover:opacity-80 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
          >
            <ExternalLink size={11} aria-hidden="true" />
            <span>open</span>
          </a>
        )}
      </div>
      {!stale && citation.excerpt && (
        // Excerpts are slices of the source Markdown chunk, so render
        // them through the same pipeline as the doc body so backticks,
        // **bold**, etc. don't bleed through as raw text. The
        // `line-clamp` here is approximate — block elements (code
        // fences) inside an excerpt look odd, but excerpts are ≤ ~160
        // chars so they rarely contain a full code fence. Citation
        // pills are disabled here ({@code citationPill} omitted) — the
        // excerpt is source text, not a place to nest other citations.
        <div className="line-clamp-3 text-[12px] leading-snug text-text-muted [&>*:first-child]:mt-0">
          <MarkdownReader body={citation.excerpt} />
        </div>
      )}
    </li>
  );
}

function CitationIndex({ n, stale = false }: { n: number; stale?: boolean }) {
  return (
    <span
      className={cn(
        'mr-[2px] inline-flex h-[18px] min-w-[22px] items-center justify-center rounded-pill px-[6px] font-mono text-[10px] font-semibold leading-none',
        stale ? 'bg-surface-soft text-text-muted' : 'bg-accent-soft text-accent',
      )}
      aria-label={`Citation ${n}`}
    >
      {n}
    </span>
  );
}
