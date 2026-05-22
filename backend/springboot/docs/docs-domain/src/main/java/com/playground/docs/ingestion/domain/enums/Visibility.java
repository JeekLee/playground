package com.playground.docs.ingestion.domain.enums;

/**
 * Visibility mirror of the docs BC's {@code Visibility} enum per ADR-13 §F.
 * Wire values must match the {@code rag.document_chunks.visibility} CHECK
 * constraint ({@code 'public'} / {@code 'private'}) and the docs-emitted event
 * payload's {@code visibility} field. Lower-case wire format is preserved on
 * round-trip.
 *
 * <p>Mirrored rather than imported per ADR-13 §4.5 — the rag-ingestion BC
 * compiles independently of {@code docs-domain}.
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
