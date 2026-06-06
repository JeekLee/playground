'use client';

import { CheckCircle2, AlertTriangle, OctagonAlert, Circle } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { displayName } from '@/shared/lib/serviceLabel';
import type {
  ContainerSummary,
  ServiceHealth,
  ServiceStatus,
} from '@/entities/metrics';

/**
 * ServiceHealthGrid — 16-cell unified container grid.
 *
 * 2026-05-21 amendment: ADR-15 §17의 11-cell service health grid +
 * 별도 Containers 섹션을 단일 4×4 grid로 통합. spark-inference-gateway는
 * Inference 섹션으로 분리 (Models loaded 카드에 status icon이 박힘).
 *
 * 16 카드 = 6 BC + 4 observability + 6 stack containers:
 *   Row 1 (BCs):    gateway · identity-api · docs-api · rag-ingestion-api
 *   Row 2 (BCs):    chat-api · metrics-api · frontend · prometheus
 *   Row 3 (obs):    loki · alloy · cadvisor · postgres
 *   Row 4 (stack):  redis · kafka-broker · kafka-init · opensearch
 *
 * 카드 종류:
 *   - status + cpu/mem 둘 다 (BC + obs): services[] + containers[] 둘 다 hit.
 *   - cpu/mem only (stack): containers[]만 hit, status는 `—`.
 *
 * 카드 visual variant (per design context §4.1):
 *   - `up`        — surface bg, border `border`, glyph `success`.
 *   - `degraded`  — `warning.soft` bg, `warning` border + glyph.
 *   - `down`      — `danger.soft` bg, `danger` border + glyph.
 *   - status 없음 (stack) — surface bg, border `border`, glyph `text.subtle` bullet.
 *   - skeleton (data null) — surface bg, `text.subtle` bullet glyph.
 *
 * Verdict source: ADR-15 §9 (BCs + observability — Prometheus `up{}` +
 * actuator/readiness probe), ADR-15 §12 (spark — Inference 섹션으로 분리).
 */

interface CellSpec {
  slug: string;
  label: string;
}

interface SubSection {
  id: string;
  heading: string;
  cells: ReadonlyArray<CellSpec>;
}

// 카드 종류는 status 유무로 dynamic 결정 (services[]에 entry 있으면 ✅).
// 사용자가 읽는 카테고리 단위로 sub-section 분리:
//   - applications: 운영자가 직접 운영하는 BC + frontend
//   - metrics: 관측 stack (prometheus / loki / alloy / cadvisor)
//   - datasources: 데이터 스토어 / 메시지 브로커
// spark-inference-gateway는 Inference 섹션에 별도.
// kafka-init은 init container라 제외.
const SUB_SECTIONS: ReadonlyArray<SubSection> = [
  {
    id: 'applications',
    heading: 'Applications',
    cells: [
      { slug: 'playground-backend-gateway', label: 'gateway' },
      { slug: 'playground-backend-identity-api', label: 'identity-api' },
      { slug: 'playground-backend-docs-api', label: 'docs-api' },
      { slug: 'playground-backend-rag-ingestion-api', label: 'rag-ingestion' },
      { slug: 'playground-backend-chat-api', label: 'chat-api' },
      { slug: 'playground-backend-metrics-api', label: 'metrics-api' },
      { slug: 'playground-frontend', label: 'frontend' },
    ],
  },
  {
    id: 'metrics-stack',
    heading: 'Metrics',
    cells: [
      { slug: 'playground-prometheus', label: 'prometheus' },
      { slug: 'playground-loki', label: 'loki' },
      { slug: 'playground-alloy', label: 'alloy' },
      { slug: 'playground-cadvisor', label: 'cadvisor' },
    ],
  },
  {
    id: 'datasources',
    heading: 'Datasources',
    cells: [
      { slug: 'playground-postgres', label: 'postgres' },
      { slug: 'playground-redis', label: 'redis' },
      { slug: 'playground-kafka-broker', label: 'kafka-broker' },
      { slug: 'playground-opensearch', label: 'opensearch' },
    ],
  },
];

export interface ServiceHealthGridProps {
  services: ServiceHealth[] | null;
  containers: ContainerSummary[] | null;
}

