/**
 * M8 chat tool-card feature module.
 *
 * Renders the per-turn `tool_result` / `tool_error` cards (design doc
 * §2.2–§2.5) that materialize below the assistant message when the
 * LLM dispatches a tool. Today only `generate_massing` is registered
 * in the M7 `ToolCatalog`; future tool BCs (M9+) fork inside
 * `ToolCardList.tsx`.
 *
 * Public surface:
 *   - `ToolCardList`        — the entry point — call from
 *                             `ChatPage` / `ChatMessage` with the
 *                             turn's `ToolCardState[]`.
 *   - `MassingResultCard`   — the §2.3/§2.4 happy-path card.
 *   - `MassingErrorCard`    — the §2.5 warning-palette card.
 *   - `ToolResultCard`      — the generic primitive (§2.2). Exported
 *                             so future tool BCs can compose their
 *                             own variant.
 *   - `parseM8ErrorPrefix`  — the M8 domain-code prefix parser. SINGLE
 *                             SITE per ADR-18 §6 ("Do not parse the
 *                             M8 error prefix anywhere except in
 *                             parseM8ErrorPrefix.ts" — dispatch
 *                             contract).
 */
export { ToolCardList } from './ToolCardList';
export type { ToolCardListProps } from './ToolCardList';
export { MassingResultCard } from './MassingResultCard';
export type { MassingResultCardProps } from './MassingResultCard';
export { MassingErrorCard } from './MassingErrorCard';
export type { MassingErrorCardProps } from './MassingErrorCard';
export { ToolResultCard } from './ToolResultCard';
export type { ToolResultCardProps } from './ToolResultCard';
export {
  parseM8ErrorPrefix,
  M8_ERROR_CODES,
} from './parseM8ErrorPrefix';
export type { ParsedToolErrorMessage, M8ErrorCode } from './parseM8ErrorPrefix';
