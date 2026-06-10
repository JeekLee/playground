package com.playground.chat.api.controller;

import com.playground.chat.api.dto.ChatTurnRequestBody;
import com.playground.chat.application.dto.ChatTurnRequest;
import com.playground.chat.application.service.ChatTurnService;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.shared.chat.SseFrame;
import com.playground.shared.error.ExceptionCreator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive SSE controller for {@code POST /api/chat} per ADR-14 §1 + spec
 * §5.1. Translates the request body + gateway-injected {@code X-User-Id /
 * X-User-Sub} headers into a {@link ChatTurnRequest}, delegates to
 * {@link ChatTurnService#stream}, maps each emitted
 * {@link ChatStreamEvent} into a {@link ServerSentEvent}.
 *
 * <p>The gateway routes {@code /api/chat/**} with {@code StripPrefix=2} per
 * the gateway YAML, so this controller listens on {@code POST /} (root). The
 * 415 (Accept must include text/event-stream) gate is enforced via the
 * {@code produces} attribute — Spring WebFlux returns 406 if the Accept header
 * is incompatible. We narrow that to 415 with a domain check.
 */
@RestController
@RequiredArgsConstructor
public class ChatStreamController {

    private final ChatTurnService chatTurnService;

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
            throw ExceptionCreator.of(ChatErrorCode.AUTH_REQUIRED).build();
        }
        if (accept == null || !accept.toLowerCase().contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            throw ExceptionCreator.of(ChatErrorCode.ACCEPT_HEADER_REQUIRED).build();
        }

        UserId caller = UserId.fromString(xUserId);
        SessionId sessionId = SessionId.of(body.sessionId());
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, xUserSub, body.message());

        return chatTurnService.stream(req).map(ChatStreamController::toSse);
    }

    /**
     * Maps a {@link ChatStreamEvent} onto the transport {@link ServerSentEvent}.
     * The per-variant wire shape (event name + data keys + conditional omissions)
     * lives on each event variant via {@link ChatStreamEvent#toSseFrame()}; this
     * method only wraps the resulting framework-neutral {@link SseFrame} into
     * Spring's SSE carrier. Reused by {@link ChatResumeController} so both
     * endpoints emit identical wire grammar.
     */
    static ServerSentEvent<Object> toSse(ChatStreamEvent evt) {
        SseFrame frame = evt.toSseFrame();
        return ServerSentEvent.<Object>builder((Object) frame.data()).event(frame.event()).build();
    }

}
