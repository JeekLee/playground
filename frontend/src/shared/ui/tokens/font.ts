/**
 * Typography tokens — mirrors design system §4. The wire-level values are
 * pinned: Inter (variable) for UI/body, JetBrains Mono for code/kbd. Each
 * scale entry carries size, line-height, weight, and tracking exactly as
 * spec'd.
 */

export const fontFamily = {
  sans: ['var(--font-inter)', 'system-ui', '-apple-system', '"Segoe UI"', 'sans-serif'],
  mono: ['var(--font-mono)', 'ui-monospace', '"SF Mono"', 'Menlo', 'monospace'],
} as const;

/**
 * 7+1 scale from §4.2. Values are objects so consumers can wire each
 * (size, line-height, weight, tracking) into Tailwind's typography
 * utility-set without losing fidelity.
 */
export const fontScale = {
  display: { size: '40px', lineHeight: '1.1', weight: 700, tracking: '-0.025em' },
  h1: { size: '28px', lineHeight: '1.2', weight: 700, tracking: '-0.02em' },
  h2: { size: '20px', lineHeight: '1.3', weight: 600, tracking: '-0.01em' },
  h3: { size: '16px', lineHeight: '1.4', weight: 600, tracking: '0' },
  body: { size: '15px', lineHeight: '1.6', weight: 400, tracking: '0' },
  small: { size: '13px', lineHeight: '1.5', weight: 400, tracking: '0' },
  eyebrow: {
    size: '11px',
    lineHeight: '1.2',
    weight: 600,
    tracking: '0.14em',
    textTransform: 'uppercase' as const,
  },
  mono: { size: '13px', lineHeight: '1.5', weight: 400, tracking: '0' },
} as const;

export type FontScaleKey = keyof typeof fontScale;
