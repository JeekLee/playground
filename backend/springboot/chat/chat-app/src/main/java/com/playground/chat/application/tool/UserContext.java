package com.playground.chat.application.tool;

import com.playground.chat.domain.model.id.UserId;
import java.util.Objects;

/**
 * Identity carrier forwarded from the originating chat session to the
 * tool-BC dispatch per ADR-17 §9 + PRD Story 3.
 *
 * <p>The dispatcher forwards the carried fields as HTTP headers on the
 * downstream {@code POST /internal/tools/<name>} call:
 * <ul>
 *   <li>{@link #userId()} → {@code X-User-Id}</li>
 *   <li>{@link #userSub()} → {@code X-User-Sub}</li>
 * </ul>
 *
 * <p>Per ADR-08 §A08.8, {@code Authorization} and cookie headers are
 * <b>not</b> forwarded — the {@code /internal/**} channel is a trusted
 * backend-to-backend pipe.
 *
 * @param userId  authenticated user's domain id (originated from
 *                {@code X-User-Id} on the SSE request). Required.
 * @param userSub Google {@code sub} (originated from {@code X-User-Sub}).
 *                Optional — null is allowed for legacy / test paths.
 */
public record UserContext(UserId userId, String userSub) {

    public UserContext {
        Objects.requireNonNull(userId, "UserContext.userId must not be null");
    }
}
