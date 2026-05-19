package com.playground.ragingestion.domain.model.vo;

import java.util.List;
import java.util.Objects;

/**
 * In-flight chunker output: a chunk's raw text plus the heading breadcrumb
 * (h1, h2, h3, ...) of the section it came from. Carried from
 * {@code MarkdownAwareChunker} through {@code IngestionService} so the
 * embedding step and {@code DocumentChunk} construction can stay zero-copy
 * — neither needs to re-derive {@code headingPath} from the AST.
 *
 * <p>{@code headingPath} is never null and is held as an immutable copy.
 * Empty list = chunk belongs to a section above the first heading (or the
 * document has no headings at all).
 */
public record ChunkDraft(ChunkText text, List<String> headingPath) {

    public ChunkDraft {
        Objects.requireNonNull(text, "ChunkDraft.text must not be null");
        Objects.requireNonNull(headingPath, "ChunkDraft.headingPath must not be null");
        headingPath = List.copyOf(headingPath);
    }
}
