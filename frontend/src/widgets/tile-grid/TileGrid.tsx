import Link from 'next/link';
import { Home, FileText, MessageSquare, Activity, Lock } from 'lucide-react';
import { Tile } from '@/shared/ui/tile';
import { Chip } from '@/shared/ui/chip';

/**
 * Tile-grid widget — "Things you can try" 4-column grid pinned in
 * `docs/design/M1-identity.md`. M2-shipped tiles are now Home + Documents;
 * Chat (M4) and System status (M5) stay locked.
 *
 * Documents tile becomes interactive on M2 — wraps the visual Tile in a
 * Next.js Link to `/docs` (community feed). Hover lift on the Tile
 * primitive triggers because neither `locked` nor `active` is set.
 */

export function TileGrid() {
  return (
    <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-4">
      <Tile
        active
        icon={<Home size={18} />}
        title="Home"
        description="You're here. The dashboard for everything else as it ships."
        meta={
          <Chip variant="success" dot>
            shipped
          </Chip>
        }
      />
      <Link
        href="/docs"
        className="block rounded-md focus:outline-none focus-visible:ring-2 focus-visible:ring-accent"
      >
        <Tile
          active
          icon={<FileText size={18} />}
          title="Documents"
          description="Long-form notes and posts. Read publicly, sign in to write your own."
          meta={
            <Chip variant="success" dot>
              shipped
            </Chip>
          }
        />
      </Link>
      <Tile
        locked
        icon={<MessageSquare size={18} />}
        title="Chat"
        description="Ask the model questions. Public for everyone when it ships."
        meta={
          <>
            <Chip variant="neutral">
              <Lock size={11} aria-hidden="true" />
              <span className="ml-xs">M4 — Chat</span>
            </Chip>
            <Chip variant="accent">PUBLIC when ready</Chip>
          </>
        }
      />
      <Tile
        locked
        icon={<Activity size={18} />}
        title="System status"
        description="Peek at how the platform is feeling — read-only metrics."
        meta={
          <>
            <Chip variant="neutral">
              <Lock size={11} aria-hidden="true" />
              <span className="ml-xs">M5 — System status</span>
            </Chip>
            <Chip variant="accent">PUBLIC when ready</Chip>
          </>
        }
      />
    </div>
  );
}
