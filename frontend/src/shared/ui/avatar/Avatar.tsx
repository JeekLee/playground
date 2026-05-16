import { cn } from '@/shared/lib/cn';

/**
 * Avatar — khaki-initials fallback per `docs/design/M1-identity.md`.
 *
 * M1 ships the initials-only variant (P1 in the PRD is "Avatar URL
 * caching/proxying so the frontend never hits Google directly"). The
 * design context explicitly shows `JL` initials on a khaki circle as
 * the loading-state fallback, and the Signed-in Home spec defers
 * image-URL handling. So this primitive renders initials only; the
 * URL-backed variant lands when the proxy lands.
 */

export interface AvatarProps {
  initials: string;
  size?: 'sm' | 'md';
  className?: string;
}

const SIZES: Record<NonNullable<AvatarProps['size']>, { box: string; text: string }> = {
  sm: { box: 'h-6 w-6', text: 'text-[10px]' },
  md: { box: 'h-7 w-7', text: 'text-[11px]' },
};

export function Avatar({ initials, size = 'md', className }: AvatarProps) {
  const sz = SIZES[size];
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-flex items-center justify-center rounded-pill bg-khaki text-surface font-semibold leading-none',
        sz.box,
        sz.text,
        className,
      )}
    >
      {initials}
    </span>
  );
}
