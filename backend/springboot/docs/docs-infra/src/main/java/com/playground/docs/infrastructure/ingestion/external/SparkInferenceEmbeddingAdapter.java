package com.playground.docs.infrastructure.ingestion.external;

import com.playground.docs.ingestion.application.port.EmbeddingPort;
import com.playground.docs.ingestion.domain.exception.RagIngestionErrorCode;
import com.playground.docs.ingestion.domain.model.vo.ChunkText;
import com.playground.docs.ingestion.domain.model.vo.Embedding;
import com.playground.shared.error.ExceptionCreator;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

/**
 * Spring AI-backed adapter for {@link EmbeddingPort} per ADR-04 + ADR-13 §10.
 * The underlying {@link EmbeddingModel} is wired by
 * {@code spring-ai-openai-spring-boot-starter} against the
 * spark-inference-gateway's OpenAI-compatible {@code /v1/embeddings} endpoint
 * (base URL = {@code SPRING_AI_OPENAI_BASE_URL}; model = {@code BGE-M3} per
 * {@code application.yml}).
 *
 * <p>The retry curve (3 attempts, 5xx + connect/read timeout retryable, 4xx
 * non-retryable, 400 ms / 1.6 s backoff with jitter 0.5) per ADR-13 §2 is
 * applied by the Spring AI starter's built-in retry advisor when configured
 * via {@code spring.ai.retry.*} (see {@code application.yml}). Errors that
 * exhaust the retry budget propagate as runtime exceptions; the Kafka error
 * handler routes the source record to the DLQ.
 *
 * <p>Batching: the adapter passes the full list of chunk texts to
 * {@code EmbeddingModel.embed} in a single call. The OpenAI-compatible
 * endpoint batches under the hood; for chunk counts > 32 the caller's
 * documents are still small in practice (max ~250 chunks for a 1 MB body),
 * so we do not split into N batches here. If gateway-side batch caps become
 * an issue, ADR-13 §2 calls for 32-per-batch — a follow-up adapter wraps
 * the call.
 */
@Component
public class SparkInferenceEmbeddingAdapter implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(SparkInferenceEmbeddingAdapter.class);

    /** Max texts per HTTP call per ADR-13 §2; documents larger than this batch in slices. */
    private static final int MAX_BATCH = 32;

    private final EmbeddingModel embeddingModel;

    public SparkInferenceEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<Embedding> embed(List<ChunkText> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<Embedding> all = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_BATCH) {
            int to = Math.min(from + MAX_BATCH, texts.size());
            List<String> batch = texts.subList(from, to).stream()
                    .map(ChunkText::value)
                    .toList();
            try {
                EmbeddingResponse response = embeddingModel.embedForResponse(batch);
                if (response.getResults().size() != batch.size()) {
                    throw new IllegalStateException(
                            "Embedding model returned " + response.getResults().size()
                                    + " vectors for " + batch.size() + " inputs");
                }
                for (int i = 0; i < response.getResults().size(); i++) {
                    float[] vector = response.getResults().get(i).getOutput();
                    all.add(Embedding.of(vector));
                }
            } catch (RuntimeException e) {
                log.warn(
                        "spark-inference-gateway embed failed batch={}-{} size={}",
                        from, to, batch.size(), e);
                throw ExceptionCreator
                        .of(RagIngestionErrorCode.EMBEDDING_GATEWAY_UNAVAILABLE, e.getMessage())
                        .build();
            }
        }
        return all;
    }
}
