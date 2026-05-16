import type { ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

/**
 * Tile primitive — composes the "Things you can try" tile grid items
 * pinned in `docs/design/M1-identity.md`. Built on top of Card semantics
 * (border, radius, padding, shadow) plus the icon-box / title / desc /
 * meta layout the design context spells out.
 *
 * The `locked` variant renders at 0.72 opacity per the design context,
 * uses `surface.soft` for the icon box (rather than `accent.soft`), and
 * dampens the hover lift to nothing (locked rows are no-op per PRD
 * "Sidebar locked rows are visual-only").
 */

export interface TileProps {
  icon: ReactNode;
  title: string;
  description: string;
  /** Meta row content — chips, status dots, etc. */
  meta: ReactNode;
  locked?: boolean;
  active?: boolean;
}

export function Tile({ icon, title, description, meta, locked = false, active = false }: TileProps) {
  return (
    <div
      className={cn(
        'flex h-full flex-col gap-md rounded-md border border-border bg-surface p-md shadow-card',
        'transition-all duration-[140ms] ease-out',
        // Hover lift only when the tile is interactive (not locked, not the
        // current-page active tile per the design context open question).
        !locked && !active && 'hover:-translate-y-[2px] hover:border-accent hover:shadow-pop',
        locked && 'opacity-[0.72] cursor-default',
      )}
      aria-disabled={locked || undefined}
    >
      <div
        className={cn(
          'flex h-9 w-9 items-center justify-center rounded-md',
          active ? 'bg-accent-soft text-accent' : 'bg-surface-soft text-text-muted',
        )}
        aria-hidden="true"
      >
        {icon}
      </div>
      <div className="flex flex-1 flex-col gap-xs">
        <h3 className="text-h3 text-text">{title}</h3>
        <p className="text-small text-text-muted">{description}</p>
      </div>
      <div className="flex flex-wrap items-center gap-xs">{meta}</div>
    </div>
  );
}
