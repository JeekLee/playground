import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

/**
 * Card primitive — design system §6.4.
 * bg `surface`, border `1px solid border`, radius `md`, padding `16px`,
 * shadow `shadow.card`.
 *
 * `interactive` variant gates the hover-as-link lift specified in §6.4
 * (used by the tile grid; document-empty-state card and login card are
 * static and do not enable it).
 */

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  interactive?: boolean;
  children: ReactNode;
}

export function Card({ interactive = false, className, children, ...rest }: CardProps) {
  return (
    <div
      {...rest}
      className={cn(
        'rounded-md border border-border bg-surface p-md shadow-card transition-all duration-[140ms] ease-out',
        interactive &&
          'cursor-pointer hover:-translate-y-[2px] hover:border-accent hover:shadow-pop',
        className,
      )}
    >
      {children}
    </div>
  );
}
