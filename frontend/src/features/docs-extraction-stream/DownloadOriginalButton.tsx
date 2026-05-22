import { Download } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import type { MimeType } from '@/entities/document';

/**
 * Download-original pill rendered next to the URL pill on `/docs/{id}` when
 * the document has a source blob in MinIO. Per M2-docs amendment
 * 2026-05-22 §(3): `surface` bg + `border` 1px + `radius.pill` 999,
 * padding `8px 16px`, label `↓ Original .pdf` (or `.md`) in
 * 11/500/`text.muted`.
 *
 * The component renders as a plain `<a href download>` rather than a
 * `<button>` with fetch+blob juggling — the server emits
 * `Content-Disposition: attachment; filename="source.pdf"` so the browser
 * handles the file save natively. The same-origin session cookie rides
 * along, so private docs gated on author ownership work without any
 * client-side auth ceremony.
 *
 * The button stays visible across every extraction state — including
 * `failed` — so the user always has a path back to the original bytes
 * even when the parsed body never materialized.
 */

export interface DownloadOriginalButtonProps {
  docId: string;
  mimeType?: MimeType;
  className?: string;
}

function extensionFor(mimeType: MimeType | undefined): string {
  if (mimeType === 'application/pdf') return '.pdf';
  return '.md';
}

export function DownloadOriginalButton({
  docId,
  mimeType,
  className,
}: DownloadOriginalButtonProps) {
  const ext = extensionFor(mimeType);
  return (
    <a
      href={`/api/docs/${encodeURIComponent(docId)}/original`}
      // `download` without a value lets the server-supplied
      // `Content-Disposition` filename win — the M6.1 backend slugifies the
      // doc title, which is what we want users to save as.
      download=""
      className={cn(
        'inline-flex items-center gap-xs rounded-pill border border-border bg-surface px-md py-[6px]',
        'text-[11px] font-medium leading-none text-text-muted',
        'transition-colors duration-[140ms] hover:bg-surface-soft hover:text-text',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
        className,
      )}
      aria-label={`Download original ${ext} file`}
    >
      <Download size={12} aria-hidden="true" />
      <span>Original {ext}</span>
    </a>
  );
}
