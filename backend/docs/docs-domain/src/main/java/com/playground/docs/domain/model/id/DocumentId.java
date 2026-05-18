package com.playground.docs.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Document identifier. UUID wrapper per M2 spec §4 (URLs are
 * {@code /docs/{id}} where {@code id} is the immutable UUID).
 */
public record DocumentId(UUID value) {

    public DocumentId {
        Objects.requireNonNull(value, "DocumentId.value must not be null");
    }

    public static DocumentId of(UUID value) {
        return new DocumentId(value);
    }

    public static DocumentId fromString(String value) {
        return new DocumentId(UUID.fromString(value));
    }

    public static DocumentId generate() {
        return new DocumentId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
