package com.playground.ragingestion.domain.service;

/**
 * Immutable chunking parameters per ADR-13 §1. Constructor takes the four
 * tunables; the {@link #DEFAULT} instance corresponds to the ADR-pinned
 * defaults (800-token windows, 120-token overlap, cl100k-base tokenizer,
 * min-chunk 64 tokens). The application layer reads these from
 * {@code application.yml} via a {@code @ConfigurationProperties} POJO and
 * hands them to the chunker as constructor args (no Spring import in
 * {@code -domain}).
 */
public record ChunkingPolicy(
        int sizeTokens,
        int overlapTokens,
        int minChunkTokens,
        String tokenizer
) {

    public static final ChunkingPolicy DEFAULT = new ChunkingPolicy(
            800,
            120,
            64,
            "cl100k-base");

    public ChunkingPolicy {
        if (sizeTokens <= 0) {
            throw new IllegalArgumentException("sizeTokens must be positive, got " + sizeTokens);
        }
        if (overlapTokens < 0) {
            throw new IllegalArgumentException("overlapTokens must be non-negative, got " + overlapTokens);
        }
        if (overlapTokens >= sizeTokens) {
            throw new IllegalArgumentException(
                    "overlapTokens (" + overlapTokens + ") must be strictly less than sizeTokens (" + sizeTokens + ")");
        }
        if (minChunkTokens <= 0 || minChunkTokens >= sizeTokens) {
            throw new IllegalArgumentException(
                    "minChunkTokens (" + minChunkTokens + ") must be in (0, sizeTokens)");
        }
        if (tokenizer == null || tokenizer.isBlank()) {
            throw new IllegalArgumentException("tokenizer must not be blank");
        }
    }

    /** Stride between consecutive chunk starts. */
    public int stride() {
        return sizeTokens - overlapTokens;
    }
}
