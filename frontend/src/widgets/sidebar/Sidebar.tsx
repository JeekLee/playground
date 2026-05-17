import { Home, FileText, MessageSquare, Activity, Lock, PanelLeftClose } from 'lucide-react';
import { Brand } from '@/shared/ui/brand';
import { Avatar } from '@/shared/ui/avatar';
import { cn } from '@/shared/lib/cn';
import type { User } from '@/entities/user';
import { userInitials } from '@/entities/user';
import { SignOutButton } from '@/features/sign-out';

/**
 * Sidebar — left column that always renders, but flips between a wide
 * 232px "expanded" mode and a narrow 64px icon-only "rail" mode, the
 * way Obsidian / VSCode handle their primary sidebars.
 *
 * Per design system §8.1 (superseded by M2 docs BC spec §7.1) the Apps
 * section ships with one shipped row (`Home`, active) and three locked
 * previews (`Docs M2`, `Chat M4`, `System status M5`). Locked rows are
 * visual-only.
 *
 * Collapse affordance:
 * - Expanded → small {@link PanelLeftClose} icon next to the brand
 *   wordmark; clicking it collapses the rail.
 * - Collapsed → the brand glyph itself becomes the expand button; no
 *   separate icon. Clicking the glyph re-expands.
 * Keeps the rail to a single column with no auxiliary chrome, the way
 * Obsidian's sidebar reads when narrowed. Topbar carries no toggle —
 * the sidebar surface is always present, so the action belongs here.
 */

export interface SidebarProps {
  user: User | null;
  collapsed: boolean;
  onToggleCollapsed: () => void;
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

export function Sidebar({ user, collapsed, onToggleCollapsed }: SidebarProps) {
  return (
    <aside
      className={cn(
        'sticky top-0 flex h-screen flex-shrink-0 flex-col gap-lg overflow-y-auto border-r border-border bg-surface-soft py-lg transition-[width] duration-[180ms]',
        collapsed ? 'w-[64px] items-center px-xs' : 'w-[232px] px-md',
      )}
      aria-label="Primary navigation"
    >
      {collapsed ? (
        <button
          type="button"
          onClick={onToggleCollapsed}
          aria-label="Expand sidebar"
          aria-pressed={false}
          title="Expand sidebar (⌘\ / Ctrl+\)"
          className="rounded-[7px] transition-opacity duration-[140ms] hover:opacity-80 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-accent"
        >
          <Brand compact />
        </button>
      ) : (
        <div className="flex w-full items-center justify-between">
          <Brand />
          <button
            type="button"
            onClick={onToggleCollapsed}
            aria-label="Collapse sidebar"
            aria-pressed={true}
            title="Collapse sidebar (⌘\ / Ctrl+\)"
            className="flex h-[26px] w-[26px] items-center justify-center rounded-md text-text-muted transition-colors duration-[140ms] hover:bg-surface hover:text-text focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
          >
            <PanelLeftClose size={15} aria-hidden="true" />
          </button>
        </div>
      )}
      <nav aria-label="Apps" className="flex w-full flex-col gap-sm">
        {!collapsed && <span className="px-sm text-eyebrow text-text-subtle">Apps</span>}
        <ul className="flex flex-col gap-xs">
          {APPS.map((row) => (
            <AppsRowItem key={row.label} collapsed={collapsed} {...row} />
          ))}
        </ul>
      </nav>
      <div className="flex-1" />
      {user ? (
        <SignedInFooter user={user} collapsed={collapsed} />
      ) : (
        <SignedOutFooter collapsed={collapsed} />
      )}
    </aside>
  );
}

function AppsRowItem({
  label,
  icon: Icon,
  active,
  locked,
  milestone,
  collapsed,
}: AppsRow & { collapsed: boolean }) {
  const className = cn(
    'flex items-center rounded-md py-[6px] text-small',
    collapsed ? 'h-[32px] w-[32px] justify-center' : 'justify-between px-sm',
    active && 'bg-accent-soft font-semibold text-accent',
    !active && !locked && 'text-text hover:bg-surface',
    locked && 'cursor-default text-text-subtle opacity-[0.72]',
  );

  const content = collapsed ? (
    <Icon size={16} aria-hidden="true" />
  ) : (
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

  const title = collapsed
    ? locked
      ? `${label} — available when ${milestone} ships`
      : label
    : locked
      ? `Available when ${milestone} ships`
      : undefined;

  return (
    <li className={collapsed ? 'flex justify-center' : ''}>
      {locked ? (
        <div role="link" aria-disabled="true" tabIndex={-1} title={title} className={className}>
          {content}
        </div>
      ) : active ? (
        <div aria-current="page" title={title} className={className}>
          {content}
        </div>
      ) : (
        <a href={`/${label.toLowerCase()}`} title={title} className={className}>
          {content}
        </a>
      )}
    </li>
  );
}

function SignedOutFooter({ collapsed }: { collapsed: boolean }) {
  if (collapsed) {
    // Rail mode: drop the prose footer entirely. The sign-in CTA still
    // lives in the topbar's right slot, so we are not hiding the action.
    return null;
  }
  return (
    <div className="rounded-md border border-border bg-surface p-md text-small leading-snug">
      <div className="font-semibold text-text">Not signed in</div>
      <p className="mt-xs text-text-muted">Sign in to write/chat privately.</p>
    </div>
  );
}

function SignedInFooter({ user, collapsed }: { user: User; collapsed: boolean }) {
  if (collapsed) {
    return (
      <div className="flex flex-col items-center gap-xs" title={`Signed in as ${user.email}`}>
        <Avatar initials={userInitials(user)} size="md" />
      </div>
    );
  }
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
