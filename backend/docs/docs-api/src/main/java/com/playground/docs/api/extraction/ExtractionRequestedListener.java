package com.playground.docs.api.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.playground.docs.application.service.ExtractionWorkflow;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * M6.1 ADR-12 §A12.5/§A12.6 — in-BC Kafka listener for
 * {@code docs.document.extraction-requested}.
 *
 * <p>The listener runs on Spring Kafka's container thread pool. To prevent
 * blocking the consumer poll for the duration of an extraction (which can
 * be minutes for a 100-page PDF), the listener dispatches the workflow run
 * to the shared {@code extractionExecutor} and returns immediately. The
 * extractor's {@code CallerRunsPolicy} provides natural back-pressure into
 * the consumer thread when the executor queue is full.
 *
 * <p>Idempotency: the workflow short-circuits when the document is already
 * in a terminal extraction state (extracted / failed). Kafka redeliveries
 * (e.g. after consumer crash) are therefore safe.
 */
@Component
public class ExtractionRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(ExtractionRequestedListener.class);

    private final ExtractionWorkflow workflow;
    private final ThreadPoolExecutor extractionExecutor;

    public ExtractionRequestedListener(
            ExtractionWorkflow workflow,
            @Qualifier("extractionExecutor") ThreadPoolExecutor extractionExecutor) {
        this.workflow = workflow;
        this.extractionExecutor = extractionExecutor;
    }

    @KafkaListener(
            topics = "docs.document.extraction-requested",
            groupId = "docs-extraction",
            containerFactory = "ingestionKafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.fasterxml.jackson.databind.JsonNode"})
    public void onExtractionRequested(JsonNode envelope) {
        UUID documentId = parseDocumentId(envelope);
        log.info("docs-extraction: dispatching extraction for documentId={}", documentId);
        // Submit and forget — the workflow does its own transaction
        // boundaries + SSE broadcasting + DocumentUploaded emission.
        extractionExecutor.execute(() -> {
            try {
                workflow.run(documentId);
            } catch (RuntimeException e) {
                log.warn("Extraction workflow crashed for documentId={}: {}", documentId, e.toString());
            }
        });
    }

    private static UUID parseDocumentId(JsonNode envelope) {
        JsonNode payload = envelope.get("payload");
        JsonNode target = payload != null ? payload : envelope;
        JsonNode idNode = target.get("documentId");
        if (idNode == null || idNode.isNull()) {
            throw new IllegalArgumentException("extraction-requested envelope missing documentId");
        }
        return UUID.fromString(idNode.asText());
    }
}
