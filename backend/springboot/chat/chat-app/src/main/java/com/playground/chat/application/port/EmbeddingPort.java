package com.playground.chat.application.port;

/**
 * Outbound port for query embedding (BGE-M3 1024-dim per ADR-04 + ADR-14 §1).
 * The infra adapter wraps the Spring AI {@code EmbeddingModel} call with the
 * shared {@code spark-gateway} Resilience4j breaker per ADR-14 §4.
 *
 * <p>Synchronous blocking — the caller wraps the call in
 * {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}
 * per ADR-14 §17 so it doesn't block the request thread.
 */
public interface EmbeddingPort {

    /**
     * Embed a single query string. Returns a 1024-element float[] in BGE-M3
     * native order.
     */
    float[] embedQuery(String query);
}
