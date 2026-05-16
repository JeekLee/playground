import { Search } from 'lucide-react';

/**
 * Search pill — sidebar `⌘K` palette trigger. Visual-only at M1; the
 * palette itself lands with M2. Renders as a static surface-on-cream
 * pill with placeholder text and the kbd glyph in JetBrains Mono.
 *
 * `aria-disabled` is set to true so screen readers don't promise an
 * interaction we don't yet honor.
 */
export function SearchPill() {
  return (
    <div
      role="button"
      aria-disabled="true"
      tabIndex={-1}
      className="flex w-full cursor-default items-center justify-between gap-sm rounded-pill border border-border bg-surface px-md py-[6px] text-small text-text-subtle"
    >
      <span className="flex items-center gap-sm">
        <Search size={14} aria-hidden="true" />
        <span>Search</span>
      </span>
      <kbd className="rounded-sm bg-surface-soft px-[6px] py-[1px] font-mono text-[11px] text-text-muted">
        ⌘K
      </kbd>
    </div>
  );
}
