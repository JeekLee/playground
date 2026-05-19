package com.playground.ragingestion.domain.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * Turns a {@link Section} list into a {@link ChunkDraft} list per ADR-13
 * §1 (M3.1 amendment). Section boundaries are never crossed (each section
 * is ≥ 1 chunk); within a section blocks are packed greedily up to
 * {@link ChunkingPolicy#sizeTokens()} with fence / table / oversize-paragraph
 * special cases.
 *
 * <p>Block-to-text rendering uses commonmark-java's {@link TextContentRenderer}
 * for paragraphs / lists / blockquotes and source-text reconstruction for
 * fenced code (so the ``` ``` markers and language tag round-trip). Heading
 * nodes are rendered with their level markers preserved so chunk text
 * re-parses as a valid heading and the embedding sees the heading shape.
 */
public final class WindowNormalizer {

    private final ChunkingPolicy policy;
    private final SentenceSplitter sentenceSplitter;
    private final Encoding encoding;
    private final TextContentRenderer textRenderer;

    public WindowNormalizer(ChunkingPolicy policy, SentenceSplitter sentenceSplitter) {
        this.policy = policy;
        this.sentenceSplitter = sentenceSplitter;
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        EncodingType type = switch (policy.tokenizer().toLowerCase()) {
            case "cl100k-base", "cl100k_base" -> EncodingType.CL100K_BASE;
            case "o200k-base", "o200k_base" -> EncodingType.O200K_BASE;
            default -> throw new IllegalArgumentException(
                    "Unknown JTokkit encoding: " + policy.tokenizer());
        };
        this.encoding = registry.getEncoding(type);
        this.textRenderer = TextContentRenderer.builder().build();
    }

    public List<ChunkDraft> normalize(List<Section> sections) {
        if (sections.isEmpty()) {
            return List.of();
        }
        List<ChunkDraft> out = new ArrayList<>();
        for (Section section : sections) {
            normalizeOne(section, out);
        }
        return List.copyOf(out);
    }

    private void normalizeOne(Section section, List<ChunkDraft> out) {
        int sizeTokens = policy.sizeTokens();
        List<Node> buf = new ArrayList<>();
        int bufTokens = 0;
        int sectionChunkIndex = 0;

        for (Node block : section.blocks()) {
            String rendered = renderBlock(block);
            int bt = tokenCount(rendered);

            if (bt > sizeTokens) {
                if (!buf.isEmpty()) {
                    out.add(buildDraft(section, buf, sectionChunkIndex++));
                    buf.clear();
                    bufTokens = 0;
                }
                emitOversize(section, block, sectionChunkIndex, out);
                sectionChunkIndex = out.size() - countSectionChunks(out, section);
                continue;
            }
            if (bufTokens + bt > sizeTokens) {
                out.add(buildDraft(section, buf, sectionChunkIndex++));
                buf.clear();
                bufTokens = 0;
            }
            buf.add(block);
            bufTokens += bt;
        }
        if (!buf.isEmpty()) {
            out.add(buildDraft(section, buf, sectionChunkIndex++));
        }
    }

    private int countSectionChunks(List<ChunkDraft> out, Section section) {
        int n = 0;
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i).headingPath().equals(section.headingPath())) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    private ChunkDraft buildDraft(Section section, List<Node> blocks, int sectionChunkIndex) {
        StringBuilder sb = new StringBuilder();
        for (Node b : blocks) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(renderBlock(b));
        }
        // Heading-aware prefix is added in Task 6c. For now leave the body as-is.
        String text = sb.toString();
        return new ChunkDraft(ChunkText.of(text), section.headingPath());
    }

    // Stub — completed in Task 6b.
    private void emitOversize(Section section, Node block, int sectionChunkIndex, List<ChunkDraft> out) {
        // Fallback: tokenize the block's text and slide a fixed window. Task 6b
        // replaces this with per-block-type handling.
        String text = renderBlock(block);
        var tokens = encoding.encode(text);
        int total = tokens.size();
        int sz = policy.sizeTokens();
        int stride = policy.stride();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + sz, total);
            var slice = new IntArrayList(end - start);
            for (int i = start; i < end; i++) slice.add(tokens.get(i));
            out.add(new ChunkDraft(ChunkText.of(encoding.decode(slice)), section.headingPath()));
            if (end == total) break;
            start += stride;
        }
    }

    String renderBlock(Node block) {
        if (block instanceof FencedCodeBlock fcb) {
            String info = fcb.getInfo() == null ? "" : fcb.getInfo();
            String literal = fcb.getLiteral() == null ? "" : fcb.getLiteral();
            return "```" + info + "\n" + literal + "```";
        }
        if (block instanceof IndentedCodeBlock icb) {
            String literal = icb.getLiteral() == null ? "" : icb.getLiteral();
            StringBuilder sb = new StringBuilder();
            for (String line : literal.split("\n", -1)) {
                sb.append("    ").append(line).append("\n");
            }
            return sb.toString();
        }
        if (block instanceof org.commonmark.node.Heading h) {
            // Preserve the markdown markers so the chunk text re-parses as a
            // heading (and so the embedding model sees the heading shape).
            String text = textRenderer.render(h);
            return "#".repeat(Math.max(1, Math.min(6, h.getLevel()))) + " " + text;
        }
        // Paragraph / BulletList / OrderedList / BlockQuote / TableBlock
        return textRenderer.render(block);
    }

    int tokenCount(String text) {
        return encoding.encode(text).size();
    }

    @SuppressWarnings("unused")
    private void splitOversizeParagraph(Paragraph p, Section section, List<ChunkDraft> out) {
        // implemented in Task 6b
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    private void splitOversizeFence(FencedCodeBlock fcb, Section section, List<ChunkDraft> out) {
        // implemented in Task 6b
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    private void splitOversizeTable(TableBlock tb, Section section, List<ChunkDraft> out) {
        // implemented in Task 6b
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unused")
    private void splitOversizeList(Node list, Section section, List<ChunkDraft> out) {
        // implemented in Task 6b
        throw new UnsupportedOperationException();
    }
}
