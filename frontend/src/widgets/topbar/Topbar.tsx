import { ChevronDown } from 'lucide-react';
import { Chip } from '@/shared/ui/chip';
import { Avatar } from '@/shared/ui/avatar';
import { SignInButton } from '@/features/sign-in';
import type { User } from '@/entities/user';
import { userInitials } from '@/entities/user';

/**
 * Topbar — design system §8.2. Slim, lives inside the main column.
 * `padding: 12px 26px`, `border-bottom: 1px solid border`.
 *
 * Left: breadcrumb (current page name).
 * Right: status chip + primary action OR account pill (signed-in).
 */

export interface TopbarProps {
  breadcrumb: string;
  user: User | null;
}

export function Topbar({ breadcrumb, user }: TopbarProps) {
  return (
    <header
      className="flex items-center justify-between border-b border-border bg-bg px-[26px] py-[12px]"
      aria-label="Page header"
    >
      <nav aria-label="Breadcrumb">
        <span className="text-small text-text-muted">{breadcrumb}</span>
      </nav>
      <div className="flex items-center gap-md">
        {user ? (
          <>
            <Chip variant="success" dot>
              Signed in
            </Chip>
            <AccountPill user={user} />
          </>
        ) : (
          <>
            <Chip variant="neutral">Viewing publicly</Chip>
            <SignInButton />
          </>
        )}
      </div>
    </header>
  );
}

function AccountPill({ user }: { user: User }) {
  return (
    <button
      type="button"
      className="flex items-center gap-sm rounded-pill border border-border bg-surface px-[10px] py-[4px] text-small text-text transition-colors duration-[140ms] hover:bg-surface-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
      aria-label={`Account menu for ${user.displayName}`}
    >
      <Avatar initials={userInitials(user)} size="sm" />
      <span className="font-medium">{user.displayName}</span>
      <ChevronDown size={14} aria-hidden="true" className="text-text-muted" />
    </button>
  );
}
