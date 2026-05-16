import type { ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

/**
 * Chip primitive — design system §6.3.
 * 11px / 500 / pill radius / padding `3px 9px`. Six variants.
 *
 * Optional `dot` renders a 6px filled circle ahead of the label, used for
 * "● shipped" on tiles and "● Signed in" on the topbar.
 */

export type ChipVariant = 'accent' | 'neutral' | 'success' | 'warning' | 'danger' | 'info';

const BASE =
  'inline-flex items-center gap-xs rounded-pill px-[9px] py-[3px] text-[11px] font-medium leading-none';

const VARIANTS: Record<ChipVariant, string> = {
  accent: 'bg-accent-soft text-accent',
  neutral: 'bg-surface-soft text-text-muted',
  success: 'bg-success-soft text-success',
  warning: 'bg-warning-soft text-warning',
  danger: 'bg-danger-soft text-danger',
  info: 'bg-info-soft text-info',
};

const DOT_COLOR: Record<ChipVariant, string> = {
  accent: 'bg-accent',
  neutral: 'bg-text-muted',
  success: 'bg-success',
  warning: 'bg-warning',
  danger: 'bg-danger',
  info: 'bg-info',
};

export interface ChipProps {
  variant?: ChipVariant;
  dot?: boolean;
  className?: string;
  children: ReactNode;
}

export function Chip({ variant = 'neutral', dot = false, className, children }: ChipProps) {
  return (
    <span className={cn(BASE, VARIANTS[variant], className)}>
      {dot && (
        <span
          aria-hidden="true"
          className={cn('inline-block h-[6px] w-[6px] rounded-pill', DOT_COLOR[variant])}
        />
      )}
      {children}
    </span>
  );
}
