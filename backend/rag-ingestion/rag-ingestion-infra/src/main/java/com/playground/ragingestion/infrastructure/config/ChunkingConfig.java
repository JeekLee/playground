package com.playground.ragingestion.infrastructure.config;

import com.playground.ragingestion.domain.service.JdkBreakIteratorSentenceSplitter;
import com.playground.ragingestion.domain.service.MarkdownAwareChunker;
import com.playground.ragingestion.domain.service.SentenceSplitter;
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
            ChunkingProperties properties, SentenceSplitter sentenceSplitter) {
        return new MarkdownAwareChunker(properties.toPolicy(), sentenceSplitter);
    }
}
