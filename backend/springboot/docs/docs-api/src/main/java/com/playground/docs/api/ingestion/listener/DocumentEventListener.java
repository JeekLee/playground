package com.playground.docs.api.ingestion.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.playground.docs.ingestion.application.service.IngestionService;
import com.playground.docs.ingestion.domain.enums.Visibility;
import com.playground.docs.ingestion.domain.event.DocumentDeletedEvent;
import com.playground.docs.ingestion.domain.event.DocumentUploadedEvent;
import com.playground.docs.ingestion.domain.event.DocumentVisibilityChangedEvent;
import com.playground.docs.ingestion.domain.model.id.AuthorId;
import com.playground.docs.ingestion.domain.model.id.DocumentId;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka entry points for M3 per ADR-13 §4 + §"Diagrams". One listener per
 * source topic, each:
 * <ol>
 *   <li>Deserializes the envelope's {@code payload} into a typed
 *       {@code -domain} event record (mirrored shape per ADR-13 §4.5).</li>
 *   <li>Calls the matching {@link IngestionService} method.</li>
 *   <li>Surface any runtime exception so Spring Kafka's
 *       {@code DefaultErrorHandler} can route the source record through the
 *       retry backoff + DLQ recoverer ({@code RagKafkaConsumerConfig}).</li>
 * </ol>
 *
 * <p>The listener accepts the raw envelope as {@link JsonNode} so it is
 * immune to backwards-compatible payload additions in the docs BC (M2 spec
 * §5 + the schema-version envelope field per ADR-03).
 *
 * <p>Consumer group {@code rag-ingestion} per ADR-13 §7 — forward-only at
 * P0, group offset starts at {@code latest}.
 */
@Component
@RequiredArgsConstructor
public class DocumentEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventListener.class);

    private final IngestionService ingestionService;

    @KafkaListener(
            topics = "docs.document.uploaded",
            groupId = "rag-ingestion",
            containerFactory = "ingestionKafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.fasterxml.jackson.databind.JsonNode"})
    public void onUploaded(JsonNode envelope) {
        DocumentUploadedEvent event = parseUploaded(envelope);
        log.info(
                "rag-ingestion: received uploaded documentId={} userId={} bodyChecksum={}",
                event.documentId(), event.userId(), event.bodyChecksum());
        ingestionService.handleUploaded(event);
    }

    @KafkaListener(
            topics = "docs.document.visibility-changed",
            groupId = "rag-ingestion",
            containerFactory = "ingestionKafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.fasterxml.jackson.databind.JsonNode"})
    public void onVisibilityChanged(JsonNode envelope) {
        DocumentVisibilityChangedEvent event = parseVisibilityChanged(envelope);
        log.info(
                "rag-ingestion: received visibility-changed documentId={} userId={} newVisibility={}",
                event.documentId(), event.userId(),
                event.newVisibility().wireValue());
        ingestionService.handleVisibilityChanged(event);
    }

    @KafkaListener(
            topics = "docs.document.deleted",
            groupId = "rag-ingestion",
            containerFactory = "ingestionKafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.fasterxml.jackson.databind.JsonNode"})
    public void onDeleted(JsonNode envelope) {
        DocumentDeletedEvent event = parseDeleted(envelope);
        log.info(
                "rag-ingestion: received deleted documentId={} userId={}",
                event.documentId(), event.userId());
        ingestionService.handleDeleted(event);
    }

    // --- payload extraction ---

    /**
     * Pull payload out of the envelope. Defensive against an unwrapped
     * payload (where the fields are at the top level) — matches the
     * docs-search-projector's symmetric helper.
     */
    private static JsonNode payload(JsonNode envelope) {
        if (envelope == null || envelope.isNull()) {
            throw new IllegalArgumentException("envelope is null");
        }
        JsonNode p = envelope.get("payload");
        return p == null ? envelope : p;
    }

    private static DocumentUploadedEvent parseUploaded(JsonNode envelope) {
        JsonNode p = payload(envelope);
        return new DocumentUploadedEvent(
                DocumentId.of(UUID.fromString(requireText(p, "documentId"))),
                AuthorId.of(UUID.fromString(requireText(p, "userId"))),
                Visibility.fromWire(requireText(p, "visibility")),
                optionalText(p, "title"),
                optionalText(p, "path"),
                requireText(p, "bodyChecksum"));
    }

    private static DocumentVisibilityChangedEvent parseVisibilityChanged(JsonNode envelope) {
        JsonNode p = payload(envelope);
        String publishedAt = optionalText(p, "publishedAt");
        return new DocumentVisibilityChangedEvent(
                DocumentId.of(UUID.fromString(requireText(p, "documentId"))),
                AuthorId.of(UUID.fromString(requireText(p, "userId"))),
                Visibility.fromWire(optionalText(p, "oldVisibility")),
                Visibility.fromWire(requireText(p, "newVisibility")),
                publishedAt == null ? null : Instant.parse(publishedAt));
    }

    private static DocumentDeletedEvent parseDeleted(JsonNode envelope) {
        JsonNode p = payload(envelope);
        return new DocumentDeletedEvent(
                DocumentId.of(UUID.fromString(requireText(p, "documentId"))),
                AuthorId.of(UUID.fromString(requireText(p, "userId"))));
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("Missing field '" + field + "' on envelope payload");
        }
        return v.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
