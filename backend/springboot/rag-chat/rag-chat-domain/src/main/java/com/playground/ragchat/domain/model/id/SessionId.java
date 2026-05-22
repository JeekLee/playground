package com.playground.ragchat.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/** Identifier for {@link com.playground.ragchat.domain.model.ChatSession} per ADR-14 §F. */
public record SessionId(UUID value) {

    public SessionId {
        Objects.requireNonNull(value, "SessionId.value must not be null");
    }

    public static SessionId of(UUID value) {
        return new SessionId(value);
    }

    public static SessionId fromString(String value) {
        return new SessionId(UUID.fromString(value));
    }

    public static SessionId generate() {
        return new SessionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
