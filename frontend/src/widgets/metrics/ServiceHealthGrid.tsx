'use client';

import { CheckCircle2, AlertTriangle, OctagonAlert, Circle } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import type { ServiceHealth, ServiceStatus } from '@/entities/metrics';

/**
 * ServiceHealthGrid — the 11-cell service health grid.
 *
 * **11 cells per ADR-15 §17** (NOT the 6-cell wireframe in spec §7.1
 * which is out of date — design context §1 calls this out explicitly):
 *   Row 1 (6 BCs): gateway · identity-api · docs-api · rag-ingestion ·
 *                  rag-chat-api · metrics-api
 *   Row 2 (5 cells): spark-inference-gateway · prometheus-playground ·
 *                    loki-playground · alloy-playground · cadvisor-playground
 *
 * Each cell is 175 × 56 with 12px gaps. Row 2 is intentionally ragged
 * on the right — only 5 cells, not stretched to match row 1's width
 * (per design context §1 coordinate table).
 *
 * Visual variants (per design context §4.1):
 *  - `up`        — surface bg, border `border`, glyph `success`.
 *  - `degraded`  — `warning.soft` bg, `warning` border + glyph.
 *  - `down`      — `danger.soft` bg, `danger` border + glyph.
 *  - skeleton (status missing) — surface bg, `text.subtle` bullet glyph.
 *
 * Verdict source: ADR-15 §9 (BCs), §12 (spark probe), §17 (4
 * observability self-cells use each tool's native readiness endpoint).
 */

// Canonical row layout — driven by the slug, not the array order on
// the wire, so the BC can ship rows in any order without breaking
// the grid visually. (Per design context §2.1 the slug list is
// pinned; missing slugs render the skeleton bullet variant.)
const ROW_1: ReadonlyArray<{ slug: string; label: string }> = [
  { slug: 'gateway', label: 'gateway' },
  { slug: 'identity-api', label: 'identity-api' },
  { slug: 'docs-api', label: 'docs-api' },
  { slug: 'rag-ingestion', label: 'rag-ingestion' },
  { slug: 'rag-chat-api', label: 'rag-chat-api' },
  { slug: 'metrics-api', label: 'metrics-api' },
];

const ROW_2: ReadonlyArray<{ slug: string; label: string }> = [
  { slug: 'spark-inference-gateway', label: 'spark-gateway' },
  { slug: 'prometheus-playground', label: 'prometheus' },
  { slug: 'loki-playground', label: 'loki' },
  { slug: 'alloy-playground', label: 'alloy' },
  { slug: 'cadvisor-playground', label: 'cadvisor' },
];

export interface ServiceHealthGridProps {
  /**
   * Services array from `DashboardResponse.services`. When null, the
   * grid renders all 11 cells in skeleton state (design context
   * Frame 3).
   */
  services: ServiceHealth[] | null;
}

export function ServiceHealthGrid({ services }: ServiceHealthGridProps) {
  const bySlug: Map<string, ServiceHealth> = services
    ? new Map(services.map((s) => [s.name, s]))
    : new Map();

  return (
    <section aria-labelledby="metrics-service-health" className="flex flex-col gap-sm">
      <h2
        id="metrics-service-health"
        className="text-eyebrow text-text-muted"
      >
        Service health
      </h2>
      {/* Two rows. Row 1: 6 cells. Row 2: 5 cells, left-aligned (intentional ragged-right). */}
      <div className="flex flex-col gap-[12px]">
        <div className="grid grid-cols-6 gap-[12px]">
          {ROW_1.map(({ slug, label }) => (
            <ServiceHealthCell key={slug} slug={slug} label={label} entry={bySlug.get(slug)} />
          ))}
        </div>
        <div className="grid grid-cols-6 gap-[12px]">
          {ROW_2.map(({ slug, label }) => (
            <ServiceHealthCell key={slug} slug={slug} label={label} entry={bySlug.get(slug)} />
          ))}
          {/* Sixth column intentionally empty — ragged-right per design. */}
          <div aria-hidden="true" />
        </div>
      </div>
    </section>
  );
}

interface ServiceHealthCellProps {
  slug: string;
  label: string;
  entry: ServiceHealth | undefined;
}

function ServiceHealthCell({ label, entry }: ServiceHealthCellProps) {
  // Missing entry → render the skeleton bullet variant (Frame 3 row
  // pattern). Per design context §2.3: icon `•` in `text.subtle`,
  // service name `—`, detail `loading…`.
  if (!entry) {
    return (
      <div className="flex h-[56px] flex-col justify-center gap-[2px] rounded-md border border-border bg-surface px-[12px] py-[10px]">
        <div className="flex items-center gap-sm">
          <Circle size={10} aria-hidden="true" className="text-text-subtle" />
          <span className="truncate text-[13px] font-semibold text-text-subtle">—</span>
        </div>
        <span className="ml-[18px] truncate text-[11px] font-normal text-text-muted">
          loading…
        </span>
      </div>
    );
  }

  const detail = buildDetailLine(entry);
  const variant = VARIANTS[entry.status];

  return (
    <div className={cn('flex h-[56px] flex-col justify-center gap-[2px] rounded-md border px-[12px] py-[10px]', variant.cell)}>
      <div className="flex items-center gap-sm">
        <StatusGlyph status={entry.status} />
        <span className="truncate text-[13px] font-semibold text-text">{label}</span>
      </div>
      <span className="ml-[18px] truncate text-[11px] font-normal text-text-muted">
        {detail}
      </span>
    </div>
  );
}

const VARIANTS: Record<ServiceStatus, { cell: string }> = {
  up: { cell: 'bg-surface border-border' },
  degraded: { cell: 'bg-warning-soft border-warning' },
  down: { cell: 'bg-danger-soft border-danger' },
};

function StatusGlyph({ status }: { status: ServiceStatus }) {
  if (status === 'up') {
    return <CheckCircle2 size={13} aria-hidden="true" className="text-success" />;
  }
  if (status === 'degraded') {
    return <AlertTriangle size={13} aria-hidden="true" className="text-warning" />;
  }
  return <OctagonAlert size={13} aria-hidden="true" className="text-danger" />;
}

/**
 * The detail-line copy in each cell. Falls back through:
 *   1. `note` (free-form from BC, used for observability cells
 *      and spark degraded).
 *   2. Uptime → `up · 3h 32m` / `down · last seen …`.
 *   3. Status-only `up` / `degraded` / `down`.
 */
function buildDetailLine(entry: ServiceHealth): string {
  if (entry.note) {
    return `${entry.status} · ${entry.note}`;
  }
  if (entry.uptimeSec !== undefined && entry.uptimeSec !== null) {
    return `${entry.status} · ${formatUptime(entry.uptimeSec)}`;
  }
  if (entry.status === 'up' && entry.latencyP95Ms !== undefined) {
    return `up · p95 ${entry.latencyP95Ms} ms`;
  }
  return entry.status;
}

function formatUptime(seconds: number): string {
  const sec = Math.max(0, Math.floor(seconds));
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m`;
  const hr = Math.floor(min / 60);
  const remMin = min % 60;
  if (hr < 24) return `${hr}h ${remMin.toString().padStart(2, '0')}m`;
  const days = Math.floor(hr / 24);
  const remHr = hr % 24;
  return `${days}d ${remHr}h`;
}
