import { Home, FileText, MessageSquare, Activity, Lock, LogIn, PanelLeftClose } from 'lucide-react';
import { Brand } from '@/shared/ui/brand';
import { Avatar } from '@/shared/ui/avatar';
import { cn } from '@/shared/lib/cn';
import type { User } from '@/entities/user';
import { userInitials } from '@/entities/user';
import { SignOutButton } from '@/features/sign-out';

/**
 * The Apps section drives which top-level destination is highlighted. Per
 * M2 spec v5 §7.1, the `Docs` row ships with M2 and lights up `accent.soft`
 * for any route under `/docs`, `/docs/mine`, `/docs/{id}`, `/docs/new`, or
 * `/docs/search`. The active row's target adapts to auth: signed-in users
 * route to `/docs/mine`, anonymous users route to `/docs` (community feed,
 * S2). In S1 we only ship the signed-in target — anonymous users see the
 * Docs row in its shipped-but-inactive state with the public destination
 * (`/docs`, which is S2 — but the route is still wired through the
 * gateway so the link does not 404; it will land on the eventual community
 * feed page once S2 ships).
 */

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
  /**
   * Current pathname — used to compute which Apps row is active. Passed
   * from the client shell which already owns the React render. Defaults
   * to `/` so SSR snapshots render Home-active.
   */
  pathname?: string;
  /**
   * Per design doc M2-docs.md §"Sidebar" / §"My documents" key elements:
   * a `published/total` numeric badge in `accent` on the Docs row when
   * the caller is signed in and the current route is somewhere under
   * `/docs/`. Null for anonymous callers.
   */
  docsBadge?: { published: number; total: number } | null;
}

interface AppsRow {
  label: string;
  icon: typeof Home;
  /** Static route the row links to when shipped. */
  href: string;
  /**
   * Predicate over the current pathname. The Apps row lights up when this
   * returns true. Defaults to exact match against `href`.
   */
  isActive?: (pathname: string) => boolean;
  /**
   * Lock state:
   *  - `milestone` — row is muted, badge reads `Mx 🔒`, click is a no-op.
   *    Used for un-shipped milestones (e.g., M5 System status).
   *  - `auth` — row is muted, badge reads `🔒 Sign in`, click → `/login?next=<href>`.
   *    Per ADR-09 amendment in ADR-14 §G.4: the third sidebar badge state
   *    distinct from milestone-lock. Visualized in design doc frame `54:575`.
   *  - omitted — row is active when the path matches, inactive otherwise.
   */
  locked?: 'milestone' | 'auth';
  milestone?: string;
  /**
   * Optional numeric badge text rendered alongside the row label. Used
   * for the Docs `published/total` count per design doc §"Sidebar".
   */
  badge?: string | null;
}

const APPS_BASE: AppsRow[] = [
  { label: 'Home', icon: Home, href: '/' },
  {
    label: 'Docs',
    icon: FileText,
    href: '/docs/mine',
    // M2 spec §7.1: any route under /docs lights this row.
    isActive: (path) => path === '/docs' || path.startsWith('/docs/'),
  },
  {
    // M4 shipped — Chat row is active for `/chat`. Auth-locked for
    // anonymous callers (per ADR-14 §G.4 amendment to ADR-09; see
    // design doc M4-rag-chat.md §2.8).
    label: 'Chat',
    icon: MessageSquare,
    href: '/chat',
    isActive: (path) => path === '/chat' || path.startsWith('/chat/'),
  },
  {
    label: 'System status',
    icon: Activity,
    href: '/system-status',
    locked: 'milestone',
    milestone: 'M5',
  },
];