export function ServiceHealthGrid({ services, containers }: ServiceHealthGridProps) {
  const bySvc: Map<string, ServiceHealth> = services
    ? new Map(services.map((s) => [s.name, s]))
    : new Map();
  const byContainer: Map<string, ContainerSummary> = containers
    ? new Map(containers.map((c) => [c.name, c]))
    : new Map();

  return (
    <div className="flex flex-col gap-md">
      {SUB_SECTIONS.map((sub) => (
        <section
          key={sub.id}
          aria-labelledby={`metrics-${sub.id}`}
          className="flex flex-col gap-sm"
        >
          <h2 id={`metrics-${sub.id}`} className="text-eyebrow text-text-muted">
            {sub.heading}
          </h2>
          <div className="grid grid-cols-1 gap-[12px] md:grid-cols-2 lg:grid-cols-4">
            {sub.cells.map(({ slug, label }) => (
              <ServiceHealthCell
                key={slug}
                label={label}
                status={bySvc.get(slug)}
                resource={byContainer.get(slug)}
                servicesLoaded={services !== null}
                containersLoaded={containers !== null}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

interface ServiceHealthCellProps {
  label: string;
  status: ServiceHealth | undefined;
  resource: ContainerSummary | undefined;
  servicesLoaded: boolean;
  containersLoaded: boolean;
}

function ServiceHealthCell({
  label,
  status,
  resource,
  servicesLoaded,
  containersLoaded,
}: ServiceHealthCellProps) {
  // Skeleton: 양쪽 데이터가 모두 아직 안 도착했으면 placeholder.
  if (!servicesLoaded && !containersLoaded) {
    return <SkeletonCell label={label} />;
  }

  const variant: keyof typeof VARIANTS = status?.status ?? 'neutral';
  const v = VARIANTS[variant];

  return (
    <div className={cn(
      'flex h-[84px] flex-col justify-between gap-[2px] rounded-md border px-[12px] py-[10px]',
      v.cell,
    )}>
      <div className="flex items-center gap-sm">
        <StatusGlyph status={status?.status} />
        <span className="truncate text-[13px] font-semibold text-text">{label}</span>
      </div>
      <span className="ml-[18px] truncate text-[11px] font-normal text-text-muted">
        {detailLine(status)}
      </span>
      <span className="ml-[18px] truncate text-[11px] font-medium text-text">
        {resourceLine(resource)}
      </span>
    </div>
  );
}

function SkeletonCell({ label }: { label: string }) {
  return (
    <div className="flex h-[84px] flex-col justify-between gap-[2px] rounded-md border border-border bg-surface px-[12px] py-[10px]">
      <div className="flex items-center gap-sm">
        <Circle size={10} aria-hidden="true" className="text-text-subtle" />
        <span className="truncate text-[13px] font-semibold text-text-subtle">{label}</span>
      </div>
      <span className="ml-[18px] truncate text-[11px] font-normal text-text-muted">loading…</span>
      <span className="ml-[18px] truncate text-[11px] font-medium text-text-subtle">—</span>
    </div>
  );
}

type VariantKey = ServiceStatus | 'neutral';
const VARIANTS: Record<VariantKey, { cell: string }> = {
  up: { cell: 'bg-surface border-border' },
  degraded: { cell: 'bg-warning-soft border-warning' },
  down: { cell: 'bg-danger-soft border-danger' },
  neutral: { cell: 'bg-surface border-border' },
};

function StatusGlyph({ status }: { status?: ServiceStatus }) {
  if (status === 'up') {
    return <CheckCircle2 size={13} aria-hidden="true" className="text-success" />;
  }
  if (status === 'degraded') {
    return <AlertTriangle size={13} aria-hidden="true" className="text-warning" />;
  }
  if (status === 'down') {
    return <OctagonAlert size={13} aria-hidden="true" className="text-danger" />;
  }
  // status 없음 (stack 컨테이너) — bullet
  return <Circle size={10} aria-hidden="true" className="text-text-subtle" />;
}

function detailLine(entry: ServiceHealth | undefined): string {
  if (!entry) return '—';
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

function resourceLine(resource: ContainerSummary | undefined): string {
  if (!resource) return '—';
  const cpu = resource.cpuPct;
  const mem = resource.memUsedMb;
  if (cpu === 0 && mem === 0) return '—';
  return `${cpu.toFixed(1)}% · ${formatMb(mem)}`;
}

function formatMb(mb: number): string {
  if (mb >= 1024) {
    return `${(mb / 1024).toFixed(1)} GB`;
  }
  return `${Math.round(mb)} MB`;
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
