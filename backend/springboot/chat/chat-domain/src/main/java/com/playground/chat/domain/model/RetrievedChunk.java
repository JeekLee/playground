package com.playground.chat.domain.model;

import com.playground.shared.chat.SourceRef;
import java.util.Objects;

/**
 * One search hit accumulated during a turn (SP3b spec D3): the turn-global
 * {@code [N]} position the LLM is told to cite, plus the corpus-agnostic
 * {@link SourceRef} the search tool emitted.
 *
 * <p>The {@code position} is the 1-indexed slot assigned by the turn
 * accumulator so the citation event + the {@code [N]} marker extraction match
 * indices. All source payload (sourceType/title/content/uri) lives in
 * {@code source} — chat copies it blind, never interpreting corpus-specific
 * fields like documentId/chunkIndex.
 */
public record RetrievedChunk(int position, SourceRef source) {

    public RetrievedChunk {
        if (position < 1) {
            throw new IllegalArgumentException("position must be >= 1, got " + position);
        }
        Objects.requireNonNull(source, "source");
    }
}
