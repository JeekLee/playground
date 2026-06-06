package com.playground.chat.infrastructure.external;

import com.playground.chat.application.port.EmbeddingPort;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.shared.error.ExceptionCreator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * Spring AI-backed adapter for {@link EmbeddingPort} per ADR-04 + ADR-14 §1.
 * Wraps the {@link EmbeddingModel#embed(String)} call with the shared
 * {@code spark-gateway} Resilience4j breaker per ADR-14 §4 (decorated via
 * the breaker's synchronous {@code executeSupplier} since the call returns a
 * raw {@code float[]}).
 *
 * <p>The retry curve (3 attempts, 5xx + connect/read timeout retryable, 4xx
 * non-retryable, 400ms / 1.6s backoff with jitter) is supplied by the Spring
 * AI starter's retry advisor via {@code spring.ai.retry.*} in
 * {@code application.yml} — same shape M3 uses (ADR-13 §10).
 */
@Component
public class SparkInferenceEmbeddingAdapter implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(SparkInferenceEmbeddingAdapter.class);

    private final EmbeddingModel embeddingModel;
    private final CircuitBreaker breaker;

    public SparkInferenceEmbeddingAdapter(EmbeddingModel embeddingModel, CircuitBreaker sparkGatewayBreaker) {
        this.embeddingModel = embeddingModel;
        this.breaker = sparkGatewayBreaker;
    }

    @Override
    public float[] embedQuery(String query) {
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        try {
            return breaker.executeSupplier(() -> embeddingModel.embed(query));
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.warn("embed call rejected by circuit breaker: {}", e.getMessage());
            throw ExceptionCreator.of(ChatErrorCode.GATEWAY_DOWN, e.getMessage()).build();
        } catch (RuntimeException e) {
            log.warn("embed failed: {}", e.toString());
            throw ExceptionCreator.of(ChatErrorCode.EMBEDDING_FAILED, e.getMessage()).build();
        }
    }
}
