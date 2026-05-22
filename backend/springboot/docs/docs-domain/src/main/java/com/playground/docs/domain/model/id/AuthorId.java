package com.playground.docs.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Author identifier. Cross-BC reference to {@code identity.users.id} per M2
 * spec §3 + ADR-12 §8. Per ADR-05 + ADR-12 §8 there is NO database-level FK
 * constraint across schemas — the reference is app-level.
 */
public record AuthorId(UUID value) {

    public AuthorId {
        Objects.requireNonNull(value, "AuthorId.value must not be null");
    }

    public static AuthorId of(UUID value) {
        return new AuthorId(value);
    }

    public static AuthorId fromString(String value) {
        return new AuthorId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
