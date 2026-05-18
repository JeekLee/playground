package com.playground.ragchat.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragchat.application.dto.ChatStreamEvent;
import com.playground.ragchat.domain.enums.Visibility;
import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.DocumentId;
import com.playground.ragchat.domain.model.id.MessageId;
import com.playground.ragchat.domain.model.id.UserId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

/** Direct tests for the SSE event mapping in {@link ChatStreamController}. */
class ChatStreamControllerTest {

    @Test
    void toSse_retrievalCarriesEventNameAndCitations() {
        ChatStreamEvent.Retrieval evt = new ChatStreamEvent.Retrieval(List.of(
                new RetrievedChunk(1, DocumentId.of(UUID.randomUUID()), 3, "text",
                        "Doc", UserId.of(UUID.randomUUID()), Visibility.PUBLIC)));
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        assertThat(sse.event()).isEqualTo("retrieval");
        assertThat(sse.data()).isInstanceOf(Map.class);
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
                new ChatStreamEvent.Done(mid, 100, 200));
        assertThat(sse.event()).isEqualTo("done");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsKey("messageId");
        assertThat(data).containsEntry("tokensIn", 100);
        assertThat(data).containsEntry("tokensOut", 200);
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
