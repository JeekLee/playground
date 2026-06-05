'use client';

import { useState } from 'react';
import { Box, ChevronDown, ChevronRight, Download } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import type { MassingProgramJson, ToolCardState } from '@/entities/chat';
import { ToolResultCard } from './ToolResultCard';

/**
 * `MassingResultCard` — M8 `generate_massing` happy-path card per design
 * doc §2.3 (collapsed, frame `78:1347`) + §2.4 (expanded, frame
 * `78:1392`).
 *
 * Filled slots (per design doc §4.2):
 *   - icon         = Lucide `Box` (18 px, strokeWidth 1.75)
 *   - name         = `매싱 모델 · {briefTitle}` (briefTitle absent → plain `매싱 모델`)
 *   - summary      = backend-emitted `summary` string (Korean-fixed
 *                    per ADR-18 §5 — e.g., `"12실 · 3층 · 총 480 m²"`).
 *                    The FE renders verbatim; no client-side i18n.
 *   - primaryAction = `↓ Download .3dm` plain `<a href download>` —
 *                    relative `outputUrl` from the SSE payload, gateway
 *                    session cookie carries `X-User-Id` to the BC's
 *                    download endpoint. NO `fetch` + `blob` juggling.
 *   - accordion    = `▸ Program details` (collapsed) / `▾ Program
 *                    details` (expanded). Mirrors the M4 citation
 *                    accordion visual pattern verbatim.
 *
 * In-flight state (when `toolCard.kind === 'in_flight'`):
 *   - Skeleton card per design doc §2.2 lifecycle section.
 *     Box icon + `매싱 모델` + `Running…` summary + small spinner.
 *     No Download button, no accordion. briefTitle is not available
 *     yet in the in-flight state; it renders once the tool_result
 *     event lands.
 *
 * Wire shape contract: see `MassingProgramJson` in
 * `shared/api/chat.ts`. The wire ships only `{name, areaM2}` per room
 * — per-room floor / dimensions live in the algorithm output
 * (`RoomBox`) which is serialized into the `.3dm` file, NOT the JSON
 * response (ADR-18 §9 + §11). The accordion table therefore renders
 * the 2 columns the wire carries today (ROOM / AREA); a future
 * backend amendment that surfaces `RoomBox` data into the response
 * would let M8.1 light up FLOOR / DIMENSIONS.
 */

export interface MassingResultCardProps {
  state: Extract<ToolCardState, { kind: 'in_flight' | 'result' }>;
}

