package com.playground.ragingestion.application.port;

import com.playground.ragingestion.application.dto.DocumentBody;
import com.playground.ragingestion.domain.model.id.DocumentId;

/**
 * Outbound port to docs-api's {@code GET /internal/docs/{id}/body} route per
 * ADR-08 Exception 1 + ADR-12 §2 + ADR-13 §11. The implementation
 * ({@code DocsBodyFetchAdapter} in {@code rag-ingestion-infra}) wraps the
 * WebClient call with the retry curve from ADR-13 §2 (body-fetch column):
 * 5 s timeout, 3 attempts, exponential backoff. 4xx (404, 413, 400) is
 * non-retryable and surfaces as an exception the Kafka error handler routes
 * to the DLQ.
 *
 * <p>The buffered read pulls the full body into memory (capped at 2 MB by the
 * adapter's WebClient {@code maxInMemorySize}) per ADR-13 §11.
 */
public interface BodyFetchPort {

    DocumentBody fetchBody(DocumentId documentId);
}
