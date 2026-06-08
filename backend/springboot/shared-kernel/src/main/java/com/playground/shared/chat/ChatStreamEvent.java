package com.playground.shared.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BC-agnostic SSE event grammar for streaming chat surfaces. Lives in
 * shared-kernel so any bounded context that streams an LLM turn — current
 * chat (M4), future tool-calling agents, second model line, etc. —
 * can emit the same wire grammar to a single frontend consumer.
 *
 * <h2>Grammar shape</h2>
 * <ul>
 *   <li>{@link Phase} — progress / status updates during a turn
 *       (retrieving, tool_call, tool_result, thinking, generating, …).
 *       {@code step} is the discriminator; {@code label} is a
 *       human-readable hint the UI can render verbatim; {@code data} is
 *       a BC-specific map (count, tool name + args, etc.).</li>
 *   <li>{@link Token} — one text delta emitted by the assistant
 *       stream.</li>
 *   <li>{@link Done} — terminal success. {@code citations} is a
 *       BC-specific payload (chat ships the cited document chunks;
 *       a future web-agent ships URLs; the wire format is whatever the
 *       BC's Jackson serializer produces).</li>
 *   <li>{@link Error} — terminal failure with a code + human-readable
 *       message + optional retry-after hint (RATE_LIMIT).</li>
 * </ul>
 *
 * <p>Wire envelope is identical across BCs: SSE event name = the record
 * type name lower-cased ({@code phase}, {@code token}, {@code done},
 * {@code error}); JSON payload = the record fields. The envelope mapping
 * (event name + field→key, including conditional key omissions) now lives
 * on each variant via {@link #toWire()}, producing a framework-neutral
 * {@link WireFrame}. The -api controller only wraps that {@code WireFrame}
 * into the transport {@code ServerSentEvent} — it owns no per-variant
 * mapping logic. BCs still control the domain payloads they place inside
 * the event ({@code data} / {@code citations} / {@code args} / {@code result}
 * are {@code Object} / {@code Map} fields the BC populates before
 * constructing the event); {@code toWire()} just lays out the wire keys.
 *
 * <h2>Pre-PR-B compat note</h2>
 * Today chat (M4) still emits the wire event {@code retrieval} as
 * a {@link Phase} with {@code step = "retrieval"} and
 * {@code data.citations} carrying the full retrieved set; PR B will
 * pull the citation list out of {@code retrieval} into {@link Done}
 * (only the actually-cited subset) and rename the wire event to
 * {@code phase}. This contract leaves room for both shapes.
 */
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.Phase, ChatStreamEvent.Token, ChatStreamEvent.Done, ChatStreamEvent.Error,
                ChatStreamEvent.ToolCall, ChatStreamEvent.ToolProgress, ChatStreamEvent.ToolResult,
                ChatStreamEvent.ToolError {

    /**
     * Produces this event's framework-neutral SSE {@link WireFrame} — the
     * {@code event:} name plus the JSON {@code data} map (with this variant's
     * conditional key omissions applied). Each variant owns its own wire shape
     * here, so the transport-side controller is a mechanical wrapper and a new
     * variant is compile-enforced to declare its wire mapping.
     */
    WireFrame toWire();

    /**
     * Progress / status update during the turn. {@code step} is the
     * machine-readable discriminator the frontend uses to pick a label /
     * icon; {@code label} is a fallback display string for steps the
     * frontend doesn't know about yet (forward-compat).
     */
    record Phase(String step, String label, Map<String, Object> data) implements ChatStreamEvent {
        public Phase {
            if (step == null || step.isBlank()) {
                throw new IllegalArgumentException("Phase.step must not be blank");
            }
        }

        @Override
        public WireFrame toWire() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("step", step);
            payload.put("label", label);
            if (data != null && !data.isEmpty()) {
                payload.put("data", data);
            }
            return new WireFrame("phase", payload);
        }
    }

    /** A single text delta from the assistant stream. */
    record Token(String delta) implements ChatStreamEvent {
        @Override
        public WireFrame toWire() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("delta", delta);
            return new WireFrame("token", payload);
        }
    }

    /**
     * Terminal success — assistant message persisted, stream closing.
     * {@code citations} is BC-specific: chat populates it with the
     * cited document chunks (PR B); other BCs may use a different shape
     * or leave it {@code null}.
     */
    record Done(String messageId, Integer tokensIn, Integer tokensOut, Object citations)
            implements ChatStreamEvent {
        @Override
        public WireFrame toWire() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", messageId);
            payload.put("tokensIn", tokensIn);
            payload.put("tokensOut", tokensOut);
            if (citations != null) {
                payload.put("citations", citations);
            }
            return new WireFrame("done", payload);
        }
    }

    /**
     * Tool-calling event — LLM has decided to invoke a registered tool.
     * Emitted immediately before the dispatcher issues the upstream
     * HTTP call per ADR-17 §3.1. Wire event name is {@code tool_call};
     * {@code id} is the opaque correlation id (Spring AI pass-through or
     * server-generated ULID per ADR-17 §3.2); {@code displayName} is a
     * short human-facing label for the in-flight tool card (tool-streaming
     * spec W2 — may be {@code null} for forward-compat); {@code args} is the
     * LLM-produced JSON arguments (carrier object — serialized by the
     * controller's Jackson mapper).
     */
    record ToolCall(String id, String name, String displayName, Object args) implements ChatStreamEvent {
        public ToolCall {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("ToolCall.id must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ToolCall.name must not be blank");
            }
        }

        @Override
        public WireFrame toWire() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("name", name);
            if (displayName != null) {
                payload.put("displayName", displayName);
            }
            payload.put("args", args);
            return new WireFrame("tool_call", payload);
        }
    }

    /**
     * Tool-calling event — a progress update emitted while a tool is
     * running (tool-streaming spec W2). Relayed from the dispatcher's
     * NDJSON {@code progress} lines onto the chat SSE stream as
     * {@code tool_progress}. {@code id} matches the originating
     * {@code tool_call.id}; {@code stage} is the machine stage key;
     * {@code label} is rendered verbatim by the FE; {@code stageIndex}
     * is 1-based; {@code attempt} is the retry attempt number, or
     * {@code null} on the first attempt.
     */
    record ToolProgress(String id, String name, String stage, String label,
            int stageIndex, int stageCount, Integer attempt) implements ChatStreamEvent {
        @Override
        public WireFrame toWire() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("name", name);
            payload.put("stage", stage);
            payload.put("label", label);
            payload.put("stageIndex", stageIndex);
            payload.put("stageCount", stageCount);
            if (attempt != null) {
                payload.put("attempt", attempt);
            }
            return new WireFrame("tool_progress", payload);
        }
    }

    /**
     * Tool-calling event — tool BC returned a successful result.
     * Emitted after the dispatch completes and the body is parsed,
     * but before the result is fed back to the LLM (ADR-17 §3.1).
     * Wire event name is {@code tool_result}. The {@code result} field
     * carries the (possibly-truncated) JSON body — see ADR-17 §4 for
     * the 16-KiB cap policy.
     */
    record ToolResult(String id, String name, Object result) implements ChatStreamEvent {
        public ToolResult {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("ToolResult.id must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ToolResult.name must not be blank");
            }
        }

        @Override
        public WireFrame toWire() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("name", name);
            payload.put("result", result);
            return new WireFrame("tool_result", payload);
        }
    }

    /**
     * Tool-calling event — tool dispatch failed.
     * Wire event name is {@code tool_error}. The {@code code} field
     * carries the verbatim {@code ToolErrorCode} enum name string
     * (one of {@code TIMEOUT}, {@code CIRCUIT_OPEN}, {@code MAX_DEPTH},
     * {@code UPSTREAM_4XX}, {@code UPSTREAM_5XX}, {@code SCHEMA_INVALID},
     * {@code INTERNAL}). Note this lives in shared-kernel so the
     * {@code code} is the bare string — the BC's mapper converts from
     * the {@code ToolErrorCode} enum at the controller boundary.
     *
     * <p>Per ADR-17 §3.1, {@code code = "CIRCUIT_OPEN"} and
     * {@code code = "MAX_DEPTH"} are <b>terminal</b> — no further
     * {@code done} / {@code error} event follows. All other codes are
     * non-terminal (the LLM may issue a follow-up turn).
     */
    record ToolError(String id, String name, String code, String message)
            implements ChatStreamEvent {
        public ToolError {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("ToolError.id must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ToolError.name must not be blank");
            }
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("ToolError.code must not be blank");
            }
        }

        @Override
        public WireFrame toWire() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("name", name);
            payload.put("code", code);
            payload.put("message", message);
            return new WireFrame("tool_error", payload);
        }
    }

    /**
     * Terminal failure. {@code code} values are intentionally a small,
     * BC-agnostic vocabulary so the frontend can render a consistent
     * banner per code regardless of which BC emitted the stream.
     */
    record Error(String code, String message, Long retryAfterSeconds) implements ChatStreamEvent {

        /** Upstream LLM / inference gateway 5xx after retries exhausted (or breaker open). */
        public static Error gatewayDown() {
            return new Error("GATEWAY_5XX", "AI service unavailable", 30L);
        }

        /** Client-initiated abort (Stop button, navigate-away). */
        public static Error aborted() {
            return new Error("ABORTED", "Stream aborted", null);
        }

        /** Catch-all server error — caller should pass the underlying cause. */
        public static Error internal(String message) {
            return new Error("INTERNAL", message == null ? "Internal error" : message, null);
        }

        @Override
        public WireFrame toWire() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("message", message);
            if (retryAfterSeconds != null) {
                payload.put("retryAfter", retryAfterSeconds);
            }
            return new WireFrame("error", payload);
        }
    }
}