export function MassingResultCard({ state }: MassingResultCardProps) {
  const [open, setOpen] = useState(false);

  if (state.kind === 'in_flight') {
    return (
      <ToolResultCard
        ariaLabel="Tool call in flight: 매싱 모델"
        icon={<Box size={18} aria-hidden="true" strokeWidth={1.75} />}
        name={<span className="text-[14px] font-semibold text-text">매싱 모델</span>}
        summary={
          <span className="inline-flex items-center gap-sm text-text-muted">
            <span>Running…</span>
            <Spinner />
          </span>
        }
        primaryAction={null}
        footer={null}
      />
    );
  }

  const program = readProgramJson(state.toolResult.programJson);
  const hasProgram = program !== null && program.rooms.length > 0;
  const hasDownloadUrl =
    typeof state.toolResult.outputUrl === 'string' && state.toolResult.outputUrl.length > 0;

  return (
    <ToolResultCard
      ariaLabel="Tool result: 매싱 모델"
      icon={<Box size={18} aria-hidden="true" strokeWidth={1.75} />}
      name={
        <span className="text-[14px] font-semibold text-text">
          매싱 모델
          {state.toolResult.briefTitle && (
            <span className="font-normal text-text-muted"> · {state.toolResult.briefTitle}</span>
          )}
        </span>
      }
      summary={
        <span className="font-medium text-text">{state.toolResult.summary}</span>
      }
      primaryAction={hasDownloadUrl ? <DownloadDotThreeDmButton href={state.toolResult.outputUrl!} /> : null}
      footer={
        hasProgram ? (
          <button
            type="button"
            onClick={() => setOpen((prev) => !prev)}
            aria-expanded={open}
            aria-controls="massing-program-details"
            className={cn(
              'inline-flex items-center gap-xs self-start rounded-md px-xs py-[2px] text-[12px] transition-colors duration-[140ms]',
              'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
              open
                ? 'font-semibold text-accent hover:bg-accent-soft'
                : 'font-medium text-text-muted hover:bg-surface-soft hover:text-text',
            )}
          >
            {open ? (
              <ChevronDown size={12} aria-hidden="true" />
            ) : (
              <ChevronRight size={12} aria-hidden="true" />
            )}
            <span>Program details</span>
          </button>
        ) : null
      }
      expanded={
        open && hasProgram ? (
          <ProgramDetailsTable program={program!} />
        ) : null
      }
    />
  );
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function Spinner() {
  // Small olive-accent rotating ring — `tool-spinner` keyframe in
  // tailwind.config.ts. The CSS spinner is preferable to a Lucide
  // `Loader` glyph because we can color the segment with `border-accent`
  // and leave the rest transparent — matches the citation-accordion's
  // calm visual language vs. a heavy "loading" spinner.
  return (
    <span
      aria-hidden="true"
      className="inline-block h-[12px] w-[12px] animate-tool-spinner rounded-full border-2 border-border border-t-accent"
    />
  );
}

function DownloadDotThreeDmButton({ href }: { href: string }) {
  // Plain `<a href download>` per ADR-17 §3 — relative URL, gateway
  // attaches session cookie, the BC's `Content-Disposition` sets the
  // saved filename. Empty `download=""` lets the server's
  // `Content-Disposition: attachment; filename=...` win, so the user
  // saves `massing-<briefSlug>-<timestamp>.3dm` instead of the UUID.
  return (
    <a
      href={href}
      download=""
      className={cn(
        'inline-flex h-[32px] items-center gap-xs rounded-md bg-accent px-[14px]',
        'text-[13px] font-semibold leading-none text-surface',
        'transition-colors duration-[140ms] hover:bg-accent-hover',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
      )}
    >
      <Download size={13} aria-hidden="true" strokeWidth={2.25} />
      <span>Download .3dm</span>
    </a>
  );
}

interface ProgramDetailsTableProps {
  program: MassingProgramJson;
}

function ProgramDetailsTable({ program }: ProgramDetailsTableProps) {
  // Wire shape carries only `{name, areaM2}` per room (ADR-18 §9 — the
  // JSON Schema). The design doc §2.4 mockup shows a 4-column table
  // (FLOOR / ROOM / DIMENSIONS / AREA), but FLOOR + DIMENSIONS are
  // derived from the `RoomBox` algorithm output which is serialized
  // into the `.3dm` file, NOT the JSON response. We render the 2
  // columns the wire actually ships; FLOOR / DIMENSIONS would light
  // up automatically the day the backend extends `tool_result.programJson`
  // to carry per-room layout fields.
  //
  // Footer summary: when there are more rooms than the visible cap,
  // surface `… and N more rooms (총 K실, L m²)` per design doc §2.4 —
  // the parenthetical pieces are computed client-side from the same
  // `programJson` so no backend addition is required.
  const VISIBLE_CAP = 6;
  const rooms = program.rooms;
  const visible = rooms.slice(0, VISIBLE_CAP);
  const overflow = rooms.length - visible.length;
  const totalArea = rooms.reduce((sum, r) => sum + (Number.isFinite(r.areaM2) ? r.areaM2 : 0), 0);

  return (
    <div
      id="massing-program-details"
      className="flex flex-col gap-sm"
      role="region"
      aria-label="Program details"
    >
      <table className="w-full border-collapse text-left">
        <thead>
          <tr>
            <th
              scope="col"
              className="pb-xs text-eyebrow text-text-muted"
            >
              Room
            </th>
            <th
              scope="col"
              className="pb-xs text-right text-eyebrow text-text-muted"
            >
              Area
            </th>
          </tr>
        </thead>
        <tbody>
          {visible.map((room, idx) => (
            <tr
              key={`${room.name}-${idx}`}
              className="border-t border-border first:border-t-0"
            >
              <td className="py-[10px] text-[13px] text-text">{room.name}</td>
              <td className="py-[10px] text-right font-mono text-[13px] text-text-muted">
                {formatAreaM2(room.areaM2)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {overflow > 0 && (
        <p className="text-[12px] font-medium text-text-muted">
          … and {overflow} more room{overflow === 1 ? '' : 's'} (총 {rooms.length}실, {formatAreaM2(totalArea)})
        </p>
      )}
    </div>
  );
}

/**
 * Narrow the `programJson: Record<string, unknown>` wire type to the
 * M8-specific shape. Returns `null` if the shape doesn't match — the
 * caller falls through to "no accordion" rather than crashing on a
 * malformed payload.
 *
 * This is intentionally loose: we read `rooms[]` if present, accept
 * each entry that carries `{name: string, areaM2: number}`, and drop
 * everything else. A future M8.1 wire-shape extension (per-room
 * floor/dimensions) lands here as additional optional fields.
 */
function readProgramJson(raw: Record<string, unknown> | undefined): MassingProgramJson | null {
  if (!raw || typeof raw !== 'object') return null;
  const roomsRaw = (raw as { rooms?: unknown }).rooms;
  if (!Array.isArray(roomsRaw)) return null;
  const rooms = roomsRaw.flatMap((entry): MassingProgramJson['rooms'][number][] => {
    if (!entry || typeof entry !== 'object') return [];
    const e = entry as { name?: unknown; areaM2?: unknown };
    if (typeof e.name !== 'string' || typeof e.areaM2 !== 'number') return [];
    return [{ name: e.name, areaM2: e.areaM2 }];
  });
  if (rooms.length === 0) return { rooms: [] };
  return {
    rooms,
    siteWidthM:
      typeof (raw as { siteWidthM?: unknown }).siteWidthM === 'number'
        ? ((raw as { siteWidthM: number }).siteWidthM)
        : undefined,
    siteDepthM:
      typeof (raw as { siteDepthM?: unknown }).siteDepthM === 'number'
        ? ((raw as { siteDepthM: number }).siteDepthM)
        : undefined,
    floorHeightM:
      typeof (raw as { floorHeightM?: unknown }).floorHeightM === 'number'
        ? ((raw as { floorHeightM: number }).floorHeightM)
        : undefined,
  };
}

function formatAreaM2(m2: number): string {
  // Drop trailing zeros for whole numbers, keep 1 decimal otherwise.
  // The wire ships REAL (per `arch.outputs.total_area_m2` per ADR-18
  // §18) — float drift is rare but possible (e.g., 48.000001).
  const rounded = Math.round(m2 * 10) / 10;
  const text = Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1);
  return `${text} m²`;
}
