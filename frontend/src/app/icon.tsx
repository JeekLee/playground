import { ImageResponse } from 'next/og';
import { color } from '@/shared/ui/tokens/color';

// File-based icon convention (Next 14 App Router) — Next picks this up
// automatically and serves it at `/icon` with the correct cache headers,
// so we don't need a `public/favicon.ico` or an `icons.icon` metadata
// entry. Mirrors the §2.2 brand glyph (accent square, white `J`,
// proportional corner radius) so the tab icon reads as the same mark as
// the sidebar wordmark.

export const size = { width: 32, height: 32 };
export const contentType = 'image/png';

export default function Icon() {
  return new ImageResponse(
    (
      <div
        style={{
          width: '100%',
          height: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: color.accent,
          color: color.surface,
          // Brand glyph uses 7px radius on a 26px box (≈27%); preserve
          // the ratio at 32px → ~9px.
          borderRadius: 9,
          fontSize: 22,
          fontWeight: 700,
          lineHeight: 1,
          letterSpacing: '-0.02em',
        }}
      >
        J
      </div>
    ),
    size,
  );
}
