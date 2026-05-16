import { Inter, JetBrains_Mono } from 'next/font/google';

/**
 * Inter (variable) per design system §4.1. Loaded as a CSS variable so the
 * `tailwind.config.ts` `fontFamily.sans` chain can resolve `var(--font-inter)`.
 */
export const inter = Inter({
  subsets: ['latin'],
  variable: '--font-inter',
  display: 'swap',
  weight: ['400', '500', '600', '700'],
});

/**
 * JetBrains Mono per §4.1 — used for kbd glyphs (⌘K) and any code/numeric
 * surfaces in later milestones.
 */
export const mono = JetBrains_Mono({
  subsets: ['latin'],
  variable: '--font-mono',
  display: 'swap',
  weight: ['400'],
});
