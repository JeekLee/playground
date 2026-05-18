package com.playground.docs.domain.enums;

/**
 * Document visibility per M2 spec §4.1 + ADR-09 amendment. The DB CHECK
 * constraint pins the literal values {@code 'private'} / {@code 'public'};
 * Java's enum names use the same lowercase token via {@link #wireValue()} so
 * a single round-trip (DTO -> JPA -> SQL) keeps the same string.
 *
 * <p>Transitions allowed in M2 spec §4.4:
 * <pre>
 *   PRIVATE --publish--> PUBLIC --unpublish--> PRIVATE
 * </pre>
 *
 * <p>S1 (single-author CRUD) supports {@link #PRIVATE} and {@link #PUBLIC} but
 * the dedicated {@code /publish} and {@code /unpublish} endpoints are deferred
 * to S2 — visibility is set on create (default PRIVATE) and may be patched
 * directly. First {@code private -> public} transition stamps {@code
 * publishedAt = now()} (see {@code Document.changeVisibility}).
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
