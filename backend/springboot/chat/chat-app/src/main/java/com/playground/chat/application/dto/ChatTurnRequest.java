package com.playground.chat.application.dto;

import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.error.ExceptionCreator;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Input to {@code ChatTurnService.stream(...)}. Carries everything that came
 * off the HTTP request after auth-header binding.
 *
 * <p>Self-validating value object: an invalid request can't be constructed.
 * {@code null} fields are programmer/binding errors ({@link NullPointerException});
 * a blank or oversize message is a user-input error mapped to HTTP 400 via
 * {@link ChatErrorCode} (the controller constructs this in its handler, so the
 * reactive advice translates the throw before any SSE handshake commits).
 *
 * @param sessionId  session the user is posting into; must be owned by {@code caller}
 * @param caller     {@code X-User-Id}-bound user
 * @param userSub    {@code X-User-Sub} for audit logging per ADR-14 §15
 * @param message    user's turn text (non-blank, ≤ 4 KB raw)
 */
public record ChatTurnRequest(SessionId sessionId, UserId caller, String userSub, String message) {

    /** Per-turn user-message raw byte cap per spec §5.1 (4 KB). The request owns
     *  its own size invariant — not env-tunable, so it lives with the value object
     *  rather than {@code ChatProperties}. */
    public static final int MAX_MESSAGE_BYTES = 4096;

    public ChatTurnRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw ExceptionCreator.of(ChatErrorCode.MESSAGE_BLANK).build();
        }
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw ExceptionCreator.of(ChatErrorCode.MESSAGE_TOO_LARGE).build();
        }
    }
}
