package com.playground.docs.infrastructure.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.docs.application.port.IdentityLookupPort;
import com.playground.docs.application.port.SearchIndexPort;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.DocumentId;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * In-service search projector per M2 spec §5.1 + ADR-12 §15. Subscribes to
 * the three {@code docs.document.*} Kafka topics emitted by this same BC's
 * outbox externalizer; for each event, fetches the authoritative row from
 * Postgres and mirrors it into the {@code docs-v1} OpenSearch index.
 *
 * <p>Why fetch from Postgres rather than read the body off the envelope?
 * The spec §5 rule "payload never contains the raw body" — events carry
 * only the {@code bodyChecksum}; consumers (including this projector) must
 * read the body from the source of truth. The latency cost is acceptable
 * because the projector is async to the original write transaction.
 *
 * <p>Failure isolation per spec §10 + §5.1: an OpenSearch outage causes the
 * listener to throw, which surfaces as a delivery failure on the Kafka
 * consumer. With the default Spring Kafka error handling the broker offset
 * does not advance — the message redelivers on the next poll cycle, and
 * the projector tries again when OpenSearch is back. The DB transaction
 * is already committed (Modulith publishes after commit), so projector
 * failures never roll back user data. The S3-deferred DLQ-on-3rd-fail
 * policy lives in ADR-12 §15 and the per-milestone follow-up.
 *
 * <p>The listener accepts the raw JSON envelope as {@link JsonNode} — we
 * read only the {@code documentId} field; the rest of the payload is read
 * from Postgres directly. This keeps the consumer immune to backwards-
 * compatible schema additions in the wire payload.
 */
@Component
@RequiredArgsConstructor
public class DocsSearchProjector {

    private static final Logger log = LoggerFactory.getLogger(DocsSearchProjector.class);

    private final DocumentRepository repository;
    private final SearchIndexPort searchIndex;
    private final IdentityLookupPort identityLookup;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {"docs.document.uploaded", "docs.document.visibility-changed"},
            groupId = "docs-search-projector",
            properties = {"spring.json.value.default.type=com.fasterxml.jackson.databind.JsonNode"})
    public void onUpsertEvent(JsonNode envelope) {
        String documentId = extractDocumentId(envelope);
        if (documentId == null) {
            log.warn("search-projector: missing documentId on envelope — dropping: {}", envelope);
            return;
        }
        try {
            Optional<Document> doc = repository.findById(DocumentId.of(UUID.fromString(documentId)));
            if (doc.isEmpty()) {
                // Race: doc deleted between event publish and projector consume.
                // Defer to the deleted event to clear the index entry.
                log.info("search-projector: doc {} no longer present — skipping upsert", documentId);
                return;
            }
            String authorName = resolveAuthorName(doc.get().authorId().value());
            searchIndex.index(doc.get(), authorName);
            log.info("search-projector: indexed doc={} visibility={} title=\"{}\"",
                    documentId, doc.get().visibility().wireValue(), doc.get().title().value());
        } catch (RuntimeException e) {
            // Log + rethrow so the Kafka consumer surfaces it as a delivery failure.
            log.warn("search-projector: index upsert failed for doc={} — Kafka will redeliver", documentId, e);
            throw e;
        }
    }

    @KafkaListener(
            topics = "docs.document.deleted",
            groupId = "docs-search-projector",
            properties = {"spring.json.value.default.type=com.fasterxml.jackson.databind.JsonNode"})
    public void onDeleteEvent(JsonNode envelope) {
        String documentId = extractDocumentId(envelope);
        if (documentId == null) {
            log.warn("search-projector: missing documentId on delete envelope — dropping: {}", envelope);
            return;
        }
        try {
            searchIndex.delete(UUID.fromString(documentId));
            log.info("search-projector: deleted doc={} from index", documentId);
        } catch (RuntimeException e) {
            log.warn("search-projector: index delete failed for doc={} — Kafka will redeliver", documentId, e);
            throw e;
        }
    }

    /**
     * Pull {@code documentId} out of the envelope's payload. Defensive against
     * unwrapped payloads (where {@code documentId} is at the top level) and
     * shared-kernel-wrapped envelopes (where it lives at
     * {@code payload.documentId}).
     */
    private static String extractDocumentId(JsonNode envelope) {
        if (envelope == null || envelope.isNull()) return null;
        JsonNode payload = envelope.get("payload");
        JsonNode root = payload == null ? envelope : payload;
        JsonNode field = root.get("documentId");
        return field == null || field.isNull() ? null : field.asText();
    }

    private String resolveAuthorName(UUID userId) {
        if (identityLookup == null) {
            return null;
        }
        return identityLookup.findById(userId)
                .map(a -> a.displayName())
                .orElse(null);
    }
}
