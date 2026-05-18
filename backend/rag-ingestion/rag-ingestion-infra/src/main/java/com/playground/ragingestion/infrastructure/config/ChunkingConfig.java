package com.playground.ragingestion.infrastructure.config;

import com.playground.ragingestion.domain.service.MarkdownChunker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the chunking-properties binding (resolved from {@code application.yml}
 * + env vars) into a singleton {@link MarkdownChunker} bean per ADR-13 §1.
 * The chunker is stateless once constructed; reuse is safe across threads.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChunkingProperties.class)
public class ChunkingConfig {

    @Bean
    public MarkdownChunker markdownChunker(ChunkingProperties properties) {
        return new MarkdownChunker(properties.toPolicy());
    }
}
