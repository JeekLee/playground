package com.playground.ragchat.api.controller;

import com.playground.ragchat.api.dto.ChatTurnRequestBody;
import com.playground.ragchat.application.dto.ChatTurnRequest;
import com.playground.ragchat.application.service.ChatTurnService;
import com.playground.ragchat.domain.exception.RagChatErrorCode;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.shared.error.ExceptionCreator;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive SSE controller for {@code POST /api/rag/chat} per ADR-14 §1 + spec
 * §5.1. Translates the request body + gateway-injected {@code X-User-Id /
 * X-User-Sub} headers into a {@link ChatTurnRequest}, delegates to
 * {@link ChatTurnService#stream}, maps each emitted
 * {@link ChatStreamEvent} into a {@link ServerSentEvent}.
 *
 * <p>The gateway routes {@code /api/rag/chat/**} with {@code StripPrefix=3} per
 * the gateway YAML, so this controller listens on {@code POST /} (root). The
 * 415 (Accept must include text/event-stream) gate is enforced via the
 * {@code produces} attribute — Spring WebFlux returns 406 if the Accept header
 * is incompatible. We narrow that to 415 with a domain check.
 */
@RestController
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    private final ChatTurnService chatTurnService;

    public ChatStreamController(ChatTurnService chatTurnService) {
        this.chatTurnService = chatTurnService;
    }

    @PostMapping(
            value = {"", "/"},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "X-User-Sub", required = false) String xUserSub,
            @RequestHeader(value = "Accept", required = false) String accept,
            @Valid @RequestBody ChatTurnRequestBody body) {

        if (xUserId == null || xUserId.isBlank()) {
            throw ExceptionCreator.of(RagChatErrorCode.AUTH_REQUIRED).build();
        }
        if (accept == null || !accept.toLowerCase().contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            throw ExceptionCreator.of(RagChatErrorCode.ACCEPT_HEADER_REQUIRED).build();
        }

        UserId caller = UserId.fromString(xUserId);
        SessionId sessionId = SessionId.of(body.sessionId());
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, xUserSub, body.message());

        return chatTurnService.stream(req).map(ChatStreamController::toSse);
    }

    static ServerSentEvent<Object> toSse(ChatStreamEvent evt) {
        if (evt instanceof ChatStreamEvent.Phase p) {
            // Wire grammar revision (PR B / spec §5.2 revised): every
            // progress event ships as SSE `event: phase`. The legacy
            // `event: retrieval` is gone — citation cards now arrive
            // only on the terminal `done` event.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("step", p.step());
            data.put("label", p.label());
            if (p.data() != null && !p.data().isEmpty()) {
                data.put("data", p.data());
            }
            return ServerSentEvent.<Object>builder((Object) data).event("phase").build();
        }
        if (evt instanceof ChatStreamEvent.Token t) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("delta", t.delta());
            return ServerSentEvent.<Object>builder((Object) data).event("token").build();
        }
        if (evt instanceof ChatStreamEvent.Done d) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageId", d.messageId());
            data.put("tokensIn", d.tokensIn());
            data.put("tokensOut", d.tokensOut());
            // PR B: cited subset arrives with the terminal event. The
            // BC's CitationDto record serializes naturally via Jackson.
            if (d.citations() != null) {
                data.put("citations", d.citations());
            }
            return ServerSentEvent.<Object>builder((Object) data).event("done").build();
        }
        if (evt instanceof ChatStreamEvent.Error e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", e.code());
            data.put("message", e.message());
            if (e.retryAfterSeconds() != null) {
                data.put("retryAfter", e.retryAfterSeconds());
            }
            return ServerSentEvent.<Object>builder((Object) data).event("error").build();
        }
        if (evt instanceof ChatStreamEvent.ToolCall tc) {
            // ADR-17 §3 + §A14.3 — wire shape carries id + name + args. Args is
            // the LLM-produced JsonNode (or Jackson-serializable carrier); the
            // controller defers JSON serialization to Spring's reactive codec.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", tc.id());
            data.put("name", tc.name());
            data.put("args", tc.args());
            return ServerSentEvent.<Object>builder((Object) data).event("tool_call").build();
        }
        if (evt instanceof ChatStreamEvent.ToolResult tr) {
            // ADR-17 §3 — wire shape carries id + name + result. Result may be
            // a Jackson JsonNode (truncated per §4) or any Jackson-serializable
            // carrier.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", tr.id());
            data.put("name", tr.name());
            data.put("result", tr.result());
            return ServerSentEvent.<Object>builder((Object) data).event("tool_result").build();
        }
        if (evt instanceof ChatStreamEvent.ToolError te) {
            // ADR-17 §3 — wire shape carries id + name + code (ToolErrorCode
            // enum name string) + message. Note `tool_error` is NOT a
            // fall-back to `error` — frontend dispatches them separately.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", te.id());
            data.put("name", te.name());
            data.put("code", te.code());
            data.put("message", te.message());
            return ServerSentEvent.<Object>builder((Object) data).event("tool_error").build();
        }
        log.warn("unknown chat stream event type: {}", evt.getClass());
        return ServerSentEvent.<Object>builder((Object) Map.of("code", "INTERNAL", "message", "unknown event"))
                .event("error").build();
    }

}
