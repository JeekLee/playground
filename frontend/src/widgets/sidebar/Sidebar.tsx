import { Home, FileText, MessageSquare, Activity, Lock, PanelLeftClose } from 'lucide-react';
import { Brand } from '@/shared/ui/brand';
import { Avatar } from '@/shared/ui/avatar';
import { cn } from '@/shared/lib/cn';
import type { User } from '@/entities/user';
import { userInitials } from '@/entities/user';
import { SignOutButton } from '@/features/sign-out';

/**
 * Sidebar — 232px fixed column on the left.
 *
 * Per design system §8.1 (superseded by M2 docs BC spec §7.1) the Apps
 * section ships with one shipped row (`Home`, active) and three locked
 * previews (`Docs M2`, `Chat M4`, `System status M5`). The locked rows
 * are visually present but non-actionable per PRD "Sidebar locked rows
 * are visual-only".
 *
 * The account footer flips between the public "Not signed in" copy and
 * the signed-in identity card based on the `user` prop.
 */

export interface SidebarProps {
  user: User | null;
  /**
   * Optional collapse handler. When provided, a chevron-style button
   * appears next to the brand wordmark so the user can hide the sidebar
   * from within its own surface (standard Notion/Linear/VSCode UX).
   * Omit to render the sidebar without a collapse affordance (used by
   * routes that never offer the toggle).
   */
  onCollapse?: () => void;
}

interface AppsRow {
  label: string;
  icon: typeof Home;
  active?: boolean;
  locked?: boolean;
  milestone?: string;
}

const APPS: AppsRow[] = [
  { label: 'Home', icon: Home, active: true },
  { label: 'Docs', icon: FileText, locked: true, milestone: 'M2' },
  { label: 'Chat', icon: MessageSquare, locked: true, milestone: 'M4' },
  { label: 'System status', icon: Activity, locked: true, milestone: 'M5' },
];

export function Sidebar({ user, onCollapse }: SidebarProps) {
  return (
    <aside
      className="sticky top-0 flex h-screen w-[232px] flex-shrink-0 flex-col gap-lg overflow-y-auto border-r border-border bg-surface-soft px-md py-lg"
      aria-label="Primary navigation"
    >
      <div className="flex items-center justify-between">
        <Brand />
        {onCollapse && (
          <button
            type="button"
            onClick={onCollapse}
            aria-label="Collapse sidebar"
            title="Collapse sidebar (⌘\ / Ctrl+\)"
            className="flex h-[26px] w-[26px] items-center justify-center rounded-md text-text-muted transition-colors duration-[140ms] hover:bg-surface hover:text-text focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
          >
            <PanelLeftClose size={15} aria-hidden="true" />
          </button>
        )}
      </div>
      <nav aria-label="Apps" className="flex flex-col gap-sm">
        <span className="px-sm text-eyebrow text-text-subtle">Apps</span>
        <ul className="flex flex-col gap-xs">
          {APPS.map((row) => (
            <AppsRowItem key={row.label} {...row} />
          ))}
        </ul>
      </nav>
      <div className="flex-1" />
      {user ? <SignedInFooter user={user} /> : <SignedOutFooter />}
    </aside>
  );
}

function AppsRowItem({ label, icon: Icon, active, locked, milestone }: AppsRow) {
  const className = cn(
    'flex items-center justify-between rounded-md px-sm py-[6px] text-small',
    active && 'bg-accent-soft font-semibold text-accent',
    !active && !locked && 'text-text hover:bg-surface',
    locked && 'cursor-default text-text-subtle opacity-[0.72]',
  );

  const content = (
    <>
      <span className="flex items-center gap-sm">
        <Icon size={16} aria-hidden="true" />
        <span>{label}</span>
      </span>
      {locked && (
        <span className="flex items-center gap-xs text-[10px] uppercase tracking-[0.04em] text-text-subtle">
          {milestone}
          <Lock size={11} aria-hidden="true" />
        </span>
      )}
      {active && <span aria-hidden="true" className="h-[6px] w-[6px] rounded-pill bg-accent" />}
    </>
  );

  return (
    <li>
      {locked ? (
        <div
          role="link"
          aria-disabled="true"
          tabIndex={-1}
          title={`Available when ${milestone} ships`}
          className={className}
        >
          {content}
        </div>
      ) : active ? (
        <div
          aria-current="page"
          className={className}
        >
          {content}
        </div>
      ) : (
        <a href={`/${label.toLowerCase()}`} className={className}>
          {content}
        </a>
      )}
    </li>
  );
}

function SignedOutFooter() {
  return (
    <div className="rounded-md border border-border bg-surface p-md text-small leading-snug">
      <div className="font-semibold text-text">Not signed in</div>
      <p className="mt-xs text-text-muted">Sign in to write/chat privately.</p>
    </div>
  );
}

function SignedInFooter({ user }: { user: User }) {
  return (
    <div className="flex flex-col gap-sm rounded-md border border-border bg-surface p-md">
      <div className="flex items-center gap-sm">
        <Avatar initials={userInitials(user)} size="md" />
        <div className="flex min-w-0 flex-col leading-tight">
          <span className="truncate text-[12px] font-semibold text-text">{user.displayName}</span>
          <span className="truncate text-[10.5px] text-text-muted">{user.email}</span>
        </div>
      </div>
      <SignOutButton />
    </div>
  );
}
