import { MetricsDashboardPage } from '@/views/metrics';

/**
 * `/metrics` — public observability dashboard.
 *
 * Per ADR-09 amendment in ADR-15 §G.2: this route is PUBLIC. The
 * gateway lets anonymous callers through without injecting `X-User-Id`
 * (only `/api/metrics/logs/**` is auth-gated, and the M5 P0 UI
 * doesn't consume that endpoint).
 *
 * The page lives inside the `(shell)` route group so it inherits the
 * sidebar + topbar chrome (per design context §3 — reused verbatim
 * from M1/M2/M4). The sidebar's `System status` row becomes active
 * here (per design context Frame 5 right variant).
 *
 * The dashboard itself is a client component because:
 *  - 15-second polling via `setInterval` (`useDashboardPoll`).
 *  - URL-as-state via `useSearchParams`.
 *  - Per-chart parallel `useTimeseries` hooks.
 * Per ADR-15 §8: Recharts is rendered client-side; the SSR skeleton
 * is statically rendered above before hydration.
 */
export const dynamic = 'force-dynamic';

export default function MetricsRoute() {
  return <MetricsDashboardPage />;
}

export const metadata = {
  title: "System status · JeekLee's playground",
  description:
    "A live look at the playground's stack — Spring Boot services, host metrics, JVM heap, spark-inference-gateway latency. Refreshed every 15s.",
};
