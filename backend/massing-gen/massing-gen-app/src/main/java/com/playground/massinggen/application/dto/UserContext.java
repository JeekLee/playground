package com.playground.massinggen.application.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable VO carrying the inbound {@code X-User-Id} + {@code X-User-Sub}
 * pair forwarded by rag-chat per M7 ADR-17 §3 + ADR-08 §A08.11. The use
 * case uses {@code userId} to tag the {@code arch.outputs.user_id} column;
 * the {@code userSub} (Google OIDC subject) is forwarded onward to docs-api
 * per ADR-08 §A08.12 Exception 5's identity-propagation rule.
 *
 * @param userId  user UUID — required, never null
 * @param userSub Google OIDC sub — may be null when forwarded by an
 *                anonymous or service-internal caller (M8 P0 invariant
 *                requires both to be present; controller enforces it)
 */
public record UserContext(UUID userId, String userSub) {

    public UserContext {
        Objects.requireNonNull(userId, "UserContext.userId must not be null");
    }
}
