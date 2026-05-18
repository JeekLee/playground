package com.playground.ragingestion.infrastructure.events;

import com.playground.ragingestion.domain.event.DocumentIngested;
import com.playground.shared.event.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/**
 * Wires Spring Modulith's event externalizer to Kafka per ADR-03 + ADR-13 §3
 * + §D. The single published domain event ({@link DocumentIngested}) is
 * wrapped in the shared-kernel {@link EventEnvelope} and emitted on topic
 * {@code rag.document.ingested}, keyed by {@code documentId} so partition
 * affinity with the docs topics is preserved (downstream consumers see all
 * per-document events on one partition).
 *
 * <p>Selection is by class (no Modulith-import in {@code -domain}) so the
 * event record stays a pure POJO.
 */
@Configuration(proxyBeanMethods = false)
public class RagEventExternalizationConfig {

    @Bean
    public EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof DocumentIngested)
                .route(DocumentIngested.class, e ->
                        RoutingTarget.forTarget("rag.document.ingested")
                                .andKey(e.documentId().value().toString()))
                .mapping(DocumentIngested.class, e ->
                        wrap("rag.document.ingested", ingestedPayload(e)))
                .build();
    }

    private static IngestedPayload ingestedPayload(DocumentIngested e) {
        return new IngestedPayload(
                e.documentId().value().toString(),
                e.userId().value().toString(),
                e.visibility().wireValue(),
                e.chunkCount(),
                e.bodyChecksum(),
                e.embeddedAt());
    }

    private static <T> EventEnvelope<T> wrap(String eventType, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                "rag-ingestion",
                1,
                payload);
    }

    /** Wire payload for {@code rag.document.ingested} per ADR-13 §3 + §G.1. */
    public record IngestedPayload(
            String documentId,
            String userId,
            String visibility,
            int chunkCount,
            String bodyChecksum,
            Instant embeddedAt) {}
}
