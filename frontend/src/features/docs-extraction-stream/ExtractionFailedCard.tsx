import { cn } from '@/shared/lib/cn';

/**
 * Failure card rendered in place of the body region when extraction failed
 * (`extractionStatus === 'failed'`). Per M2-docs amendment 2026-05-22 §(2):
 * a `danger`-bordered card with the failure reason and a hint that
 * re-uploading is the path forward. The retry-by-reupload affordance is
 * advisory only — actual re-extraction wiring is out of scope for this
 * commit (the dispatch notes it as a deferred concern).
 *
 * The card lives at the same horizontal rhythm as the markdown body it
 * replaces, so the page reads as "this doc exists but couldn't be
 * extracted" rather than as a broken layout. The original blob is still
 * downloadable via the {@link DownloadOriginalButton} in the article
 * header — the user retains access to the source even when the parsed
 * body never materialized.
 */

const DEFAULT_REASON =
  'Extraction did not complete. The original file is still available — try uploading a different copy or splitting it into smaller files.';

export interface ExtractionFailedCardProps {
  reason: string | null | undefined;
  className?: string;
}

export function ExtractionFailedCard({ reason, className }: ExtractionFailedCardProps) {
  const message = reason && reason.trim().length > 0 ? reason : DEFAULT_REASON;
  return (
    <section
      role="alert"
      className={cn(
        'flex flex-col gap-md rounded-md border border-danger bg-danger-soft px-lg py-lg',
        className,
      )}
      aria-labelledby="extraction-failed-title"
    >
      <header className="flex items-center gap-sm">
        <span
          aria-hidden="true"
          className="inline-flex h-[28px] w-[28px] items-center justify-center rounded-pill bg-danger-soft text-[14px] font-semibold text-danger"
        >
          !
        </span>
        <h2
          id="extraction-failed-title"
          className="text-h3 text-text"
        >
          Could not extract this document
        </h2>
      </header>
      <p className="text-body text-text-muted">{message}</p>
      <p className="text-small text-text-subtle">
        The original file is preserved — use the{' '}
        <span className="font-medium text-text-muted">Download original</span> link in the
        header to recover it, then re-upload a corrected copy from <span className="font-mono">/docs/mine</span>.
      </p>
    </section>
  );
}
