package com.playground.chat.domain.tool;

/**
 * Enumeration of tool-dispatch failure classifications per ADR-17 §2 + PRD Story 5.
 *
 * <p>The 7 values are the closed P0 set — frontend renders user-facing copy from
 * these values, so adding / removing / renaming requires an ADR amendment.
 *
 * <p>Wire shape: the enum name (e.g., {@code "TIMEOUT"}) is the verbatim value
 * carried by the {@code tool_error.code} SSE payload (ADR-17 §3 + ADR-14 §A14.3).
 *
 * <ul>
 *   <li>{@link #TIMEOUT} — WebClient call to tool BC exceeded descriptor's timeout.</li>
 *   <li>{@link #CIRCUIT_OPEN} — per-tool Resilience4j circuit breaker is OPEN;
 *       the upstream HTTP call was skipped (operator cost-protection).
 *       <b>Terminal</b> — no further LLM round-trip.</li>
 *   <li>{@link #MAX_DEPTH} — per-turn depth cap exceeded.
 *       <b>Terminal</b> — no further LLM round-trip.</li>
 *   <li>{@link #UPSTREAM_4XX} — tool BC returned 4xx (typically LLM-produced
 *       args violated the descriptor's parameter schema). NOT counted as
 *       breaker failure (ADR-14 §4 invariant).</li>
 *   <li>{@link #UPSTREAM_5XX} — tool BC returned 5xx (internal failure).
 *       Counted as breaker failure.</li>
 *   <li>{@link #SCHEMA_INVALID} — LLM-produced args failed JSON-Schema
 *       validation before dispatch.</li>
 *   <li>{@link #INTERNAL} — dispatcher internal failure (deserialization,
 *       connect refused, DNS, etc.).</li>
 * </ul>
 */
public enum ToolErrorCode {
    TIMEOUT,
    CIRCUIT_OPEN,
    MAX_DEPTH,
    UPSTREAM_4XX,
    UPSTREAM_5XX,
    SCHEMA_INVALID,
    INTERNAL
}
