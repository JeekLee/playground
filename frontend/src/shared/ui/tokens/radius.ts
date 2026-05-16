/**
 * Border-radius scale — design system §5.2.
 */

export const radius = {
  sm: '6px',
  md: '10px',
  lg: '14px',
  pill: '999px',
} as const;

export type RadiusToken = keyof typeof radius;

/**
 * Elevation scale — §5.3. No third level (per spec).
 */
export const shadow = {
  card: '0 4px 14px rgba(60,50,20,.05)',
  pop: '0 10px 30px rgba(60,50,20,.10)',
} as const;

export type ShadowToken = keyof typeof shadow;
