/**
 * Spacing scale — design system §5.1. T-shirt, multiples of 4.
 */

export const spacing = {
  xs: '4px',
  sm: '8px',
  md: '16px',
  lg: '24px',
  xl: '40px',
  '2xl': '64px',
} as const;

export type SpacingToken = keyof typeof spacing;