export function Sidebar({
  user,
  collapsed,
  onToggleCollapsed,
  pathname = '/',
  docsBadge = null,
}: SidebarProps) {
  // Per design doc §"My documents" the `published/total` badge shows on
  // the Docs row when the caller is on a Docs surface. We keep it
  // visible across every `/docs/...` route (mine, search, new, single)
  // so the count is always one click away — the badge is informational
  // chrome, not a route indicator.
  const showDocsBadge =
    docsBadge !== null && (pathname === '/docs' || pathname.startsWith('/docs/'));
  const isAnonymous = user === null;
  const apps: AppsRow[] = APPS_BASE.map((row) => {
    // The Chat row is M4-shipped, but auth-only — anonymous callers see
    // the `🔒 Sign in` badge instead of a destination tab (per ADR-09
    // amendment in ADR-14 §G.4 + design doc M4-rag-chat.md §2.8).
    if (row.label === 'Chat' && isAnonymous && !row.locked) {
      return { ...row, locked: 'auth' as const };
    }
    if (row.label === 'Docs' && showDocsBadge) {
      return { ...row, badge: `${docsBadge.published}/${docsBadge.total}` };
    }
    return row;
  });
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
          {apps.map((row) => {
            const active = !row.locked && (row.isActive?.(pathname) ?? pathname === row.href);
            return (
              <AppsRowItem
                key={row.label}
                collapsed={collapsed}
                active={active}
                {...row}
              />
            );
          })}
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
  href,
  active,
  locked,
  milestone,
  badge,
  collapsed,
}: AppsRow & { collapsed: boolean; active?: boolean }) {
  const isLocked = Boolean(locked);
  const isAuthLocked = locked === 'auth';
  const isMilestoneLocked = locked === 'milestone';
  const className = cn(
    'flex items-center rounded-md py-[6px] text-small',
    collapsed ? 'h-[32px] w-[32px] justify-center' : 'justify-between px-sm',
    active && 'bg-accent-soft font-semibold text-accent',
    !active && !isLocked && 'text-text hover:bg-surface',
    isMilestoneLocked && 'cursor-default text-text-subtle opacity-[0.72]',
    isAuthLocked && 'text-text-subtle hover:bg-surface',
  );

  const lockBadge = isMilestoneLocked ? (
    <span className="flex items-center gap-xs text-[10px] uppercase tracking-[0.04em] text-text-subtle">
      {milestone}
      <Lock size={11} aria-hidden="true" />
    </span>
  ) : isAuthLocked ? (
    <span className="flex items-center gap-xs text-[10px] uppercase tracking-[0.04em] text-text-subtle">
      <LogIn size={11} aria-hidden="true" />
      Sign in
    </span>
  ) : null;

  const content = collapsed ? (
    <Icon size={16} aria-hidden="true" />
  ) : (
    <>
      <span className="flex items-center gap-sm">
        <Icon size={16} aria-hidden="true" />
        <span>{label}</span>
      </span>
      {lockBadge ?? (badge ? (
        <span
          className={cn(
            'rounded-pill px-[7px] py-[1px] font-mono text-[10px]',
            active
              ? 'bg-accent text-surface'
              : 'bg-accent-soft text-accent',
          )}
          aria-label={`${badge} documents`}
        >
          {badge}
        </span>
      ) : (
        active && <span aria-hidden="true" className="h-[6px] w-[6px] rounded-pill bg-accent" />
      ))}
    </>
  );

  const title = collapsed
    ? isMilestoneLocked
      ? `${label} — available when ${milestone} ships`
      : isAuthLocked
        ? `${label} — sign in to use`
        : label
    : isMilestoneLocked
      ? `Available when ${milestone} ships`
      : isAuthLocked
        ? 'Sign in to use Chat'
        : undefined;

  return (
    <li className={collapsed ? 'flex justify-center' : ''}>
      {isMilestoneLocked ? (
        <div role="link" aria-disabled="true" tabIndex={-1} title={title} className={className}>
          {content}
        </div>
      ) : isAuthLocked ? (
        <a
          href={`/login?next=${encodeURIComponent(href)}`}
          title={title}
          className={className}
          aria-label={`${label} — sign in to use`}
        >
          {content}
        </a>
      ) : active ? (
        <a aria-current="page" href={href} title={title} className={className}>
          {content}
        </a>
      ) : (
        <a href={href} title={title} className={className}>
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
