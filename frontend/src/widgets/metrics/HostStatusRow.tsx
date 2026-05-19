'use client';

import { LineChart, Line, ResponsiveContainer } from 'recharts';
import { cn } from '@/shared/lib/cn';
import { color } from '@/shared/ui/tokens';
import { useTimeseries } from '@/features/metrics';
import type { HostSummary, RangePreset } from '@/entities/metrics';

/**
 * HostStatusRow — 4 cards (CPU, MEMORY, DISK, LOAD AVG) per design
 * context §2.1 + §4.7.
 *
 * Each card: 270 × 96, `surface` bg + `border` + `radius.md`.
 * - CPU: eyebrow → big number → tiny sparkline (170 × 18, host-cpu
 *   timeseries via Recharts `<Line type="monotone">`).
 * - MEMORY: eyebrow → big number (`12.4 / 64 GB`) → progress bar.
 * - DISK: eyebrow → big number (`42% · 420 GB`) → progress bar.
 * - LOAD AVG: eyebrow → big number (`1.2 0.8 0.6`) → sub-label.
 *
 * Skeleton state: when `host` is null all values render `—` in
 * `text.subtle` per Frame 3.
 */

export interface HostStatusRowProps {
  host: HostSummary | null;
  range: RangePreset;
  pollKey: number | null;
}

export function HostStatusRow({ host, range, pollKey }: HostStatusRowProps) {
  return (
    <section aria-labelledby="metrics-host" className="flex flex-col gap-sm">
      <h2 id="metrics-host" className="text-eyebrow text-text-muted">
        Host
      </h2>
      <div className="grid grid-cols-1 gap-[12px] md:grid-cols-2 lg:grid-cols-4">
        <CpuCard host={host} range={range} pollKey={pollKey} />
        <MemoryCard host={host} />
        <DiskCard host={host} />
        <LoadAvgCard host={host} />
      </div>
    </section>
  );
}

function CardShell({
  eyebrow,
  children,
}: {
  eyebrow: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex h-[96px] flex-col gap-xs rounded-md border border-border bg-surface px-md py-[10px]">
      <span className="text-eyebrow text-text-muted">{eyebrow}</span>
      {children}
    </div>
  );
}

function BigNumber({
  value,
  loading,
}: {
  value: string;
  loading: boolean;
}) {
  return (
    <span
      className={cn(
        'text-h2',
        loading ? 'text-text-subtle' : 'text-text',
      )}
    >
      {value}
    </span>
  );
}

function CpuCard({
  host,
  range,
  pollKey,
}: {
  host: HostSummary | null;
  range: RangePreset;
  pollKey: number | null;
}) {
  const series = useTimeseries('host-cpu', range, {
    pollKey,
    enabled: pollKey !== null,
  });
  const points = series.data?.series[0]?.points ?? [];
  const data = points.map(([t, v]) => ({ t, v }));

  return (
    <CardShell eyebrow="CPU">
      <div className="flex items-end justify-between">
        <BigNumber
          value={host ? `${host.cpuPct.toFixed(1)}%` : '—'}
          loading={!host}
        />
        <span className="text-[11px] text-text-muted">last {range}</span>
      </div>
      <div className="h-[20px] w-full rounded-sm bg-surface-soft">
        {data.length > 0 ? (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart
              data={data}
              margin={{ top: 2, right: 2, bottom: 2, left: 2 }}
            >
              <Line
                type="monotone"
                dataKey="v"
                stroke={color.accent}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        ) : null}
      </div>
    </CardShell>
  );
}

function MemoryCard({ host }: { host: HostSummary | null }) {
  const usedPct = host ? Math.min(100, (host.memUsedGb / host.memTotalGb) * 100) : 0;
  return (
    <CardShell eyebrow="Memory">
      <BigNumber
        value={
          host
            ? `${host.memUsedGb.toFixed(1)} / ${host.memTotalGb.toFixed(1)} GB`
            : '—'
        }
        loading={!host}
      />
      <ProgressBar pct={usedPct} loading={!host} />
    </CardShell>
  );
}

function DiskCard({ host }: { host: HostSummary | null }) {
  const usedPct = host ? Math.min(100, host.diskUsedPct) : 0;
  const value = host
    ? Number.isFinite(host.diskUsedGb)
      ? `${host.diskUsedPct.toFixed(1)}% · ${host.diskUsedGb.toFixed(1)} GB`
      : `${host.diskUsedPct.toFixed(1)}%`
    : '—';
  return (
    <CardShell eyebrow="Disk">
      <BigNumber value={value} loading={!host} />
      <ProgressBar pct={usedPct} loading={!host} />
    </CardShell>
  );
}

function LoadAvgCard({ host }: { host: HostSummary | null }) {
  const value = host
    ? host.loadAvg
        .map((n) => n.toFixed(1))
        .join('  ')
    : '—';
  return (
    <CardShell eyebrow="Load avg">
      <BigNumber value={value} loading={!host} />
      <span className="text-[11px] text-text-muted">1m · 5m · 15m</span>
    </CardShell>
  );
}

function ProgressBar({ pct, loading }: { pct: number; loading: boolean }) {
  return (
    <div className="h-[10px] w-full rounded-sm bg-surface-soft">
      {!loading ? (
        <div
          className="h-full rounded-sm bg-accent transition-[width] duration-[400ms]"
          style={{ width: `${pct}%` }}
          aria-valuenow={pct}
          aria-valuemin={0}
          aria-valuemax={100}
          role="progressbar"
        />
      ) : null}
    </div>
  );
}
