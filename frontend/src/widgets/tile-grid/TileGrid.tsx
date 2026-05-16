import { Home, FileText, MessageSquare, Activity, Lock } from 'lucide-react';
import { Tile } from '@/shared/ui/tile';
import { Chip } from '@/shared/ui/chip';

/**
 * Tile-grid widget — "Things you can try" 4-column grid pinned in
 * `docs/design/M1-identity.md`. One shipped tile (Home, active) +
 * three locked previews keyed to their unlock milestones.
 *
 * Static content for M1; later milestones flip locked tiles to active
 * by editing the entries below.
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
      <Tile
        locked
        icon={<FileText size={18} />}
        title="Documents"
        description="Long-form notes and posts. Read publicly; sign in to write."
        meta={
          <>
            <Chip variant="neutral">
              <Lock size={11} aria-hidden="true" />
              <span className="ml-xs">M2 — Documents</span>
            </Chip>
            <Chip variant="neutral">sign in to write</Chip>
          </>
        }
      />
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
