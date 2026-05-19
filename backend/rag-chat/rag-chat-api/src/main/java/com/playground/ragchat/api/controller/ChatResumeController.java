package com.playground.ragchat.api.controller;

import com.playground.ragchat.application.service.ActiveTurnRegistry;
import com.playground.ragchat.domain.exception.RagChatErrorCode;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.shared.error.ExceptionCreator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Mid-stream re-join surface — {@code GET /api/rag/chat/sessions/{id}/stream}.
 * Lets a client attach to an in-flight chat-turn flux that was started by
 * an earlier {@code POST /api/rag/chat} but whose original SSE subscriber
 * disconnected (navigate-away, tab close, Stop). The
 * {@link ActiveTurnRegistry} keeps the hot replay-shared flux alive
 * until the server pipeline terminates; a late subscriber gets the
 * buffered {@code retrieval} + every token emitted so far + the live
 * tail (or the final {@code done} if the turn finished while away).
 *
 * <p>Auth + tenant enforcement mirrors {@code SessionController}: caller
 * must be the session owner, otherwise 404 (we do not distinguish
 * "not yours" from "no in-flight turn for this session" — both shapes
 * read the same to the client).
 *
 * <p>{@link ChatStreamController#toSse} is reused as the
 * {@link ChatStreamEvent} → SSE-frame mapper so both endpoints emit
 * identical wire grammar (spec §5.2).
 */
@RestController
@RequestMapping("/sessions")
public class ChatResumeController {

    private static final Logger log = LoggerFactory.getLogger(ChatResumeController.class);

    private final ActiveTurnRegistry registry;

    public ChatResumeController(ActiveTurnRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> resume(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestHeader(value = "Accept", required = false) String accept) {

        if (xUserId == null || xUserId.isBlank()) {
            throw ExceptionCreator.of(RagChatErrorCode.AUTH_REQUIRED).build();
        }
        if (accept == null || !accept.toLowerCase().contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            throw ExceptionCreator.of(RagChatErrorCode.ACCEPT_HEADER_REQUIRED).build();
        }

        SessionId sessionId;
        try {
            sessionId = SessionId.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            throw ExceptionCreator.of(RagChatErrorCode.SESSION_NOT_FOUND).build();
        }
        UserId caller = UserId.fromString(xUserId);

        ActiveTurnRegistry.Entry entry = registry.find(sessionId)
                .filter(e -> e.owner().equals(caller))
                .orElseThrow(() -> ExceptionCreator.of(RagChatErrorCode.SESSION_NOT_FOUND).build());

        log.info("resume_attach sessionId=" + sessionId + " userId=" + caller
                + " startedAt=" + entry.startedAt());

        return entry.stream().map(ChatStreamController::toSse);
    }
}
