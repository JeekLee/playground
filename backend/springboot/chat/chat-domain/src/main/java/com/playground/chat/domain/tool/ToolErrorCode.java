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
    TIMEOUT(false),
    CIRCUIT_OPEN(true),
    MAX_DEPTH(true),
    UPSTREAM_4XX(false),
    UPSTREAM_5XX(false),
    SCHEMA_INVALID(false),
    INTERNAL(false);

    private final boolean terminal;

    ToolErrorCode(boolean terminal) {
        this.terminal = terminal;
    }

    /**
     * Whether this code aborts the turn's tool round-trip (ADR-17 §2): the LLM
     * gets no further round-trip — the orchestrator throws {@code
     * ToolCallTerminalException} to unwind. The two terminal codes are
     * {@link #CIRCUIT_OPEN} (operator cost-protection) and {@link #MAX_DEPTH}
     * (per-turn depth cap). Non-terminal codes are fed back to the LLM as a
     * synthetic error result so it can retry or apologize.
     */
    public boolean isTerminal() {
        return terminal;
    }
}
