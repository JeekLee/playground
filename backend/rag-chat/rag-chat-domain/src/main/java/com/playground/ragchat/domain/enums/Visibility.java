package com.playground.ragchat.domain.enums;

/**
 * Visibility mirror of the M2 docs / M3 rag-ingestion enum per ADR-14 §3.2.
 * Used in the retrieval predicate
 * ({@code WHERE visibility='public' OR (user_id=? AND visibility='private')})
 * and in the {@code retrieval} SSE event payload. Lower-case wire format is
 * preserved on round-trip.
 *
 * <p>Mirrored rather than imported per the same discipline ADR-13 §4.5
 * applied to rag-ingestion — the rag-chat BC compiles independently.
 */
public enum Visibility {
    PRIVATE("private"),
    PUBLIC("public");

    private final String wireValue;

    Visibility(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Visibility fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (Visibility v : values()) {
            if (v.wireValue.equalsIgnoreCase(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown visibility: " + value);
    }

    public boolean isPublic() {
        return this == PUBLIC;
    }
}
