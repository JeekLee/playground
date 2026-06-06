package com.playground.chat.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier for the caller. App-level reference to {@code identity.users.id};
 * not a DB FK because chat lives in a separate schema per ADR-05.
 */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "UserId.value must not be null");
    }

    public static UserId of(UUID value) {
        return new UserId(value);
    }

    public static UserId fromString(String value) {
        return new UserId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
