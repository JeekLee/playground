'use client';

import { ChevronDown, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { Chip } from '@/shared/ui/chip';
import { Avatar } from '@/shared/ui/avatar';
import { SearchPill } from '@/shared/ui/search-pill';
import { SignInButton } from '@/features/sign-in';
import type { User } from '@/entities/user';
import { userInitials } from '@/entities/user';

/**
 * Topbar — design system §8.2. Slim, lives inside the main column.
 * `padding: 12px 26px`, `border-bottom: 1px solid border`.
 *
 * Left: sidebar toggle + breadcrumb (current page name).
 * Center: search pill (⌘K), hoisted out of the sidebar so it stays
 * reachable even when the sidebar is collapsed.
 * Right: status chip + primary action OR account pill (signed-in).
 */

export interface TopbarProps {
  breadcrumb: string;
  user: User | null;
  sidebarOpen: boolean;
  onToggleSidebar: () => void;
}

export function Topbar({ breadcrumb, user, sidebarOpen, onToggleSidebar }: TopbarProps) {
  const Icon = sidebarOpen ? PanelLeftClose : PanelLeftOpen;

  return (
    <header
      className="flex items-center gap-md border-b border-border bg-bg px-[26px] py-[12px]"
      aria-label="Page header"
    >
      <div className="flex items-center gap-md">
        <button
          type="button"
          onClick={onToggleSidebar}
          aria-label={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}
          aria-pressed={sidebarOpen}
          title="Toggle sidebar (⌘\ / Ctrl+\)"
          className="flex h-[28px] w-[28px] items-center justify-center rounded-md text-text-muted transition-colors duration-[140ms] hover:bg-surface-soft hover:text-text focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
        >
          <Icon size={16} aria-hidden="true" />
        </button>
        <nav aria-label="Breadcrumb">
          <span className="text-small text-text-muted">{breadcrumb}</span>
        </nav>
      </div>
      <div className="hidden min-w-0 flex-1 justify-center md:flex">
        <div className="w-full max-w-[360px]">
          <SearchPill />
        </div>
      </div>
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
