package com.playground.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical wrapper for every Kafka event emitted by a playground BC.
 * Defined here in shared-kernel so producers and consumers agree byte-for-byte.
 * <p>
 * Field rules pinned by ADR-03 ("Kafka KRaft, topic naming, event envelope"):
 * <ul>
 *   <li>{@code eventId} — UUID v4 per emission. Consumers use this for idempotency.</li>
 *   <li>{@code eventType} — fully-qualified topic name ({@code <bc>.<aggregate>.<verb-past>}).</li>
 *   <li>{@code occurredAt} — UTC wall-clock at the producer.</li>
 *   <li>{@code producerId} — producing BC name ({@code identity}, {@code docs}, ...).</li>
 *   <li>{@code schemaVersion} — integer, incremented on incompatible payload changes.</li>
 *   <li>{@code payload} — BC-specific record; serialized verbatim by Jackson.</li>
 * </ul>
 * The payload type is BC-defined and never imported into shared-kernel.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String producerId,
        int schemaVersion,
        T payload
) {
}
