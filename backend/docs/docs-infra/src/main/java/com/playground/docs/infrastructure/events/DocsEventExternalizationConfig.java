package com.playground.docs.infrastructure.events;

import com.playground.docs.domain.event.DocumentDeleted;
import com.playground.docs.domain.event.DocumentUploaded;
import com.playground.docs.domain.event.DocumentVisibilityChanged;
import com.playground.shared.event.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/**
 * Wires Spring Modulith's event externalizer to Kafka topics per ADR-03 +
 * ADR-12 §1 (inheriting ADR-10 §8). Each domain event is wrapped in the
 * shared-kernel {@link EventEnvelope} before publication so consumers see one
 * canonical shape across BCs.
 *
 * <p>Topic naming follows ADR-03 ({@code <bc>.<aggregate>.<verb-past>}) and
 * spec §5:
 * <ul>
 *   <li>{@code docs.document.uploaded} — create or body change</li>
 *   <li>{@code docs.document.visibility-changed} — publish / unpublish</li>
 *   <li>{@code docs.document.deleted} — hard delete</li>
 * </ul>
 *
 * <p>Each event is keyed by the document id so a single document's events land
 * on the same Kafka partition (consumers — in-service projector + M3
 * ingestion — order operations on the same key correctly).
 *
 * <p>Selection is by class (not by {@code @Externalized} annotation) so the
 * domain-event records in {@code docs-domain} stay free of any Modulith
 * import — Spring Modulith never reaches into {@code -domain}.
 */
@Configuration(proxyBeanMethods = false)
public class DocsEventExternalizationConfig {

    @Bean
    public EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof DocumentUploaded
                        || event instanceof DocumentVisibilityChanged
                        || event instanceof DocumentDeleted)
                .route(DocumentUploaded.class, e -> RoutingTarget.forTarget("docs.document.uploaded")
                        .andKey(e.documentId().value().toString()))
                .route(DocumentVisibilityChanged.class, e -> RoutingTarget.forTarget("docs.document.visibility-changed")
                        .andKey(e.documentId().value().toString()))
                .route(DocumentDeleted.class, e -> RoutingTarget.forTarget("docs.document.deleted")
                        .andKey(e.documentId().value().toString()))
                .mapping(DocumentUploaded.class, e -> wrap("docs.document.uploaded", uploadedPayload(e)))
                .mapping(DocumentVisibilityChanged.class, e -> wrap("docs.document.visibility-changed", visibilityPayload(e)))
                .mapping(DocumentDeleted.class, e -> wrap("docs.document.deleted", deletedPayload(e)))
                .build();
    }

    private static UploadedPayload uploadedPayload(DocumentUploaded e) {
        return new UploadedPayload(
                e.documentId().value().toString(),
                e.userId().value().toString(),
                e.visibility().wireValue(),
                e.title().value(),
                e.path().value(),
                e.bodyChecksum());
    }

    private static VisibilityPayload visibilityPayload(DocumentVisibilityChanged e) {
        return new VisibilityPayload(
                e.documentId().value().toString(),
                e.userId().value().toString(),
                e.oldVisibility().wireValue(),
                e.newVisibility().wireValue(),
                e.publishedAt());
    }

    private static DeletedPayload deletedPayload(DocumentDeleted e) {
        return new DeletedPayload(
                e.documentId().value().toString(),
                e.userId().value().toString());
    }

    private static <T> EventEnvelope<T> wrap(String eventType, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                "docs",
                1,
                payload);
    }

    /**
     * Wire payload for {@code docs.document.uploaded} per M2 spec §5.
     * Idempotency key: {@code documentId + bodyChecksum}.
     */
    public record UploadedPayload(
            String documentId,
            String userId,
            String visibility,
            String title,
            String path,
            String bodyChecksum) {
    }

    /**
     * Wire payload for {@code docs.document.visibility-changed} per spec §5.
     * Idempotency key: {@code documentId + newVisibility}.
     * {@code publishedAt} is present when {@code newVisibility='public'} and
     * the spec leaves consumers to read the historical value on unpublish.
     */
    public record VisibilityPayload(
            String documentId,
            String userId,
            String oldVisibility,
            String newVisibility,
            Instant publishedAt) {
    }

    /** Wire payload for {@code docs.document.deleted} per spec §5. Idempotency key: {@code documentId}. */
    public record DeletedPayload(String documentId, String userId) {
    }
}
