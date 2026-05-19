package com.playground.ragingestion.domain.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/**
 * Walks the top level of a CommonMark AST and groups blocks into
 * {@link Section}s separated by {@link Heading} nodes. GFM tables +
 * strikethrough are enabled.
 *
 * <p>Stateless after construction: the {@link Parser} instance is reused
 * across documents (commonmark-java parsers are thread-safe).
 */
public final class SectionBuilder {

    private final Parser parser;

    public SectionBuilder() {
        this.parser = Parser.builder()
                .extensions(Arrays.asList(
                        TablesExtension.create(),
                        StrikethroughExtension.create()))
                .build();
    }

    public List<Section> build(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        Node root = parser.parse(body);
        if (root.getFirstChild() == null) {
            return List.of();
        }

        List<Section> sections = new ArrayList<>();
        String[] stack = new String[6];

        List<String> currentPath = List.of();
        List<Node> currentBlocks = new ArrayList<>();

        for (Node child = root.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading h) {
                if (!currentBlocks.isEmpty()) {
                    sections.add(new Section(currentPath, currentBlocks));
                }
                int level = clamp(h.getLevel(), 1, 6);
                stack[level - 1] = headingText(h);
                for (int i = level; i < 6; i++) {
                    stack[i] = null;
                }
                currentPath = snapshotPath(stack, level);
                currentBlocks = new ArrayList<>();
                currentBlocks.add(h);
            } else {
                currentBlocks.add(child);
            }
        }
        if (!currentBlocks.isEmpty()) {
            sections.add(new Section(currentPath, currentBlocks));
        }
        return List.copyOf(sections);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String headingText(Heading h) {
        StringBuilder sb = new StringBuilder();
        for (Node n = h.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Text t) {
                sb.append(t.getLiteral());
            } else {
                for (Node inner = n.getFirstChild(); inner != null; inner = inner.getNext()) {
                    if (inner instanceof Text t) {
                        sb.append(t.getLiteral());
                    }
                }
            }
        }
        return sb.toString();
    }

    private static List<String> snapshotPath(String[] stack, int upToLevel) {
        List<String> out = new ArrayList<>(upToLevel);
        for (int i = 0; i < upToLevel; i++) {
            out.add(stack[i] == null ? "" : stack[i]);
        }
        return out;
    }
}
