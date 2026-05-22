package com.playground.docs.application.service;

import com.playground.docs.application.dto.ExtractionStatusUpdate;
import com.playground.docs.application.port.BlobStoragePort;
import com.playground.docs.application.port.PdfExtractorPort;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.enums.ExtractionStatus;
import com.playground.docs.domain.enums.MimeType;
import com.playground.docs.domain.event.DocumentUploaded;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.shared.error.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M6.1 ADR-12 §A12.5 — drives the async extraction of an uploaded PDF/MD
 * document. Triggered by the in-BC Kafka listener on
 * {@code docs.document.extraction-requested}; the listener calls
 * {@link #run(UUID)} and the workflow:
 *
 * <ol>
 *   <li>Marks the row {@code extraction_status='extracting'} (broadcasts SSE).</li>
 *   <li>Streams the source blob from MinIO.</li>
 *   <li>Branches on mime type — PDF goes through {@link PdfExtractorPort};
 *       Markdown decodes the bytes verbatim.</li>
 *   <li>Updates the row {@code extraction_status='extracted'} (or
 *       {@code 'failed'} if PDF page-cap blown / extraction failed past the
 *       degraded-but-OK threshold).</li>
 *   <li>Broadcasts the terminal SSE event + publishes
 *       {@link DocumentUploaded} on success so the in-BC ingestion listener
 *       picks up the freshly-materialized body.</li>
 * </ol>
 *
 * <p>The workflow is idempotent — if the row is already in a terminal state
 * ({@code extracted} / {@code failed} / {@code pending}) the workflow
 * short-circuits. Kafka may redeliver the event under at-least-once
 * semantics; this guard keeps the second pass from re-running extraction
 * unnecessarily.
 */
@Service
public class ExtractionWorkflow {

    private static final Log log = LogFactory.getLog(ExtractionWorkflow.class);

    private final DocumentRepository repository;
    private final BlobStoragePort blobStorage;
    private final PdfExtractorPort pdfExtractor;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final double failureThreshold;

    public ExtractionWorkflow(
            DocumentRepository repository,
            BlobStoragePort blobStorage,
            PdfExtractorPort pdfExtractor,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${playground.docs.extraction.vision.retry.failure-threshold:0.2}") double failureThreshold) {
        this.repository = repository;
        this.blobStorage = blobStorage;
        this.pdfExtractor = pdfExtractor;
        this.events = events;
        this.clock = clock;
        this.failureThreshold = failureThreshold;
    }

    /**
     * Drive the full workflow for the supplied document id. Called by the
     * Kafka listener after dispatching off the consumer thread onto the
     * shared extraction executor.
     */
    public void run(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Document doc = loadOrSkip(documentId);
        if (doc == null) {
            return;
        }
        markExtracting(doc);

        Document withBody;
        try {
            withBody = extract(doc);
        } catch (BadRequestException e) {
            // PDF-too-many-pages / encrypted / corrupted — fail terminal.
            markFailed(doc.id(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.warn("Extraction crashed for document " + documentId + ": " + e);
            markFailed(doc.id(), "INTERNAL_ERROR: " + e.toString());
            return;
        }

        markExtracted(withBody);
    }

    /**
     * @return the document if it is still {@code PENDING_EXTRACTION} or has
     *         been redelivered while {@code EXTRACTING}, or {@code null} if
     *         the workflow should skip (already terminal).
     */
    @Transactional(readOnly = true)
    public Document loadOrSkip(UUID documentId) {
        Optional<Document> docOpt = repository.findById(com.playground.docs.domain.model.id.DocumentId.of(documentId));
        if (docOpt.isEmpty()) {
            log.warn("Extraction requested for missing document " + documentId);
            return null;
        }
        Document doc = docOpt.get();
        ExtractionStatus status = doc.extractionStatus();
        if (status == ExtractionStatus.EXTRACTED || status == ExtractionStatus.FAILED) {
            log.info("Skipping extraction for document " + documentId + " — already terminal (" + status + ")");
            return null;
        }
        if (doc.sourceObjectKey() == null) {
            log.warn("Extraction requested for document " + documentId + " with null source_object_key — marking failed");
            return null;
        }
        return doc;
    }

    @Transactional
    public void markExtracting(Document doc) {
        Document updated = doc.withExtraction(ExtractionStatus.EXTRACTING, null, null, Instant.now(clock));
        repository.save(updated);
        events.publishEvent(ExtractionStatusUpdate.of(updated.id(), ExtractionStatus.EXTRACTING));
    }

    @Transactional
    public void markFailed(DocumentId id, String reason) {
        Optional<Document> docOpt = repository.findById(id);
        if (docOpt.isEmpty()) {
            return;
        }
        Document updated = docOpt.get().withExtraction(ExtractionStatus.FAILED, reason, null, Instant.now(clock));
        repository.save(updated);
        events.publishEvent(ExtractionStatusUpdate.failed(updated.id(), reason));
    }

    @Transactional
    public void markExtracted(Document doc) {
        Document saved = repository.save(doc);
        events.publishEvent(ExtractionStatusUpdate.of(saved.id(), ExtractionStatus.EXTRACTED));
        // Publish DocumentUploaded so the in-BC ingestion listener picks up
        // the freshly-materialized body.
        events.publishEvent(new DocumentUploaded(
                saved.id(),
                saved.authorId(),
                saved.visibility(),
                saved.title(),
                saved.path(),
                saved.body().checksum(),
                Instant.now(clock)));
    }

    /**
     * MD path: bytes → UTF-8 markdown body.
     * PDF path: bytes → PdfExtractorPort → markdown body (degraded-allowed
     * unless failureRate exceeds the configured threshold).
     */
    private Document extract(Document doc) {
        String objectKey = doc.sourceObjectKey();
        Optional<BlobStoragePort.BlobHandle> handle = blobStorage.getObject(objectKey);
        if (handle.isEmpty()) {
            throw new DocumentNotFoundException(doc.id());
        }
        try (BlobStoragePort.BlobHandle blob = handle.get()) {
            String markdown;
            if (doc.mimeType() == MimeType.PDF) {
                markdown = extractPdf(doc.id(), blob.stream());
            } else {
                markdown = extractMarkdown(blob.stream());
            }
            DocumentBody body = DocumentBody.of(markdown == null ? "" : markdown);
            return doc.withExtraction(ExtractionStatus.EXTRACTED, null, body, Instant.now(clock));
        }
    }

    private String extractPdf(DocumentId documentId, InputStream pdfStream) {
        try {
            byte[] bytes;
            try (InputStream s = pdfStream) {
                bytes = s.readAllBytes();
            }
            PdfExtractorPort.Result result = pdfExtractor.extract(bytes);
            if (result.failureRate() > failureThreshold) {
                throw com.playground.shared.error.ExceptionCreator.of(DocsErrorCode.PDF_CORRUPTED).build();
            }
            log.info(String.format(
                    "PDF extraction complete for document %s: pages=%d failedPages=%d failureRate=%.2f",
                    documentId, result.totalPages(), result.failedPages(), result.failureRate()));
            return result.markdown();
        } catch (IOException e) {
            log.warn("MinIO read failed during PDF extraction for " + documentId + ": " + e);
            throw com.playground.shared.error.ExceptionCreator.of(DocsErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        }
    }

    private String extractMarkdown(InputStream stream) {
        try (InputStream s = stream) {
            // The multipart cap is 25 MB upstream — readAllBytes here is
            // bounded by the upload validation gate.
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw com.playground.shared.error.ExceptionCreator.of(DocsErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        }
    }
}
