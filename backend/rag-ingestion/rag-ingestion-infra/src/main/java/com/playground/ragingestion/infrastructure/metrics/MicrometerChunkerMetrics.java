package com.playground.ragingestion.infrastructure.metrics;

import com.playground.ragingestion.domain.service.ChunkerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Micrometer-backed implementation of the {@link ChunkerMetrics} port per
 * ADR-13 §6 (M3.1 amendment). Registered as a Spring component so that
 * {@link com.playground.ragingestion.infrastructure.config.ChunkingConfig}
 * can inject it into the {@link com.playground.ragingestion.domain.service.MarkdownAwareChunker}
 * bean without the domain layer importing Micrometer.
 */
@Component
public class MicrometerChunkerMetrics implements ChunkerMetrics {

    private final MeterRegistry registry;

    public MicrometerChunkerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordDuration(Duration d, Outcome outcome) {
        Timer.builder("playground.rag_ingestion.chunker.duration")
                .tags("outcome", outcome.name().toLowerCase())
                .register(registry)
                .record(d);
    }

    @Override
    public void incOversizeFenceSplit() {
        registry.counter("playground.rag_ingestion.chunker.oversize_fence_split").increment();
    }

    @Override
    public void incOversizeSentenceFallback() {
        registry.counter("playground.rag_ingestion.chunker.oversize_sentence_fallback").increment();
    }

    @Override
    public void incParseFallback() {
        registry.counter("playground.rag_ingestion.chunker.parse_fallback").increment();
    }
}
