package com.playground.ragchat.api.controller;

import com.playground.ragchat.api.dto.SessionResponses;
import com.playground.ragchat.application.service.SessionService;
import com.playground.ragchat.domain.exception.RagChatErrorCode;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.shared.error.ExceptionCreator;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Session CRUD per spec §5.3. Reactive shape (returns
 * {@code Mono<ResponseEntity<...>>}) since {@code rag-chat-api} runs WebFlux.
 * JDBC-bound reads/writes are offloaded to {@code Schedulers.boundedElastic()}
 * per ADR-14 §17 so the request thread is never blocked on a connection
 * acquire.
 *
 * <p>Gateway strips {@code /api/rag/chat} (StripPrefix=3); the controller
 * binds the relative {@code /sessions/**} path.
 */
@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionService.CitationResolver citationResolver;

    public SessionController(SessionService sessionService, SessionService.CitationResolver citationResolver) {
        this.sessionService = sessionService;
        this.citationResolver = citationResolver;
    }

    @PostMapping
    public Mono<ResponseEntity<SessionResponses.CreateSessionResponse>> create(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId) {
        UserId caller = requireCaller(xUserId);
        return Mono.fromCallable(() -> sessionService.create(caller))
                .subscribeOn(Schedulers.boundedElastic())
                .map(s -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(SessionResponses.CreateSessionResponse.from(s)));
    }

    @GetMapping
    public Mono<ResponseEntity<SessionResponses.SessionListResponse>> list(
            @RequestHeader(value = "X-User-Id", required = false) String xUserId) {
        UserId caller = requireCaller(xUserId);
        return Mono.fromCallable(() -> sessionService.list(caller))
                .subscribeOn(Schedulers.boundedElastic())
                .map(rows -> ResponseEntity.ok(new SessionResponses.SessionListResponse(
                        rows.stream().map(SessionResponses.SessionSummaryDto::from).toList())));
    }

    @PatchMapping("/{id}")
    public Mono<ResponseEntity<SessionResponses.RenameSessionResponse>> rename(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", required = false) String xUserId,
            @RequestBody SessionResponses.RenameSessionRequest body) {
        UserId caller = requireCaller(xUserId);
        return Mono.fromCallable(() -> sessionService.rename(SessionId.of(id), caller, body.title()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(s -> ResponseEntity.ok(SessionResponses.RenameSessionResponse.from(s)));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", required = false) String xUserId) {
        UserId caller = requireCaller(xUserId);
        return Mono.<Void>fromRunnable(() -> sessionService.delete(SessionId.of(id), caller))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/{id}/messages")
    public Mono<ResponseEntity<SessionResponses.MessageHistoryResponse>> history(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", required = false) String xUserId) {
        UserId caller = requireCaller(xUserId);
        return Mono.fromCallable(() -> sessionService.loadDetail(SessionId.of(id), caller, citationResolver))
                .subscribeOn(Schedulers.boundedElastic())
                .map(detail -> ResponseEntity.ok(SessionResponses.MessageHistoryResponse.from(detail)));
    }

    private static UserId requireCaller(String xUserId) {
        if (xUserId == null || xUserId.isBlank()) {
            throw ExceptionCreator.of(RagChatErrorCode.AUTH_REQUIRED).build();
        }
        return UserId.fromString(xUserId);
    }
}
