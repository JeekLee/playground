package com.playground.ragchat.application.port;

import com.playground.ragchat.domain.model.id.UserId;
import java.util.Optional;

/**
 * Cross-schema SELECT into {@code identity.users} for the chat header's
 * display-name rendering per ADR-14 §3. Called once per session-page-load,
 * not per turn. Degrades gracefully — an empty {@link Optional} means
 * "render the email prefix instead".
 */
public interface OwnerDisplayNamePort {

    Optional<DisplayName> lookup(UserId userId);

    record DisplayName(String displayName, String avatarUrl) {}
}
