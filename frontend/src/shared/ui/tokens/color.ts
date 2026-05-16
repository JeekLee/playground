/**
 * Color tokens — mirrors `docs/superpowers/specs/2026-05-16-playground-design-system.md`
 * §3.1, §3.2, §3.3 verbatim. The dotted token names are flattened here
 * (e.g. `color.bg` → `bg`, `color.surface.soft` → `surfaceSoft`) so they
 * survive ESM exports and Tailwind config consumption. Zero hex literals
 * may appear outside this file (lint-enforced via no-restricted-syntax).
 */

export const color = {
  // §3.1 Surfaces / borders / text
  bg: '#FAF7EF',
  surface: '#FFFFFF',
  surfaceSoft: '#F4EFDF',
  border: '#E6E0CB',
  borderStrong: '#D6CFB3',
  khaki: '#C2B88A',
  text: '#2A2C20',
  textMuted: '#6F6A55',
  textSubtle: '#8B8670',

  // §3.2 Accent (single olive)
  accent: '#6E7A3A',
  accentHover: '#5C6730',
  accentSoft: '#E9E8D1',

  // §3.3 Semantic
  success: '#4F6B2E',
  successSoft: '#E5EBD9',
  warning: '#B58A2B',
  warningSoft: '#F4E8C7',
  danger: '#B14B3B',
  dangerSoft: '#F4E1DA',
  dangerHover: '#9A3F31',
  info: '#4A6B7A',
  infoSoft: '#DCE4E9',
} as const;

export type ColorToken = keyof typeof color;
