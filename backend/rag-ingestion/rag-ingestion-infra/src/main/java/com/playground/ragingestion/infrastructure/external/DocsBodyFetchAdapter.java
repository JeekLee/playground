package com.playground.ragingestion.infrastructure.external;

import com.playground.ragingestion.application.dto.DocumentBody;
import com.playground.ragingestion.application.port.BodyFetchPort;
import com.playground.ragingestion.domain.exception.RagIngestionErrorCode;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import com.playground.shared.error.ExceptionCreator;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

/**
 * Calls docs-api's {@code GET /internal/docs/{id}/body} per ADR-08 Exception
 * 1 + ADR-12 §2 + ADR-13 §2 + §11. The retry curve mirrors ADR-13 §2's
 * body-fetch column:
 *
 * <ul>
 *   <li>WebClient response timeout: 5 s (set in {@link DocsApiClientConfig})</li>
 *   <li>Max attempts: 3 (one initial + 2 retries)</li>
 *   <li>Backoff: 200 ms base, multiplier 2, jitter 0.5, max 800 ms</li>
 *   <li>Retryable: 5xx, connect timeout, read timeout</li>
 *   <li>Non-retryable: 404, 400, 413 — propagated immediately, the consumer
 *       error handler routes the source record to the DLQ.</li>
 * </ul>
 *
 * <p>The full body is buffered into memory (ADR-13 §11); the
 * {@code maxInMemorySize} on the WebClient ({@link DocsApiClientConfig}) is
 * 2 MB — 2× the 1 MB body cap (ADR-12 §4) for envelope overhead. Responses
 * exceeding 2 MB throw {@code DataBufferLimitException}, routed to DLQ.
 */
@Component
public class DocsBodyFetchAdapter implements BodyFetchPort {

    private static final Logger log = LoggerFactory.getLogger(DocsBodyFetchAdapter.class);

    private final WebClient docsWebClient;

    public DocsBodyFetchAdapter(@Qualifier("docsWebClient") WebClient docsWebClient) {
        this.docsWebClient = docsWebClient;
    }

    @Override
    public DocumentBody fetchBody(DocumentId documentId) {
        try {
            DocumentBodyResponse response = docsWebClient.get()
                    .uri("/internal/docs/{id}/body", documentId.value())
                    .retrieve()
                    .onStatus(this::isNonRetryable4xx, clientResponse ->
                            clientResponse.createException()
                                    .map(ex -> nonRetryable(documentId, clientResponse.statusCode(), ex)))
                    .bodyToMono(DocumentBodyResponse.class)
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(200))
                            .maxBackoff(Duration.ofMillis(800))
                            .jitter(0.5)
                            .filter(this::isRetryable))
                    .blockOptional(Duration.ofSeconds(15))
                    .orElseThrow(() -> ExceptionCreator
                            .of(RagIngestionErrorCode.DOCS_BODY_FETCH_FAILED, documentId.toString())
                            .build());
            return new DocumentBody(
                    documentId,
                    response.body() == null ? "" : response.body(),
                    BodyChecksum.of(response.bodyChecksum()),
                    response.updatedAt());
        } catch (NonRetryable4xxException e) {
            // Rethrow unwrapped so the Kafka error handler can read the
            // status off the original RuntimeException without unwrapping
            // Reactor's wrapper.
            log.warn(
                    "docs body-fetch non-retryable {} for document={} — DLQ-bound",
                    e.status, documentId, e);
            // 404 → NotFoundException so ReembedService can catch it as SKIPPED
            // without routing to the DLQ.
            if (e.status.value() == 404) {
                throw ExceptionCreator
                        .of(RagIngestionErrorCode.DOCUMENT_NOT_FOUND, documentId.toString())
                        .build();
            }
            throw ExceptionCreator
                    .of(e.status.value() == 413
                            ? RagIngestionErrorCode.BODY_TOO_LARGE
                            : RagIngestionErrorCode.DOCS_BODY_FETCH_FAILED,
                            documentId.toString())
                    .build();
        } catch (WebClientResponseException e) {
            // 5xx that exhausted retries.
            log.warn("docs body-fetch failed for document={} status={}", documentId, e.getStatusCode(), e);
            throw ExceptionCreator
                    .of(RagIngestionErrorCode.DOCS_BODY_FETCH_FAILED, documentId.toString())
                    .build();
        } catch (RuntimeException e) {
            log.warn("docs body-fetch failed for document={}", documentId, e);
            throw ExceptionCreator
                    .of(RagIngestionErrorCode.DOCS_BODY_FETCH_FAILED, documentId.toString())
                    .build();
        }
    }

    private boolean isNonRetryable4xx(HttpStatusCode status) {
        // 404 (doc gone), 400 (bad UUID — unreachable in practice), 413
        // (body too large): never retry per ADR-13 §2.
        return status.value() == 404 || status.value() == 400 || status.value() == 413;
    }

    private RuntimeException nonRetryable(
            DocumentId documentId, HttpStatusCode status, Throwable upstream) {
        return new NonRetryable4xxException(status, "docs body-fetch " + status + " for " + documentId, upstream);
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof NonRetryable4xxException) {
            return false;
        }
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError();
        }
        // IOExceptions / connect / read timeouts: retry.
        return true;
    }

    /**
     * Internal marker so the error mapping path can distinguish "this 4xx
     * surfaced in the {@code onStatus} hook" from a generic 4xx that snuck
     * past — preventing accidental retries through the Reactor pipeline.
     */
    private static final class NonRetryable4xxException extends RuntimeException {
        final HttpStatusCode status;

        NonRetryable4xxException(HttpStatusCode status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }
    }

    /** Wire-shape mirror of docs-api's {@code DocumentBodyResponse} (ADR-12 §2). */
    public record DocumentBodyResponse(
            String id,
            String body,
            String bodyChecksum,
            Instant updatedAt) {}
}
