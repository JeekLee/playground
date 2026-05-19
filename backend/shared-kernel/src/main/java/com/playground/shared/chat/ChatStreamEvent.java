package com.playground.shared.chat;

import java.util.Map;

/**
 * BC-agnostic SSE event grammar for streaming chat surfaces. Lives in
 * shared-kernel so any bounded context that streams an LLM turn — current
 * rag-chat (M4), future tool-calling agents, second model line, etc. —
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
 *       BC-specific payload (rag-chat ships the cited document chunks;
 *       a future web-agent ships URLs; the wire format is whatever the
 *       BC's Jackson serializer produces).</li>
 *   <li>{@link Error} — terminal failure with a code + human-readable
 *       message + optional retry-after hint (RATE_LIMIT).</li>
 * </ul>
 *
 * <p>Wire envelope is identical across BCs: SSE event name = the record
 * type name lower-cased ({@code phase}, {@code token}, {@code done},
 * {@code error}); JSON payload = the record fields. Each chat BC owns
 * its own controller-side {@code toSse} mapper so it can decorate
 * {@code data} / {@code citations} with its domain shapes; the mapper
 * is mechanical because the envelope is fixed here.
 *
 * <h2>Pre-PR-B compat note</h2>
 * Today rag-chat (M4) still emits the wire event {@code retrieval} as
 * a {@link Phase} with {@code step = "retrieval"} and
 * {@code data.citations} carrying the full retrieved set; PR B will
 * pull the citation list out of {@code retrieval} into {@link Done}
 * (only the actually-cited subset) and rename the wire event to
 * {@code phase}. This contract leaves room for both shapes.
 */
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.Phase, ChatStreamEvent.Token, ChatStreamEvent.Done, ChatStreamEvent.Error {

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
    }

    /** A single text delta from the assistant stream. */
    record Token(String delta) implements ChatStreamEvent {}

    /**
     * Terminal success — assistant message persisted, stream closing.
     * {@code citations} is BC-specific: rag-chat populates it with the
     * cited document chunks (PR B); other BCs may use a different shape
     * or leave it {@code null}.
     */
    record Done(String messageId, Integer tokensIn, Integer tokensOut, Object citations)
            implements ChatStreamEvent {}

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
    }
}
