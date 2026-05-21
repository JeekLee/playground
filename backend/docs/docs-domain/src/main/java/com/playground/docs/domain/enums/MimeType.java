package com.playground.docs.domain.enums;

/**
 * Source MIME type of a document upload per M6 spec (ADR-16).
 *
 * <p>M2 docs BC only ingested raw Markdown via the multipart {@code .md}
 * upload path. M6 adds PDF uploads — the docs BC extracts the text layer (or
 * falls back to Vision OCR) and stores the resulting Markdown in
 * {@code documents.body}. The MIME type is preserved as a column so the
 * frontend can render a "(PDF)" badge on doc lists without re-parsing the
 * body.
 *
 * <p>The wire values match the standard IANA media types; the DB CHECK
 * constraint pins the same two literals
 * ({@code 'text/markdown'} / {@code 'application/pdf'}) per migration
 * {@code V202605210001__add_mime_type.sql}.
 */
public enum MimeType {
    MARKDOWN("text/markdown"),
    PDF("application/pdf");

    private final String wireValue;

    MimeType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static MimeType fromWire(String value) {
        if (value == null) {
            return MARKDOWN;
        }
        for (MimeType m : values()) {
            if (m.wireValue.equalsIgnoreCase(value)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown mime type: " + value);
    }
}
