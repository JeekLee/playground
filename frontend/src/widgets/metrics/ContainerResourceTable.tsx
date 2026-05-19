'use client';

import { cn } from '@/shared/lib/cn';
import type { ContainerSummary } from '@/entities/metrics';

/**
 * ContainerResourceTable — compact table of container resources per
 * spec §5.2's `containers` array (cAdvisor source).
 *
 * Spec §7.1 ASCII wireframe mentions a container resource table as one
 * of the 19 widgets. Design context M5-metrics.md's static Figma
 * frames don't render this widget (the dashboard is viewport-locked
 * per §1.5 and the table doesn't fit in the 900px canvas); this
 * implementation renders it inside the scrollable widget area so
 * operators on taller viewports get the data.
 *
 * Columns: name · CPU % · memory used / limit · restarts.
 * Restart count > 0 is highlighted in `warning`.
 */

export interface ContainerResourceTableProps {
  containers: ContainerSummary[] | null;
}

export function ContainerResourceTable({ containers }: ContainerResourceTableProps) {
  const rows = containers ?? [];

  return (
    <section
      aria-labelledby="metrics-containers"
      className="flex flex-col gap-sm"
    >
      <h2 id="metrics-containers" className="text-eyebrow text-text-muted">
        Containers ({rows.length})
      </h2>
      <div className="overflow-hidden rounded-md border border-border bg-surface">
        <table className="w-full table-fixed text-left">
          <thead>
            <tr className="border-b border-border bg-surface-soft">
              <Th>Name</Th>
              <Th align="right">CPU</Th>
              <Th align="right">Memory</Th>
              <Th align="right">Restarts</Th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={4} className="px-md py-md text-center text-[12px] text-text-subtle">
                  loading…
                </td>
              </tr>
            ) : (
              rows.map((c) => <ContainerRow key={c.name} container={c} />)
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function Th({ children, align = 'left' }: { children: React.ReactNode; align?: 'left' | 'right' }) {
  return (
    <th
      scope="col"
      className={cn(
        'px-md py-[8px] text-eyebrow text-text-muted',
        align === 'right' && 'text-right',
      )}
    >
      {children}
    </th>
  );
}

function ContainerRow({ container }: { container: ContainerSummary }) {
  const memPct = (container.memUsedMb / container.memLimitMb) * 100;
  const hasRestarts = container.restartCount > 0;
  return (
    <tr className="border-b border-border last:border-b-0">
      <td className="truncate px-md py-[8px] text-[13px] font-medium text-text">
        {container.name}
      </td>
      <td className="px-md py-[8px] text-right text-[13px] font-mono text-text">
        {container.cpuPct.toFixed(1)}%
      </td>
      <td className="px-md py-[8px] text-right text-[12px] text-text-muted">
        <span className="font-mono text-text">{container.memUsedMb}</span>
        <span className="mx-[4px]">/</span>
        <span className="font-mono">{container.memLimitMb}</span>
        <span className="ml-xs text-[11px] text-text-subtle">
          ({memPct.toFixed(0)}%)
        </span>
      </td>
      <td
        className={cn(
          'px-md py-[8px] text-right text-[13px] font-mono',
          hasRestarts ? 'text-warning' : 'text-text-subtle',
        )}
      >
        {container.restartCount}
      </td>
    </tr>
  );
}
