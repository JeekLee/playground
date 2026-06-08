package com.playground.chat.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.chat.application.dto.CitationDto;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.shared.chat.ChatStreamEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Wire-grammar tests for {@link ChatStreamController#toSse}. PR B revision:
 * <ul>
 *   <li>The legacy {@code event: retrieval} is gone — progress events
 *       now ride a single {@code event: phase} type discriminated by
 *       {@code step}.</li>
 *   <li>{@link CitationDto} cards arrive on the terminal
 *       {@code event: done} (cited subset only).</li>
 * </ul>
 */
class ChatStreamControllerTest {

    @Test
    void toSse_phaseEventCarriesStepLabelAndData() {
        ChatStreamEvent.Phase evt = new ChatStreamEvent.Phase(
                "retrieval", "참고 문서 확인 중", Map.of("count", 6));
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        assertThat(sse.event()).isEqualTo("phase");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsEntry("step", "retrieval");
        assertThat(data).containsEntry("label", "참고 문서 확인 중");
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) data.get("data");
        assertThat(inner).containsEntry("count", 6);
    }

    @Test
    void toSse_phaseEventOmitsDataWhenEmpty() {
        ChatStreamEvent.Phase evt = new ChatStreamEvent.Phase("thinking", "사고 중", Map.of());
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).doesNotContainKey("data");
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
    void toSse_doneCarriesCitedSubset() {
        MessageId mid = MessageId.of(UUID.randomUUID());
        List<CitationDto> cited = List.of(
                new CitationDto(1, "document", "Doc title", "excerpt…", "https://o/docs/a"),
                new CitationDto(2, "document", "Another", "excerpt…", "https://o/docs/b"));
        ServerSentEvent<Object> sse = ChatStreamController.toSse(
                new ChatStreamEvent.Done(mid.value().toString(), 100, 200, cited));
        assertThat(sse.event()).isEqualTo("done");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsKey("messageId");
        assertThat(data).containsEntry("tokensIn", 100);
        assertThat(data).containsEntry("tokensOut", 200);
        assertThat(data).extractingByKey("citations").asList().hasSize(2);
    }

    @Test
    void toSse_doneOmitsCitationsWhenNull() {
        ServerSentEvent<Object> sse = ChatStreamController.toSse(
                new ChatStreamEvent.Done(UUID.randomUUID().toString(), 10, 20, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).doesNotContainKey("citations");
    }

    @Test
    void toSse_toolCallCarriesIdNameArgs() {
        ChatStreamEvent.ToolCall evt = new ChatStreamEvent.ToolCall(
                "call_abc123", "generate_massing", "매싱 모델",
                Map.of("briefDocId", "doc-1", "siteWidth", 30.0));
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        assertThat(sse.event()).isEqualTo("tool_call");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsEntry("id", "call_abc123");
        assertThat(data).containsEntry("name", "generate_massing");
        assertThat(data).containsKey("args");
    }

    @Test
    void toSse_toolResultCarriesIdNameResult() {
        ChatStreamEvent.ToolResult evt = new ChatStreamEvent.ToolResult(
                "call_abc123", "generate_massing",
                Map.of("fileUrl", "/api/arch/outputs/x", "summary", "12 rooms"));
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        assertThat(sse.event()).isEqualTo("tool_result");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsEntry("id", "call_abc123");
        assertThat(data).containsEntry("name", "generate_massing");
        assertThat(data).containsKey("result");
    }

    @Test
    void toSse_toolErrorCarriesIdNameCodeMessage() {
        ChatStreamEvent.ToolError evt = new ChatStreamEvent.ToolError(
                "call_abc123", "generate_massing", "TIMEOUT",
                "Tool 'generate_massing' did not respond within 30s");
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        assertThat(sse.event()).isEqualTo("tool_error");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsEntry("id", "call_abc123");
        assertThat(data).containsEntry("name", "generate_massing");
        assertThat(data).containsEntry("code", "TIMEOUT");
        assertThat(data).containsEntry("message",
                "Tool 'generate_massing' did not respond within 30s");
    }

    @Test
    void toSse_mapsToolProgress() {
        var evt = new ChatStreamEvent.ToolProgress(
                "t1", "generate_massing", "extract", "공간 프로그램 추출 중", 3, 10, 2);
        ServerSentEvent<Object> sse = ChatStreamController.toSse(evt);
        assertThat(sse.event()).isEqualTo("tool_progress");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) sse.data();
        assertThat(data).containsEntry("stage", "extract")
                .containsEntry("label", "공간 프로그램 추출 중")
                .containsEntry("stageIndex", 3)
                .containsEntry("stageCount", 10)
                .containsEntry("attempt", 2);
    }

    @Test
    void toSse_toolProgressOmitsNullAttempt() {
        var evt = new ChatStreamEvent.ToolProgress(
                "t1", "generate_massing", "compute", "매싱 계산", 7, 10, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>)
                ChatStreamController.toSse(evt).data();
        assertThat(data).doesNotContainKey("attempt");
    }

    @Test
    void toSse_toolCallCarriesDisplayName() {
        var evt = new ChatStreamEvent.ToolCall("t1", "generate_massing", "매싱 모델",
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>)
                ChatStreamController.toSse(evt).data();
        assertThat(data).containsEntry("displayName", "매싱 모델");
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
