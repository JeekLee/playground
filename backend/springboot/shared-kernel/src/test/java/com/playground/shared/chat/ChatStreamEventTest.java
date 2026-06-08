package com.playground.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Wire-contract tests for {@link ChatStreamEvent#toWire()}. Each variant owns
 * its own {@link WireFrame} (event name + data keys + conditional omissions);
 * this is the canonical home of the SSE wire contract. The byte-identical
 * controller-side projection is separately covered by
 * {@code ChatStreamControllerTest} in chat-api.
 */
class ChatStreamEventTest {

    @Test
    void phaseCarriesStepLabelAndData() {
        WireFrame wf = new ChatStreamEvent.Phase("retrieval", "참고 문서 확인 중", Map.of("count", 6)).toWire();
        assertThat(wf.event()).isEqualTo("phase");
        assertThat(wf.data()).containsEntry("step", "retrieval");
        assertThat(wf.data()).containsEntry("label", "참고 문서 확인 중");
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) wf.data().get("data");
        assertThat(inner).containsEntry("count", 6);
    }

    @Test
    void phaseOmitsDataWhenEmpty() {
        WireFrame wf = new ChatStreamEvent.Phase("thinking", "사고 중", Map.of()).toWire();
        assertThat(wf.event()).isEqualTo("phase");
        assertThat(wf.data()).doesNotContainKey("data");
    }

    @Test
    void phaseOmitsDataWhenNull() {
        WireFrame wf = new ChatStreamEvent.Phase("thinking", "사고 중", null).toWire();
        assertThat(wf.data()).doesNotContainKey("data");
    }

    @Test
    void tokenCarriesDelta() {
        WireFrame wf = new ChatStreamEvent.Token("hello").toWire();
        assertThat(wf.event()).isEqualTo("token");
        assertThat(wf.data()).containsEntry("delta", "hello");
    }

    @Test
    void doneCarriesIdsAndCitations() {
        List<String> cited = List.of("c1", "c2");
        WireFrame wf = new ChatStreamEvent.Done("m1", 100, 200, cited).toWire();
        assertThat(wf.event()).isEqualTo("done");
        assertThat(wf.data()).containsEntry("messageId", "m1");
        assertThat(wf.data()).containsEntry("tokensIn", 100);
        assertThat(wf.data()).containsEntry("tokensOut", 200);
        assertThat(wf.data()).extractingByKey("citations").asList().hasSize(2);
    }

    @Test
    void doneOmitsCitationsWhenNull() {
        WireFrame wf = new ChatStreamEvent.Done("m1", 10, 20, null).toWire();
        assertThat(wf.event()).isEqualTo("done");
        assertThat(wf.data()).doesNotContainKey("citations");
        assertThat(wf.data()).containsEntry("messageId", "m1");
    }

    @Test
    void errorCarriesCodeMessageAndRetryAfter() {
        WireFrame wf = ChatStreamEvent.Error.gatewayDown().toWire();
        assertThat(wf.event()).isEqualTo("error");
        assertThat(wf.data()).containsEntry("code", "GATEWAY_5XX");
        assertThat(wf.data()).containsKey("message");
        assertThat(wf.data()).containsEntry("retryAfter", 30L);
    }

    @Test
    void errorOmitsRetryAfterWhenNull() {
        WireFrame wf = ChatStreamEvent.Error.aborted().toWire();
        assertThat(wf.event()).isEqualTo("error");
        assertThat(wf.data()).containsEntry("code", "ABORTED");
        assertThat(wf.data()).doesNotContainKey("retryAfter");
    }

    @Test
    void toolCallCarriesIdNameDisplayNameArgs() {
        WireFrame wf = new ChatStreamEvent.ToolCall(
                "call_1", "generate_massing", "매싱 모델", Map.of("k", "v")).toWire();
        assertThat(wf.event()).isEqualTo("tool_call");
        assertThat(wf.data()).containsEntry("id", "call_1");
        assertThat(wf.data()).containsEntry("name", "generate_massing");
        assertThat(wf.data()).containsEntry("displayName", "매싱 모델");
        assertThat(wf.data()).containsKey("args");
    }

    @Test
    void toolCallOmitsDisplayNameWhenNull() {
        WireFrame wf = new ChatStreamEvent.ToolCall(
                "call_1", "generate_massing", null, Map.of("k", "v")).toWire();
        assertThat(wf.event()).isEqualTo("tool_call");
        assertThat(wf.data()).doesNotContainKey("displayName");
        assertThat(wf.data()).containsKey("args");
    }

    @Test
    void toolProgressCarriesAllFieldsWithAttempt() {
        WireFrame wf = new ChatStreamEvent.ToolProgress(
                "t1", "generate_massing", "extract", "공간 프로그램 추출 중", 3, 10, 2).toWire();
        assertThat(wf.event()).isEqualTo("tool_progress");
        assertThat(wf.data())
                .containsEntry("id", "t1")
                .containsEntry("name", "generate_massing")
                .containsEntry("stage", "extract")
                .containsEntry("label", "공간 프로그램 추출 중")
                .containsEntry("stageIndex", 3)
                .containsEntry("stageCount", 10)
                .containsEntry("attempt", 2);
    }

    @Test
    void toolProgressOmitsAttemptWhenNull() {
        WireFrame wf = new ChatStreamEvent.ToolProgress(
                "t1", "generate_massing", "compute", "매싱 계산", 7, 10, null).toWire();
        assertThat(wf.event()).isEqualTo("tool_progress");
        assertThat(wf.data()).doesNotContainKey("attempt");
    }

    @Test
    void toolResultCarriesIdNameResult() {
        WireFrame wf = new ChatStreamEvent.ToolResult(
                "call_1", "generate_massing", Map.of("summary", "12 rooms")).toWire();
        assertThat(wf.event()).isEqualTo("tool_result");
        assertThat(wf.data()).containsEntry("id", "call_1");
        assertThat(wf.data()).containsEntry("name", "generate_massing");
        assertThat(wf.data()).containsKey("result");
    }

    @Test
    void toolErrorCarriesIdNameCodeMessage() {
        WireFrame wf = new ChatStreamEvent.ToolError(
                "call_1", "generate_massing", "TIMEOUT", "did not respond within 30s").toWire();
        assertThat(wf.event()).isEqualTo("tool_error");
        assertThat(wf.data()).containsEntry("id", "call_1");
        assertThat(wf.data()).containsEntry("name", "generate_massing");
        assertThat(wf.data()).containsEntry("code", "TIMEOUT");
        assertThat(wf.data()).containsEntry("message", "did not respond within 30s");
    }
}
