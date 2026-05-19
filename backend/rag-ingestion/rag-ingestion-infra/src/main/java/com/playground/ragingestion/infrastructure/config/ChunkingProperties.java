package com.playground.ragingestion.infrastructure.config;

import com.playground.ragingestion.domain.service.ChunkingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code playground.rag-ingestion.chunk.*} per ADR-13 §1.
 * The defaults below match the ADR-pinned numbers; an operator can override
 * any field via environment variable
 * ({@code PLAYGROUND_RAG_INGESTION_CHUNK_SIZE_TOKENS}, etc.) without rebuild.
 *
 * <p>Lives in {@code -infra} (not {@code -app}) because
 * {@link ConfigurationProperties} is a Spring Boot type and {@code -app} is
 * forbidden from importing {@code org.springframework.boot.*}. The
 * {@code ChunkingConfig} bean wires a {@link ChunkingPolicy} from these
 * values and the {@code MarkdownChunker} bean from the policy.
 */
@ConfigurationProperties(prefix = "playground.rag-ingestion.chunk")
public class ChunkingProperties {

    private int sizeTokens = 800;
    private int overlapTokens = 120;
    private int minChunkTokens = 64;
    private String tokenizer = "cl100k-base";

    public int getSizeTokens() {
        return sizeTokens;
    }

    public void setSizeTokens(int sizeTokens) {
        this.sizeTokens = sizeTokens;
    }

    public int getOverlapTokens() {
        return overlapTokens;
    }

    public void setOverlapTokens(int overlapTokens) {
        this.overlapTokens = overlapTokens;
    }

    public int getMinChunkTokens() {
        return minChunkTokens;
    }

    public void setMinChunkTokens(int minChunkTokens) {
        this.minChunkTokens = minChunkTokens;
    }

    public String getTokenizer() {
        return tokenizer;
    }

    public void setTokenizer(String tokenizer) {
        this.tokenizer = tokenizer;
    }

    public ChunkingPolicy toPolicy() {
        return new ChunkingPolicy(sizeTokens, overlapTokens, minChunkTokens, tokenizer, 800, true);
    }
}
