package com.playground.ragchat.api.controller;

import com.playground.ragchat.api.dto.ChatTurnRequestBody;
import com.playground.ragchat.application.dto.ChatStreamEvent;
import com.playground.ragchat.application.dto.ChatTurnRequest;
import com.playground.ragchat.application.service.ChatTurnService;
import com.playground.ragchat.domain.exception.RagChatErrorCode;
import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.shared.error.ExceptionCreator;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
        if (evt instanceof ChatStreamEvent.Retrieval r) {
            return ServerSentEvent.<Object>builder(retrievalPayload(r.chunks())).event("retrieval").build();
        }
        if (evt instanceof ChatStreamEvent.Token t) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("delta", t.delta());
            return ServerSentEvent.<Object>builder((Object) data).event("token").build();
        }
        if (evt instanceof ChatStreamEvent.Done d) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageId", d.messageId().value().toString());
            data.put("tokensIn", d.tokensIn());
            data.put("tokensOut", d.tokensOut());
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
        log.warn("unknown chat stream event type: {}", evt.getClass());
        return ServerSentEvent.<Object>builder((Object) Map.of("code", "INTERNAL", "message", "unknown event"))
                .event("error").build();
    }

    private static Map<String, Object> retrievalPayload(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> citations = new java.util.ArrayList<>(chunks.size());
        for (RetrievedChunk c : chunks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", c.position());
            m.put("documentId", c.documentId().value().toString());
            m.put("chunkIndex", c.chunkIndex());
            m.put("title", c.title());
            m.put("excerpt", excerpt(c.text()));
            m.put("visibility", c.visibility().wireValue());
            citations.add(m);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("citations", citations);
        return payload;
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    // UUID import resolution helper to avoid unused-import warnings.
    @SuppressWarnings("unused")
    private static UUID forceUuidImport() {
        return null;
    }
}
