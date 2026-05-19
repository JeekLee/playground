package com.playground.ragchat.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragchat.domain.enums.Visibility;
import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.DocumentId;
import com.playground.ragchat.domain.model.id.MessageId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.shared.chat.ChatStreamEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Direct tests for the SSE event mapping in {@link ChatStreamController}.
 * Wire-format assertions guard the PR-A compat layer: the
 * shared-kernel grammar emits {@link ChatStreamEvent.Phase} for the
 * retrieval phase, and the mapper translates it back to the pre-PR-A
 * wire event name {@code retrieval} so the frontend SSE consumer
 * doesn't break.
 */
class ChatStreamControllerTest {

    @Test
    void toSse_retrievalPhase_compatMapsToRetrievalEventName() {
        List<RetrievedChunk> chunks = List.of(
                new RetrievedChunk(1, DocumentId.of(UUID.randomUUID()), 3, "text",
                        "Doc", UserId.of(UUID.randomUUID()), Visibility.PUBLIC));
        ChatStreamEvent.Phase evt = new ChatStreamEvent.Phase(
                "retrieval", "Retrieving chunks", Map.of("chunks", chunks));
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        assertThat(sse.event()).isEqualTo("retrieval");
        assertThat(sse.data()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsKey("citations");
    }

    @Test
    void toSse_tokenCarriesDelta() {
        ServerSentEvent<Object> sse = ChatStreamController.toSse(new ChatStreamEvent.Token("hello"));
        assertThat(sse.event()).isEqualTo("token");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsEntry("delta", "hello");
    }

    @Test
    void toSse_doneCarriesMessageIdAndCounts() {
        MessageId mid = MessageId.of(UUID.randomUUID());
        ServerSentEvent<Object> sse = ChatStreamController.toSse(
                new ChatStreamEvent.Done(mid.value().toString(), 100, 200, null));
        assertThat(sse.event()).isEqualTo("done");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsKey("messageId");
        assertThat(data).containsEntry("tokensIn", 100);
        assertThat(data).containsEntry("tokensOut", 200);
        // PR-A: null citations are dropped from the wire.
        assertThat(data).doesNotContainKey("citations");
    }

    @Test
    void toSse_errorCarriesCodeAndMessage() {
        ServerSentEvent<Object> sse = ChatStreamController.toSse(ChatStreamEvent.Error.gatewayDown());
        assertThat(sse.event()).isEqualTo("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsEntry("code", "GATEWAY_5XX");
        assertThat(data).containsKey("message");
        assertThat(data).containsKey("retryAfter");
    }
}
