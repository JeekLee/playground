package com.playground.docs.ingestion.domain.service;

import java.time.Duration;

/**
 * Metric sink for chunker hot paths per ADR-13 §6 (M3.1 amendment). Lives in
 * {@code -domain} as a port so the algorithm doesn't depend on Micrometer.
 * The Micrometer-backed adapter is wired by Spring in {@code -infra}.
 */
public interface ChunkerMetrics {

    void recordDuration(Duration d, Outcome outcome);

    void incOversizeFenceSplit();

    void incOversizeSentenceFallback();

    void incParseFallback();

    enum Outcome { SUCCESS, PARSE_FALLBACK }

    /** No-op implementation for unit tests + the non-metric default ctor path. */
    ChunkerMetrics NOOP = new ChunkerMetrics() {
        public void recordDuration(Duration d, Outcome o) {}
        public void incOversizeFenceSplit() {}
        public void incOversizeSentenceFallback() {}
        public void incParseFallback() {}
    };
}
