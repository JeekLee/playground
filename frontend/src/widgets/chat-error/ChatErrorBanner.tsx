'use client';

import { useEffect, useState } from 'react';
import { AlertTriangle, Clock, RotateCcw } from 'lucide-react';
import { cn } from '@/shared/lib/cn';

/**
 * ChatErrorBanner — anchored 8px above the composer per design doc §1.5.
 * Two variants per frames 54:443 + 54:509:
 *
 *  - `gateway-down` (503 GATEWAY_DOWN): danger.soft bg + danger border.
 *    Copy: "AI service is currently unavailable." / "The inference
 *    gateway is failing. Try again in a moment." + `↻ Retry last message`
 *    outline button.
 *  - `rate-limited` (429 RATE_LIMIT): warning.soft bg + warning border.
 *    Copy: "You've hit your hourly limit." / "Try again in N minutes."
 *    + countdown pill `⏱ mm:ss`. Composer disables; on countdown reaches
 *    0 the banner dismisses + composer re-enables.
 *
 * No close button on either banner — dismissal is by retry / cooldown
 * elapse per spec §7.5.
 */

export type ChatErrorVariant = 'gateway-down' | 'rate-limited';

export interface ChatErrorBannerProps {
  variant: ChatErrorVariant;
  /** RATE_LIMIT countdown — seconds remaining when this banner mounted. */
  retryAfterSeconds?: number;
  /** Fired when the 503 banner's Retry button is clicked. */
  onRetry?: () => void;
  /** Fired when the 429 countdown reaches 0. */
  onCooldownComplete?: () => void;
}

export function ChatErrorBanner({
  variant,
  retryAfterSeconds = 60 * 13, // 13 minutes default (matches mock copy)
  onRetry,
  onCooldownComplete,
}: ChatErrorBannerProps) {
  if (variant === 'gateway-down') {
    return <GatewayDownBanner onRetry={onRetry} />;
  }
  return (
    <RateLimitBanner
      retryAfterSeconds={retryAfterSeconds}
      onCooldownComplete={onCooldownComplete}
    />
  );
}

function GatewayDownBanner({ onRetry }: { onRetry?: () => void }) {
  return (
    <div
      role="alert"
      className={cn(
        'flex w-full max-w-[1112px] items-center gap-md rounded-md border border-danger bg-danger-soft px-md py-[10px]',
      )}
    >
      <AlertTriangle size={18} aria-hidden="true" className="shrink-0 text-danger" />
      <div className="flex flex-1 flex-col leading-tight">
        <p className="text-[14px] font-semibold text-danger">
          AI service is currently unavailable.
        </p>
        <p className="text-[12px] text-text-muted">
          The inference gateway is failing. Try again in a moment.
        </p>
      </div>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="inline-flex h-[32px] shrink-0 items-center gap-xs rounded-sm border border-danger bg-surface px-[12px] text-[13px] font-semibold text-danger transition-colors duration-[140ms] hover:bg-danger-soft focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-danger"
        >
          <RotateCcw size={13} aria-hidden="true" />
          <span>Retry last message</span>
        </button>
      )}
    </div>
  );
}

function RateLimitBanner({
  retryAfterSeconds,
  onCooldownComplete,
}: {
  retryAfterSeconds: number;
  onCooldownComplete?: () => void;
}) {
  const [remaining, setRemaining] = useState(Math.max(0, Math.floor(retryAfterSeconds)));

  useEffect(() => {
    setRemaining(Math.max(0, Math.floor(retryAfterSeconds)));
  }, [retryAfterSeconds]);

  useEffect(() => {
    if (remaining <= 0) {
      onCooldownComplete?.();
      return;
    }
    const id = window.setInterval(() => {
      setRemaining((prev) => {
        const next = Math.max(0, prev - 1);
        if (next <= 0) {
          onCooldownComplete?.();
        }
        return next;
      });
    }, 1000);
    return () => window.clearInterval(id);
  }, [remaining, onCooldownComplete]);

  const minutesLabel = Math.max(1, Math.ceil(remaining / 60));
  const mm = String(Math.floor(remaining / 60)).padStart(2, '0');
  const ss = String(remaining % 60).padStart(2, '0');

  return (
    <div
      role="alert"
      className={cn(
        'flex w-full max-w-[1112px] items-center gap-md rounded-md border border-warning bg-warning-soft px-md py-[10px]',
      )}
    >
      <AlertTriangle size={18} aria-hidden="true" className="shrink-0 text-warning" />
      <div className="flex flex-1 flex-col leading-tight">
        <p className="text-[14px] font-semibold text-warning">
          You&rsquo;ve hit your hourly limit.
        </p>
        <p className="text-[12px] text-text-muted">
          Try again in {minutesLabel} {minutesLabel === 1 ? 'minute' : 'minutes'}. Hourly cap is 60
          completions per user (cost ceiling).
        </p>
      </div>
      <div className="inline-flex h-[32px] shrink-0 items-center gap-xs rounded-sm border border-warning bg-warning-soft px-[12px] text-[13px] font-semibold text-warning">
        <Clock size={13} aria-hidden="true" />
        <span className="font-mono">
          {mm} : {ss}
        </span>
      </div>
    </div>
  );
}
