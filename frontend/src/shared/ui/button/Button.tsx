import { forwardRef } from 'react';
import type { AnchorHTMLAttributes, ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

/**
 * Button primitive — design system §6.1.
 *
 * Five variants, all `13px / 500`, padding `8px 14px`, radius `md`,
 * transition `all .14s ease`. Focus state: 2px accent outline + 1px offset.
 *
 * The `primary` and `secondary` variants are the two used in M1
 * (sign-in CTA and `Go home` secondary respectively); `ghost`, `danger`,
 * and `disabled` are scaffolded for later milestones.
 *
 * Anchor + button polymorphism: pass `href` to render as `<a>` (used for
 * the OAuth sign-in link — anchors do not require client JS, so the
 * sign-in entry is purely server-rendered).
 */

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

const BASE =
  'inline-flex items-center justify-center gap-sm rounded-md px-[14px] py-sm text-small font-medium ' +
  'transition-all duration-[140ms] ease-out ' +
  'outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent ' +
  'disabled:cursor-not-allowed';

const VARIANTS: Record<ButtonVariant, string> = {
  primary:
    'bg-accent text-surface border border-accent hover:bg-accent-hover hover:border-accent-hover ' +
    'disabled:bg-surface-soft disabled:text-text-subtle disabled:border-border',
  secondary:
    'bg-surface text-text border border-border-strong hover:bg-surface-soft ' +
    'disabled:bg-surface-soft disabled:text-text-subtle disabled:border-border',
  ghost:
    'bg-transparent text-accent border border-transparent hover:bg-accent-soft ' +
    'disabled:text-text-subtle disabled:bg-transparent',
  danger:
    'bg-danger text-surface border border-danger hover:bg-danger-hover hover:border-danger-hover ' +
    'disabled:bg-surface-soft disabled:text-text-subtle disabled:border-border',
};

type CommonProps = {
  variant?: ButtonVariant;
  className?: string;
  children: ReactNode;
};

type AsButton = CommonProps &
  Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className' | 'children'> & {
    href?: undefined;
  };

type AsAnchor = CommonProps &
  Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'className' | 'children'> & {
    href: string;
  };

export type ButtonProps = AsButton | AsAnchor;

export const Button = forwardRef<HTMLAnchorElement | HTMLButtonElement, ButtonProps>(
  function Button(props, ref) {
    const { variant = 'primary', className, children, ...rest } = props;
    const classes = cn(BASE, VARIANTS[variant], className);

    if ('href' in props && props.href !== undefined) {
      const { href, ...anchorRest } = rest as AnchorHTMLAttributes<HTMLAnchorElement> & {
        href: string;
      };
      return (
        <a
          {...anchorRest}
          href={href}
          ref={ref as React.Ref<HTMLAnchorElement>}
          className={classes}
        >
          {children}
        </a>
      );
    }

    return (
      <button
        {...(rest as ButtonHTMLAttributes<HTMLButtonElement>)}
        ref={ref as React.Ref<HTMLButtonElement>}
        className={classes}
      >
        {children}
      </button>
    );
  },
);
