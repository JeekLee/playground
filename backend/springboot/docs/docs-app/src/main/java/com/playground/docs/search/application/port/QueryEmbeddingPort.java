package com.playground.docs.search.application.port;

/**
 * Outbound port for single-query embedding used by the {@code search_documents}
 * tool (agentic-search spec D1). Distinct from the ingestion {@code EmbeddingPort}
 * (batch chunk embedding) — search embeds exactly one query string per call.
 *
 * <p>The implementation
 * ({@code SparkInferenceEmbeddingAdapter} in docs-infra) reuses the ingestion
 * adapter's Spring AI {@code EmbeddingModel} via a batch-of-one call.
 */
public interface QueryEmbeddingPort {

    /** Embed the supplied search query into a single dense vector. */
    float[] embedQuery(String query);
}
