/**
 * Parse the M8 domain-code prefix off a `tool_error.message`.
 *
 * Per ADR-18 §6 ("Q-D — frontend M8-specific error code recognition"),
 * the M8 BC encodes its domain-specific error code as a
 *
 *     <DOMAIN_CODE>: <human-readable message>
 *
 * prefix grammar inside the M7-level `tool_error.message` field. The
 * M7 wire shape (`tool_error.{id, name, code, message}`) is NOT
 * extended — the M7 enum (`UPSTREAM_4XX`, `TIMEOUT`, …) stays the
 * sole wire-level classifier. The M8 code rides inside `message`.
 *
 * This module is the **single site** in the frontend that splits the
 * prefix off. Any UI surface that needs the M8 code (the
 * `MassingErrorCode` switch on the secondary action label, the
 * `code: <CODE> · <elapsed>s elapsed` line per design doc §2.5) calls
 * `parseM8ErrorPrefix(...)` and reads `m8Code`. Callers MUST NOT
 * re-implement the regex.
 *
 * Grammar (ADR-18 §6 table):
 *   - DOMAIN_CODE matches `[A-Z_]{1,40}` (uppercase + underscore, 1-40 chars)
 *   - separator is colon followed by ≥1 whitespace
 *   - the remainder is the human-readable message body
 *
 * If the prefix does NOT match (e.g. the dispatcher emitted an
 * M7-level error like `TIMEOUT` that pre-dates the M8 BC's
 * involvement, or a malformed-prefix BC), the helper returns
 * `{m8Code: null, message: <raw>}` and the UI falls back to a
 * generic `↻ Retry` secondary action (per ADR-18 §6 mapping table:
 * "fallback for TIMEOUT / SIDECAR_FAILED / SIDECAR_TIMEOUT /
 * INTERNAL / unknown → ↻ Retry").
 */

/**
 * Strict M8 prefix grammar. Anchored at both ends so a message that
 * happens to *contain* `BRIEF_EXTRACTION_FAILED:` mid-sentence (e.g.,
 * a log dump quoted back) does not falsely register as prefixed.
 *
 * The `\s+` (one-or-more whitespace after the colon) avoids matching
 * pathological "ALL_CAPS:0 elapsed" log lines.
 */
const M8_PREFIX_RE = /^([A-Z_]{1,40}):\s+(.+)$/s;

/**
 * Known M8 domain codes (P0 set per ADR-18 §7). Surfacing this constant
 * lets the consumer pick a typed secondary-action label without
 * stringly-typed comparisons against the parser output.
 *
 * The list is NOT validated against by `parseM8ErrorPrefix` — the
 * parser accepts ANY `[A-Z_]{1,40}` prefix, so a future M8.x code
 * (e.g. `BRIEF_PARSE_DEGRADED`) flows through unchanged and the
 * consumer's switch falls into its `default` arm with the generic
 * `↻ Retry` action.
 */
export const M8_ERROR_CODES = [
  'BRIEF_NOT_FOUND',
  'BRIEF_NOT_ACCESSIBLE',
  'BRIEF_NOT_READY',
  'BRIEF_EXTRACTION_FAILED',
  'BRIEF_FETCH_FAILED',
  'MASSING_ALGORITHM_FAILED',
  'SIDECAR_TIMEOUT',
  'SIDECAR_FAILED',
  'INTERNAL',
] as const;

export type M8ErrorCode = (typeof M8_ERROR_CODES)[number];

export interface ParsedToolErrorMessage {
  /** The extracted M8 domain code, or `null` if the prefix did not match. */
  m8Code: string | null;
  /** The human-readable message body, with the prefix stripped when present. */
  message: string;
}

/**
 * Split an M8 prefix off the M7 `tool_error.message` string.
 *
 * @example
 *   parseM8ErrorPrefix("BRIEF_EXTRACTION_FAILED: Could not extract room program from brief.")
 *   // → { m8Code: "BRIEF_EXTRACTION_FAILED", message: "Could not extract room program from brief." }
 *
 *   parseM8ErrorPrefix("Generation timed out")
 *   // → { m8Code: null, message: "Generation timed out" }
 *
 *   parseM8ErrorPrefix("BRIEF_EXTRACTION_FAILED:no-space")  // separator violated
 *   // → { m8Code: null, message: "BRIEF_EXTRACTION_FAILED:no-space" }
 */
export function parseM8ErrorPrefix(raw: string | null | undefined): ParsedToolErrorMessage {
  if (!raw) return { m8Code: null, message: '' };
  const trimmed = raw.trim();
  if (trimmed.length === 0) return { m8Code: null, message: '' };
  const m = M8_PREFIX_RE.exec(trimmed);
  if (!m) {
    return { m8Code: null, message: trimmed };
  }
  // capture groups are guaranteed by the regex when `m` is non-null
  const code = m[1]!;
  const body = m[2]!;
  return { m8Code: code, message: body };
}
