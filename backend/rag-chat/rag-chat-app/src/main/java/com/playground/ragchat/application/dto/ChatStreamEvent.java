package com.playground.ragchat.application.dto;

import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.MessageId;
import java.util.List;

/**
 * Sealed event hierarchy emitted by {@code ChatTurnService.stream(...)}. The
 * controller maps each into the SSE event grammar from ADR-14 §C / spec §5.2:
 * <ul>
 *   <li>{@link Retrieval} → {@code event: retrieval}</li>
 *   <li>{@link Token} → {@code event: token}</li>
 *   <li>{@link Done} → {@code event: done}</li>
 *   <li>{@link Error} → {@code event: error}</li>
 * </ul>
 */
public sealed interface ChatStreamEvent permits ChatStreamEvent.Retrieval,
        ChatStreamEvent.Token,
        ChatStreamEvent.Done,
        ChatStreamEvent.Error {

    record Retrieval(List<RetrievedChunk> chunks) implements ChatStreamEvent {}

    record Token(String delta) implements ChatStreamEvent {}

    record Done(MessageId messageId, Integer tokensIn, Integer tokensOut) implements ChatStreamEvent {}

    record Error(String code, String message, Long retryAfterSeconds) implements ChatStreamEvent {

        /** Mid-stream codes per spec §5.2 / ADR-14 §C row "200 + SSE error event". */
        public static Error gatewayDown() {
            return new Error("GATEWAY_5XX", "AI service unavailable", 30L);
        }

        public static Error aborted() {
            return new Error("ABORTED", "Stream aborted", null);
        }

        public static Error internal(String message) {
            return new Error("INTERNAL", message == null ? "Internal error" : message, null);
        }
    }
}
