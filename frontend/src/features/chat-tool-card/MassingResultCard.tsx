'use client';

import { useCallback, useEffect, useState } from 'react';
import { Box, ChevronDown, ChevronRight, Download } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { zonePalette } from '@/shared/ui/tokens';
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
 *   - preview      = `▸ 3D 미리보기` accordion above Program details —
 *                    lazy-loads @google/model-viewer and renders the .glb
 *                    from `${outputUrl}/preview` (320px-tall full-width viewer,
 *                    camera-controls + auto-rotate). Absent when there is
 *                    no outputUrl (in-flight card, failed artifact); a fetch
 *                    error (legacy rows without .glb) swaps the viewer for
 *                    fallback copy.
 *
 * In-flight state (when `toolCard.kind === 'in_flight'`):
 *   - Skeleton card per design doc §2.2 lifecycle section.
 *     Box icon + `매싱 모델` + `Running…` summary + small spinner.
 *     No Download button, no accordion. briefTitle is not available
 *     yet in the in-flight state; it renders once the tool_result
 *     event lands.
 *
 * Wire shape contract: see `MassingProgramJson` in
 * `shared/api/chat.ts`. The accordion table renders 2 columns (ROOM /
 * AREA) for legacy zone-only payloads and 4 columns (ZONE / ROOM /
 * FLOOR / AREA, zone group-headed) for room-split payloads
 * (2026-06-05). Hotspot labels ride `labelAnchor` on named-room rows.
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
        hasDownloadUrl || hasProgram ? (
          <div className="flex w-full flex-col gap-xs">
            {hasDownloadUrl && (
              <PreviewAccordion
                previewUrl={`${state.toolResult.outputUrl}/preview`}
                rooms={program?.rooms ?? []}
              />
            )}
            {hasProgram && (
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
            )}
          </div>
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

// `zonePalette` (shared/ui/tokens) mirrors glb_serializer.py's _PALETTE in
// order/value — zones cycle by first-appearance. The hex literals live in
// the tokens dir (lint forbids them here); the server emits rooms rows in
// box zone-appearance order so the index lines up with the .glb colors.
function zoneColorMap(rooms: MassingProgramJson['rooms']): Map<string, string> {
  const map = new Map<string, string>();
  for (const room of rooms) {
    if (room.zone && !map.has(room.zone)) {
      map.set(room.zone, zonePalette[map.size % zonePalette.length]!);
    }
  }
  return map;
}

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

function PreviewAccordion({
  previewUrl,
  rooms,
}: {
  previewUrl: string;
  rooms: MassingProgramJson['rooms'];
}) {
  // Inline 3D preview per design spec 2026-06-05-massing-glb-preview —
  // the backend serves the .glb sibling of the .3dm at `${outputUrl}/preview`.
  const [open, setOpen] = useState(false);
  // Intentionally never reset on close/reopen — a 404 here means the row
  // predates the .glb sibling (permanent), so retrying would just 404 again.
  const [failed, setFailed] = useState(false);
  const [viewerReady, setViewerReady] = useState(false);

  useEffect(() => {
    if (open) {
      // Defer the heavy model-viewer chunk until first open. The component
      // SSRs (it's a client component, but Next still renders initial HTML),
      // and @google/model-viewer touches `customElements` at module scope —
      // a top-level import would crash the server render. The unknown
      // element upgrades in place once the module registers it.
      void import('@google/model-viewer').then(() => setViewerReady(true));
    }
  }, [open]);

  const viewerRef = useCallback((el: HTMLElement | null) => {
    // React 18 sets `on*` props on custom elements as attributes, not
    // listeners — attach the DOM `error` event by hand. Fires when the
    // .glb fetch fails (404 on legacy rows without a preview sibling).
    el?.addEventListener('error', () => setFailed(true), { once: true });
  }, []);

  // One zone→hue map per render (not per hotspot) — first-appearance index
  // matches the glb's zone color slots (server emits rows in box order).
  const zoneColors = zoneColorMap(rooms);

  return (
    <div className="flex w-full flex-col gap-sm">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        aria-controls="massing-3d-preview"
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
        <span>3D 미리보기</span>
      </button>
      {open && failed && (
        <p className="rounded-md bg-surface-soft px-md py-sm text-[12px] text-text-muted">
          미리보기를 불러올 수 없습니다 — 이 모델은 3D 미리보기가 제공되기 전에 생성되었습니다.
        </p>
      )}
      {open && !failed && (
        // Sizing/decoration live on this plain div: React 18 writes
        // `className` on CUSTOM elements as a literal `className` attribute
        // (not `class`), so Tailwind selectors never match the viewer and it
        // collapses to model-viewer's default 300×150 host size. The wrapper
        // owns the design tokens; the viewer fills it via inline style
        // (`style` IS special-cased by React on every element).
        <div className="h-[320px] w-full overflow-hidden rounded-md bg-surface-soft">
          <model-viewer
            ref={viewerRef}
            id="massing-3d-preview"
            src={previewUrl}
            camera-controls
            auto-rotate
            shadow-intensity="1"
            style={{ width: '100%', height: '100%' }}
          >
            {/* 업그레이드 전에는 슬롯이 없어 라벨이 좌상단에 쌓여 보인다 — 모듈 등록 후에만 렌더. */}
            {viewerReady &&
              rooms
                .filter((r) => r.labelAnchor)
                .map((r, i) => (
                  <div
                    key={`${r.zone}-${r.name}-${i}`}
                    aria-hidden="true"
                    slot={`hotspot-room-${i}`}
                    data-position={`${r.labelAnchor!.x} ${r.labelAnchor!.y} ${r.labelAnchor!.z}`}
                    data-normal="0 1 0"
                    className="pointer-events-none inline-flex items-center gap-xs rounded-md bg-surface/90 px-xs py-[2px] text-[11px] font-medium text-text shadow-card"
                  >
                    <span
                      aria-hidden="true"
                      className="inline-block h-[8px] w-[8px] rounded-full"
                      style={{ backgroundColor: zoneColors.get(r.zone ?? '') ?? zonePalette[0] }}
                    />
                    {r.name}
                  </div>
                ))}
          </model-viewer>
        </div>
      )}
    </div>
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
  // Room-split payloads (2026-06-05) carry per-room `zone` / `floor`. When
  // any row has a numeric floor we render the 4-column split table (ZONE /
  // ROOM / FLOOR / AREA, zone group-headed); legacy zone-only payloads fall
  // through to the 2-column (ROOM / AREA) table unchanged.
  //
  // Footer summary: when there are more rooms than the visible cap,
  // surface `… and N more rooms (총 K실, L m²)` per design doc §2.4 —
  // the parenthetical pieces are computed client-side from the same
  // `programJson` so no backend addition is required.
  const VISIBLE_CAP = 10;
  const rooms = program.rooms;
  const isSplit = rooms.some((r) => typeof r.floor === 'number');
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
            {isSplit && (
              <th scope="col" className="pb-xs text-eyebrow text-text-muted">Zone</th>
            )}
            <th scope="col" className="pb-xs text-eyebrow text-text-muted">Room</th>
            {isSplit && (
              <th scope="col" className="pb-xs text-right text-eyebrow text-text-muted">Floor</th>
            )}
            <th scope="col" className="pb-xs text-right text-eyebrow text-text-muted">Area</th>
          </tr>
        </thead>
        <tbody>
          {visible.map((room, idx) => (
            <tr key={`${room.name}-${idx}`} className="border-t border-border first:border-t-0">
              {isSplit && (
                <td className="py-[10px] text-[13px] text-text-muted">
                  {idx === 0 || visible[idx - 1]?.zone !== room.zone ? room.zone ?? '' : ''}
                </td>
              )}
              <td className="py-[10px] text-[13px] text-text">{room.name}</td>
              {isSplit && (
                <td className="py-[10px] text-right font-mono text-[13px] text-text-muted">
                  {typeof room.floor === 'number'
                    ? room.floor > 0 ? `${room.floor}F` : `B${-room.floor}`
                    : '—'}
                </td>
              )}
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
    const e = entry as {
      name?: unknown;
      areaM2?: unknown;
      zone?: unknown;
      floor?: unknown;
      labelAnchor?: unknown;
    };
    if (typeof e.name !== 'string' || typeof e.areaM2 !== 'number') return [];
    const anchorRaw = e.labelAnchor as { x?: unknown; y?: unknown; z?: unknown } | undefined;
    const labelAnchor =
      anchorRaw &&
      typeof anchorRaw.x === 'number' &&
      typeof anchorRaw.y === 'number' &&
      typeof anchorRaw.z === 'number'
        ? { x: anchorRaw.x, y: anchorRaw.y, z: anchorRaw.z }
        : undefined;
    return [{
      name: e.name,
      areaM2: e.areaM2,
      zone: typeof e.zone === 'string' ? e.zone : undefined,
      floor: typeof e.floor === 'number' ? e.floor : undefined,
      labelAnchor,
    }];
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
