package com.playground.docs.infrastructure.ingestion.config;

import com.playground.docs.ingestion.domain.service.ChunkerMetrics;
import com.playground.docs.ingestion.domain.service.JdkBreakIteratorSentenceSplitter;
import com.playground.docs.ingestion.domain.service.MarkdownAwareChunker;
import com.playground.docs.ingestion.domain.service.SentenceSplitter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link ChunkingProperties} (typed bindings from {@code application.yml}
 * + env vars) into a singleton {@link MarkdownAwareChunker} bean per ADR-13
 * §1 (M3.1 amendment). The chunker is stateless once constructed; reuse is
 * safe across threads.
 */
@Configuration
@EnableConfigurationProperties(ChunkingProperties.class)
public class ChunkingConfig {

    @Bean
    public SentenceSplitter sentenceSplitter() {
        return new JdkBreakIteratorSentenceSplitter();
    }

    @Bean
    public MarkdownAwareChunker markdownAwareChunker(
            ChunkingProperties properties,
            SentenceSplitter sentenceSplitter,
            ChunkerMetrics chunkerMetrics) {
        return new MarkdownAwareChunker(properties.toPolicy(), sentenceSplitter, chunkerMetrics);
    }
}
