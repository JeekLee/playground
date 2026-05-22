package com.playground.ragchat.domain.model.id;

import java.util.Objects;
import java.util.UUID;

/**
 * App-level reference to a docs BC document. UUID wrapper per ADR-14 §F (no
 * FK to {@code docs.documents} — schema-per-BC under ADR-05). Mirrored here,
 * not imported from {@code docs-domain}, per the same discipline ADR-13 §4.5
 * applied to rag-ingestion.
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
