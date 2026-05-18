package com.playground.ragingestion.domain.model.vo;

import java.util.Objects;

/**
 * Raw text of a single chunk produced by {@code MarkdownChunker} (ADR-13 §1).
 * Held verbatim through embedding + persistence so M4's citation surfaces can
 * render the exact span the embedding scored.
 *
 * <p>Allowed to be empty in principle but the chunker only emits chunks with
 * at least one token; an empty value is treated as a domain invariant violation
 * upstream.
 */
public record ChunkText(String value) {

    public ChunkText {
        Objects.requireNonNull(value, "ChunkText.value must not be null");
    }

    public static ChunkText of(String value) {
        return new ChunkText(value);
    }

    public int length() {
        return value.length();
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public String toString() {
        return value;
    }
}
