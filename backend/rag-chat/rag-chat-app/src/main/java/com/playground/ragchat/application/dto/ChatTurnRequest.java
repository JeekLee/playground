package com.playground.ragchat.application.dto;

import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;

/**
 * Input to {@code ChatTurnService.stream(...)}. Carries everything that came
 * off the HTTP request after auth-header binding.
 *
 * @param sessionId  session the user is posting into; must be owned by {@code caller}
 * @param caller     {@code X-User-Id}-bound user
 * @param userSub    {@code X-User-Sub} for audit logging per ADR-14 §15
 * @param message    user's turn text (≤ 4 KB raw)
 */
public record ChatTurnRequest(SessionId sessionId, UserId caller, String userSub, String message) {

    public ChatTurnRequest {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(caller, "caller");
        java.util.Objects.requireNonNull(message, "message");
    }
}
