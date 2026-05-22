import type { Config } from 'tailwindcss';
import { color } from './src/shared/ui/tokens/color';
import { spacing } from './src/shared/ui/tokens/spacing';
import { radius, shadow } from './src/shared/ui/tokens/radius';
import { fontFamily, fontScale } from './src/shared/ui/tokens/font';

/**
 * Tailwind consumes the design tokens. No hex literals appear in this file
 * — every color flows from `src/shared/ui/tokens/`. Utility names follow
 * the design-system token names (e.g. `bg-bg`, `text-text-muted`,
 * `border-border-strong`, `shadow-card`).
 */
const config: Config = {
  content: ['./src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        bg: color.bg,
        surface: {
          DEFAULT: color.surface,
          soft: color.surfaceSoft,
        },
        border: {
          DEFAULT: color.border,
          strong: color.borderStrong,
        },
        khaki: color.khaki,
        text: {
          DEFAULT: color.text,
          muted: color.textMuted,
          subtle: color.textSubtle,
        },
        accent: {
          DEFAULT: color.accent,
          hover: color.accentHover,
          soft: color.accentSoft,
        },
        success: {
          DEFAULT: color.success,
          soft: color.successSoft,
        },
        warning: {
          DEFAULT: color.warning,
          soft: color.warningSoft,
        },
        danger: {
          DEFAULT: color.danger,
          soft: color.dangerSoft,
          hover: color.dangerHover,
        },
        info: {
          DEFAULT: color.info,
          soft: color.infoSoft,
        },
      },
      spacing: {
        xs: spacing.xs,
        sm: spacing.sm,
        md: spacing.md,
        lg: spacing.lg,
        xl: spacing.xl,
        '2xl': spacing['2xl'],
      },
      borderRadius: {
        sm: radius.sm,
        md: radius.md,
        lg: radius.lg,
        pill: radius.pill,
      },
      boxShadow: {
        card: shadow.card,
        pop: shadow.pop,
      },
      fontFamily: {
        sans: [...fontFamily.sans],
        mono: [...fontFamily.mono],
      },
      fontSize: {
        display: [
          fontScale.display.size,
          {
            lineHeight: fontScale.display.lineHeight,
            letterSpacing: fontScale.display.tracking,
            fontWeight: String(fontScale.display.weight),
          },
        ],
        h1: [
          fontScale.h1.size,
          {
            lineHeight: fontScale.h1.lineHeight,
            letterSpacing: fontScale.h1.tracking,
            fontWeight: String(fontScale.h1.weight),
          },
        ],
        h2: [
          fontScale.h2.size,
          {
            lineHeight: fontScale.h2.lineHeight,
            letterSpacing: fontScale.h2.tracking,
            fontWeight: String(fontScale.h2.weight),
          },
        ],
        h3: [
          fontScale.h3.size,
          {
            lineHeight: fontScale.h3.lineHeight,
            letterSpacing: fontScale.h3.tracking,
            fontWeight: String(fontScale.h3.weight),
          },
        ],
        body: [
          fontScale.body.size,
          {
            lineHeight: fontScale.body.lineHeight,
            letterSpacing: fontScale.body.tracking,
            fontWeight: String(fontScale.body.weight),
          },
        ],
        small: [
          fontScale.small.size,
          {
            lineHeight: fontScale.small.lineHeight,
            letterSpacing: fontScale.small.tracking,
            fontWeight: String(fontScale.small.weight),
          },
        ],
        eyebrow: [
          fontScale.eyebrow.size,
          {
            lineHeight: fontScale.eyebrow.lineHeight,
            letterSpacing: fontScale.eyebrow.tracking,
            fontWeight: String(fontScale.eyebrow.weight),
          },
        ],
        mono: [
          fontScale.mono.size,
          {
            lineHeight: fontScale.mono.lineHeight,
            letterSpacing: fontScale.mono.tracking,
            fontWeight: String(fontScale.mono.weight),
          },
        ],
      },
      transitionDuration: {
        DEFAULT: '140ms',
      },
      transitionTimingFunction: {
        DEFAULT: 'cubic-bezier(0.4, 0, 0.2, 1)',
      },
      keyframes: {
        // M4 §4.3: pulsing block cursor while a chat turn is streaming.
        // Opacity 0.3 ↔ 1.0 at ~1s cadence.
        'chat-cursor': {
          '0%, 100%': { opacity: '0.3' },
          '50%': { opacity: '1' },
        },
        // M4 §2.2 "Thinking…" placeholder — 3 staggered dots.
        'chat-dot': {
          '0%, 80%, 100%': { opacity: '0.35', transform: 'translateY(0)' },
          '40%': { opacity: '1', transform: 'translateY(-2px)' },
        },
        // M8 §2.2/§2.3 — tool_result card entrance. Soft 6px lift +
        // fade so the card materializing mid-stream doesn't snap.
        'tool-card-in': {
          '0%': { opacity: '0', transform: 'translateY(6px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        // M8 §2.3 in-flight skeleton spinner — slow 1.2s rotation,
        // calmer than a network-loading spinner so it reads as
        // "tool is thinking" rather than "page is loading".
        'tool-spinner': {
          '0%': { transform: 'rotate(0deg)' },
          '100%': { transform: 'rotate(360deg)' },
        },
      },
      animation: {
        'chat-cursor': 'chat-cursor 1.1s ease-in-out infinite',
        'chat-dot': 'chat-dot 1s ease-in-out infinite',
        'tool-card-in': 'tool-card-in 180ms cubic-bezier(0.4, 0, 0.2, 1) both',
        'tool-spinner': 'tool-spinner 1.2s linear infinite',
      },
    },
  },
  plugins: [],
};

export default config;
