package com.playground.docs.domain.enums;

/**
 * Async extraction lifecycle per M6.1 ADR-12 §A12.3 / §A12.5.
 *
 * <p>Values:
 * <ul>
 *   <li>{@link #PENDING} — no extraction needed; the document body was
 *       materialized synchronously at create time. Reserved for future
 *       lifecycle hooks; new uploads either skip async entirely (landing
 *       as {@link #EXTRACTED}) or queue.</li>
 *   <li>{@link #PENDING_EXTRACTION} — INSERTed with empty body; awaits the
 *       {@code docs.document.extraction-requested} Kafka listener.</li>
 *   <li>{@link #EXTRACTING} — worker in progress (set on the request thread
 *       inside the worker's transactional boundary so the SSE stream sees
 *       the transition).</li>
 *   <li>{@link #EXTRACTED} — body materialized; the document is queryable.
 *       Default for pre-M6.1 rows backfilled by the Flyway migration and
 *       for synchronous-path uploads (JSON POST, plain markdown multipart).</li>
 *   <li>{@link #FAILED} — worker errored; {@code extraction_reason} set.</li>
 * </ul>
 *
 * <p>The DB CHECK constraint pins the same five wire values (lowercase).
 */
public enum ExtractionStatus {
    PENDING("pending"),
    PENDING_EXTRACTION("pending_extraction"),
    EXTRACTING("extracting"),
    EXTRACTED("extracted"),
    FAILED("failed");

    private final String wireValue;

    ExtractionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ExtractionStatus fromWire(String value) {
        if (value == null) {
            return EXTRACTED;
        }
        for (ExtractionStatus s : values()) {
            if (s.wireValue.equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown extraction status: " + value);
    }

    public boolean isTerminal() {
        return this == EXTRACTED || this == FAILED || this == PENDING;
    }
}
