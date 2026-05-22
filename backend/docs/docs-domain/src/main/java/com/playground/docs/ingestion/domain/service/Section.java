package com.playground.docs.ingestion.domain.service;

import java.util.List;
import java.util.Objects;
import org.commonmark.node.Node;

/**
 * One heading-bounded segment of a markdown document. Built by
 * {@link SectionBuilder} and consumed by {@link WindowNormalizer}.
 *
 * <p>{@code headingPath} is the sequence h1..hN where N is the depth of the
 * deepest heading in scope when this section started. Empty list = leading
 * content before any heading, or a document with no headings at all.
 *
 * <p>{@code blocks} contains the section's body in document order, including
 * the bounding {@code Heading} node as the first element (so embedding sees
 * the heading text). For the root pathless section the first block is
 * whatever paragraph / fence / list led the document.
 */
public record Section(List<String> headingPath, List<Node> blocks) {

    public Section {
        Objects.requireNonNull(headingPath, "Section.headingPath must not be null");
        Objects.requireNonNull(blocks, "Section.blocks must not be null");
        headingPath = List.copyOf(headingPath);
        // blocks intentionally not copied — Node is a mutable AST type and
        // re-parenting would corrupt the parser's invariants.
    }
}
