/**
 * A flat `G` mark in white, rendered as an inline SVG so the sign-in
 * button reads as Google's call to action without depending on an
 * external asset bundle. Color is inherited so the same glyph works
 * inside a primary (white text) and a future ghost (accent text)
 * button without modification.
 */
export function GoogleGlyph() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 14 14"
      role="presentation"
      aria-hidden="true"
      fill="none"
    >
      <path
        d="M11.83 7.13c0-.42-.04-.83-.11-1.22H7v2.32h2.71a2.31 2.31 0 0 1-1 1.52v1.25h1.62c.95-.87 1.5-2.15 1.5-3.87Z"
        fill="currentColor"
      />
      <path
        d="M7 12c1.35 0 2.48-.45 3.3-1.2L8.71 9.55a2.84 2.84 0 0 1-4.22-1.49H2.83v1.29A4.99 4.99 0 0 0 7 12Z"
        fill="currentColor"
        opacity=".75"
      />
      <path
        d="M4.49 8.06A3 3 0 0 1 4.49 5.94V4.65H2.83a5 5 0 0 0 0 4.7l1.66-1.29Z"
        fill="currentColor"
        opacity=".55"
      />
      <path
        d="M7 4.32a2.7 2.7 0 0 1 1.92.75l1.43-1.43A4.79 4.79 0 0 0 7 2.32a4.99 4.99 0 0 0-4.17 2.33L4.49 5.94A2.99 2.99 0 0 1 7 4.32Z"
        fill="currentColor"
        opacity=".4"
      />
    </svg>
  );
}
