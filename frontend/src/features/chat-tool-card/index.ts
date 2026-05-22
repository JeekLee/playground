/**
 * M8 chat tool-card feature module.
 *
 * Renders the per-turn `tool_result` / `tool_error` cards (design doc
 * §2.2–§2.5) that materialize below the assistant message when the
 * LLM dispatches a tool. Today only `generate_massing` is registered
 * in the M7 `ToolCatalog`; future tool BCs (M9+) fork inside
 * `ToolCardList.tsx`.
 *
 * Public surface (this commit — types + parser only; the visual
 * components land in the next commit):
 *   - `parseM8ErrorPrefix`  — the M8 domain-code prefix parser. SINGLE
 *                             SITE per ADR-18 §6 ("Do not parse the
 *                             M8 error prefix anywhere except in
 *                             parseM8ErrorPrefix.ts" — dispatch
 *                             contract).
 */
export {
  parseM8ErrorPrefix,
  M8_ERROR_CODES,
} from './parseM8ErrorPrefix';
export type { ParsedToolErrorMessage, M8ErrorCode } from './parseM8ErrorPrefix';
