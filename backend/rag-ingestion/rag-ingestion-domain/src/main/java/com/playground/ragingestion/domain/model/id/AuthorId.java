package com.playground.ragingestion.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Author identifier copied from the docs BC's event payload. UUID wrapper —
 * value-equality semantics for the chunk-row's {@code user_id} column
 * (ADR-13 §F).
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
