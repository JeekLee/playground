package com.playground.docs.ingestion.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Logical reference to a docs BC document. UUID wrapper per ADR-13 §F (no
 * FK to {@code docs.documents} — schema-per-BC under ADR-05).
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

    @Override
    public String toString() {
        return value.toString();
    }
}
