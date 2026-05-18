import { color } from '@/shared/ui/tokens';

/**
 * BlockNote theme mapped from the playground design tokens (color +
 * fontFamily). Wired through `<BlockNoteView theme={playgroundTheme}>`
 * — without it BlockNote's default Mantine light theme uses pure white
 * + Inter-default-weights and the editor surface reads as a white box
 * pasted on the cream page (the bug user reported "하얀 블랭크 박스").
 *
 * Frame mapping in design doc M2-docs.md §"New document editor" /
 * §"Edit document": the editor surface uses page `bg` (cream) and
 * blends with the rest of the page. Slash menu / formatting toolbar /
 * tooltips live on `surface` (white) with `border` outline. The side
 * menu drag handle uses `textSubtle` for muted icon color.
 *
 * The CSS variables this maps to (per @blocknote/mantine's
 * applyBlockNoteCSSVariablesFromTheme):
 *   --bn-colors-editor-background, --bn-colors-editor-text,
 *   --bn-colors-menu-background, --bn-colors-menu-text,
 *   --bn-colors-hovered-background, ..., --bn-colors-border,
 *   --bn-colors-side-menu, --bn-font-family, etc.
 */
export const playgroundTheme = {
    colors: {
        editor: {
            text: color.text,
            background: color.bg,
        },
        menu: {
            text: color.text,
            background: color.surface,
        },
        tooltip: {
            text: color.bg,
            background: color.text,
        },
        hovered: {
            text: color.text,
            background: color.surfaceSoft,
        },
        selected: {
            text: color.accent,
            background: color.accentSoft,
        },
        disabled: {
            text: color.textSubtle,
            background: color.surfaceSoft,
        },
        shadow: 'rgba(42, 44, 32, 0.08)',
        border: color.border,
        sideMenu: color.textSubtle,
    },
    borderRadius: 6,
    // The page already loads Inter via the `var(--font-inter)` CSS variable
    // wired in `app/layout.tsx`. BlockNote's theme expects a string fontFamily
    // value, so we expand the stack inline here.
    fontFamily: 'var(--font-inter), system-ui, -apple-system, "Segoe UI", sans-serif',
};
