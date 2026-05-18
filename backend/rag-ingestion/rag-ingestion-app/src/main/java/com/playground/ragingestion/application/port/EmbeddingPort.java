package com.playground.ragingestion.application.port;

import com.playground.ragingestion.domain.model.vo.ChunkText;
import com.playground.ragingestion.domain.model.vo.Embedding;
import java.util.List;

/**
 * Outbound port to the embedding service (spark-inference-gateway BGE-M3 per
 * ADR-04 + ADR-13 §10). The implementation
 * ({@code SparkInferenceEmbeddingAdapter} in {@code rag-ingestion-infra})
 * wraps the OpenAI-compatible {@code /v1/embeddings} call with the retry curve
 * from ADR-13 §2.
 *
 * <p>The adapter is expected to batch up to 32 chunks per HTTP call (ADR-13
 * §2); callers may pass any list length. Documents producing fewer than 32
 * chunks ship in a single call.
 *
 * <p>On retry exhaustion + non-retryable 4xx the adapter throws a
 * {@link RuntimeException} subclass that the Kafka error handler routes to
 * the DLQ.
 */
public interface EmbeddingPort {

    /**
     * Embed the supplied chunk texts. The returned list is the same length and
     * in the same order as the input.
     */
    List<Embedding> embed(List<ChunkText> texts);
}
