'use client';

import { CheckCircle2, AlertTriangle, OctagonAlert } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import type { ServiceStatus, SparkGatewaySummary } from '@/entities/metrics';

/**
 * SparkGatewayPanel — Models loaded card.
 *
 * 디자인 컨텍스트 §2.1은 원래 Latency P95 (365 × 236) + Models loaded (175 × 236)
 * 두 카드를 mixed-width row로 두기로 했음. Latency P95 카드는 보류 상태:
 * - PromQL `http_client_requests_seconds_bucket{target="spark-inference-gateway"}`
 *   메트릭이 chat-api의 actuator/prometheus에 emit되지 않음 (Spring AI
 *   WebClient + Micrometer observation 통합 미설정).
 * - 빈 차트 + 0 값을 표시하느니 차라리 카드 자체를 숨김. metric emit 정리
 *   되면 별도 PR로 복원.
 *
 * **Models loaded (175 × 236):** title → list of currently-loaded models
 * (from {@code sparkGateway.modelsLoaded}, dynamic — backend pulls from
 * {@code GET /v1/models}) → HOST + UPTIME footers.
 */

export interface SparkGatewayPanelProps {
  spark: SparkGatewaySummary | null;
  /** Uptime in seconds — pulled from the corresponding services row. */
  uptimeSec: number | null;
}

export function SparkGatewayPanel({ spark, uptimeSec }: SparkGatewayPanelProps) {
  const status: ServiceStatus = spark?.status ?? 'up';
  return (
    <div className="flex h-[236px] w-full flex-col gap-md rounded-md border border-border bg-surface px-md py-md">
      <span className="text-[12px] font-semibold text-text">Models loaded</span>
      <ul className="flex flex-col gap-xs text-[13px] font-medium text-text">
        {(spark?.modelsLoaded ?? ['—', '—']).map((model, i) => (
          <li
            key={`${model}-${i}`}
            className="inline-flex items-center gap-sm"
          >
            <ModelGlyph status={status} />
            <span className={cn(spark ? 'text-text' : 'text-text-subtle')}>
              {model}
            </span>
          </li>
        ))}
      </ul>
      <div className="mt-auto flex flex-col gap-[6px]">
        <span className="text-eyebrow text-text-muted">Host</span>
        <span className="text-[11px] text-text-muted">
          {spark?.url ?? 'loading…'}
        </span>
        <span className="text-eyebrow text-text-muted">Uptime</span>
        <span className={cn('text-[14px] font-semibold', spark ? 'text-text' : 'text-text-subtle')}>
          {uptimeSec !== null ? formatUptime(uptimeSec) : '—'}
        </span>
      </div>
    </div>
  );
}

function ModelGlyph({ status }: { status: ServiceStatus }) {
  if (status === 'up') {
    return <CheckCircle2 size={13} aria-hidden="true" className="text-success" />;
  }
  if (status === 'degraded') {
    return <AlertTriangle size={13} aria-hidden="true" className="text-warning" />;
  }
  return <OctagonAlert size={13} aria-hidden="true" className="text-danger" />;
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
