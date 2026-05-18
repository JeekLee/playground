import { Search } from 'lucide-react';

/**
 * Search pill — `⌘K` palette trigger. Lives in the topbar's center slot
 * (Obsidian / VSCode pattern: keyboard-fastest is the global shortcut,
 * but a visible click target keeps the affordance discoverable).
 *
 * The pill itself stays presentational — the caller wires `onClick` to
 * open the global {@link CommandPalette}. When `onClick` is omitted the
 * pill renders as a non-interactive label so the component is still
 * usable in pre-S2 surfaces (e.g. login page).
 */
export interface SearchPillProps {
  onClick?: () => void;
}

export function SearchPill({ onClick }: SearchPillProps) {
  const interactive = Boolean(onClick);
  const content = (
    <>
      <span className="flex items-center gap-sm">
        <Search size={14} aria-hidden="true" />
        <span>Search</span>
      </span>
      <kbd className="rounded-sm bg-surface-soft px-[6px] py-[1px] font-mono text-[11px] text-text-muted">
        ⌘K
      </kbd>
    </>
  );

  if (!interactive) {
    return (
      <div
        role="button"
        aria-disabled="true"
        tabIndex={-1}
        className="flex w-full cursor-default items-center justify-between gap-sm rounded-pill border border-border bg-surface px-md py-[6px] text-small text-text-subtle"
      >
        {content}
      </div>
    );
  }

  return (
    <button
      type="button"
      onClick={onClick}
      aria-label="Open command palette (⌘K)"
      className="flex w-full items-center justify-between gap-sm rounded-pill border border-border bg-surface px-md py-[6px] text-small text-text-subtle transition-colors duration-[140ms] hover:border-border-strong hover:bg-surface-soft hover:text-text focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
    >
      {content}
    </button>
  );
}
