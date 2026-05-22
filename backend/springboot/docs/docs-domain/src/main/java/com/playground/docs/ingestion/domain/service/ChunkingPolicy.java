package com.playground.docs.ingestion.domain.service;

/**
 * Immutable chunking parameters per ADR-13 §1 (M3.1 amendment). Six fields:
 * the four ADR-13 P0 tunables plus two M3.1 additions —
 * {@code maxOversizeFenceTokens} (line-split threshold for oversized fences /
 * tables) and {@code preserveHeadingPath} (toggle for the heading-aware
 * prefix; disable to fall back to plain block packing).
 *
 * <p>{@code overlapTokens} is dual-meaning: in the normal markdown-aware path
 * it caps the heading-prefix budget injected at chunk start; in the
 * parse-fallback path it serves the historical role of "token-window stride"
 * via {@link #stride()}. Spec §"Fallback 경로 한정".
 */
public record ChunkingPolicy(
        int sizeTokens,
        int overlapTokens,
        int minChunkTokens,
        String tokenizer,
        int maxOversizeFenceTokens,
        boolean preserveHeadingPath
) {

    public static final ChunkingPolicy DEFAULT = new ChunkingPolicy(
            800,
            120,
            64,
            "cl100k-base",
            800,
            true);

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
        if (maxOversizeFenceTokens <= 0) {
            throw new IllegalArgumentException(
                    "maxOversizeFenceTokens must be positive, got " + maxOversizeFenceTokens);
        }
    }

    /** Stride between consecutive chunk starts. */
    public int stride() {
        return sizeTokens - overlapTokens;
    }
}
