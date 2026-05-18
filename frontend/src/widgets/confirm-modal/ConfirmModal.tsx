'use client';

import { useEffect } from 'react';
import { Button } from '@/shared/ui/button';
import { cn } from '@/shared/lib/cn';

/**
 * ConfirmModal — destructive / reductive confirmation surface used by
 * Unpublish and Delete on `/docs/{id}` per design doc §"Unpublish modal"
 * and §"Delete modal":
 *  - backdrop `rgba(42,44,32,.30)`
 *  - card 480×auto, centered, `surface` + `border` + `radius.lg`, padding 32px
 *  - title + body + footer with `Cancel` (secondary) + action button
 *
 * The action button variant chooses between `secondary` (reductive, used
 * by Unpublish) and `danger` (destructive, used by Delete).
 *
 * `Esc` and backdrop click both dismiss. Server-side errors surface as
 * an inline `danger` banner above the footer.
 */

export interface ConfirmModalProps {
  open: boolean;
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel?: string;
  variant?: 'secondary' | 'danger';
  pending?: boolean;
  errorMessage?: string | null;
  onConfirm: () => void;
  onClose: () => void;
}

export function ConfirmModal({
  open,
  title,
  body,
  confirmLabel,
  cancelLabel = 'Cancel',
  variant = 'secondary',
  pending = false,
  errorMessage = null,
  onConfirm,
  onClose,
}: ConfirmModalProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !pending) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, pending, onClose]);

  if (!open) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-modal-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(42,44,32,0.30)]"
      onClick={() => !pending && onClose()}
    >
      <div
        className={cn(
          'w-[480px] max-w-[calc(100vw-32px)] flex flex-col gap-md rounded-lg border border-border bg-surface p-[32px] shadow-pop',
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="confirm-modal-title" className="text-h2 text-text">
          {title}
        </h2>
        <p className="text-body text-text-muted">{body}</p>
        {errorMessage && (
          <p className="rounded-md border border-danger bg-danger-soft px-sm py-xs text-small text-danger">
            {errorMessage}
          </p>
        )}
        <div className="flex justify-end gap-sm">
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            {cancelLabel}
          </Button>
          <Button variant={variant} onClick={onConfirm} disabled={pending}>
            {pending ? 'Working…' : confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
