package com.playground.ragingestion.domain.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.renderer.markdown.MarkdownRenderer;
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
    private final ChunkerMetrics metrics;
    private final Encoding encoding;
    private final TextContentRenderer textRenderer;
    private final MarkdownRenderer markdownRenderer;

    public WindowNormalizer(ChunkingPolicy policy, SentenceSplitter sentenceSplitter) {
        this(policy, sentenceSplitter, ChunkerMetrics.NOOP);
    }

    public WindowNormalizer(ChunkingPolicy policy, SentenceSplitter sentenceSplitter, ChunkerMetrics metrics) {
        this.policy = policy;
        this.sentenceSplitter = sentenceSplitter;
        this.metrics = metrics;
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        EncodingType type = switch (policy.tokenizer().toLowerCase()) {
            case "cl100k-base", "cl100k_base" -> EncodingType.CL100K_BASE;
            case "o200k-base", "o200k_base" -> EncodingType.O200K_BASE;
            default -> throw new IllegalArgumentException(
                    "Unknown JTokkit encoding: " + policy.tokenizer());
        };
        this.encoding = registry.getEncoding(type);
        var extensions = Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create());
        this.textRenderer = TextContentRenderer.builder()
                .extensions(extensions)
                .build();
        this.markdownRenderer = MarkdownRenderer.builder()
                .extensions(extensions)
                .build();
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
        int sectionStart = out.size();

        for (Node block : section.blocks()) {
            String rendered = renderBlock(block);
            int bt = tokenCount(rendered);

            if (bt > sizeTokens) {
                if (!buf.isEmpty()) {
                    out.add(buildDraft(section, buf));
                    buf.clear();
                    bufTokens = 0;
                }
                emitOversize(section, block, 0, out);
                continue;
            }
            if (bufTokens + bt > sizeTokens) {
                out.add(buildDraft(section, buf));
                buf.clear();
                bufTokens = 0;
            }
            buf.add(block);
            bufTokens += bt;
        }
        if (!buf.isEmpty()) {
            out.add(buildDraft(section, buf));
        }

        // Apply heading-aware prefix to chunks 2..N of this section.
        if (policy.preserveHeadingPath() && !section.headingPath().isEmpty()) {
            for (int i = sectionStart + 1; i < out.size(); i++) {
                ChunkDraft d = out.get(i);
                String prefix = renderHeadingPrefix(section.headingPath());
                String body = d.text().value();
                String prefixed = prefix + "\n\n" + body;
                out.set(i, new ChunkDraft(ChunkText.of(prefixed), section.headingPath()));
            }
        }

        // Trailing-merge: if last chunk in this section is shorter than
        // minChunkTokens AND another chunk in this section exists, merge it.
        if (out.size() - sectionStart >= 2) {
            ChunkDraft last = out.get(out.size() - 1);
            if (tokenCount(last.text().value()) < policy.minChunkTokens()) {
                ChunkDraft prev = out.get(out.size() - 2);
                String merged = prev.text().value() + "\n\n" + last.text().value();
                out.set(out.size() - 2, new ChunkDraft(ChunkText.of(merged), section.headingPath()));
                out.remove(out.size() - 1);
            }
        }
    }

    private ChunkDraft buildDraft(Section section, List<Node> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Node b : blocks) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(renderBlock(b));
        }
        return new ChunkDraft(ChunkText.of(sb.toString()), section.headingPath());
    }

    private String renderHeadingPrefix(List<String> headingPath) {
        int budget = policy.overlapTokens();
        // Start from the full path; drop top-level entries until the
        // rendered prefix fits the budget.
        int from = 0;
        while (from < headingPath.size()) {
            String candidate = "> Context: " + String.join(" > ", headingPath.subList(from, headingPath.size()));
            if (tokenCount(candidate) <= budget) {
                return candidate;
            }
            from++;
        }
        // All single-heading prefixes exceeded the budget — emit the deepest only.
        String deepest = headingPath.get(headingPath.size() - 1);
        return "> Context: " + deepest;
    }

    private void emitOversize(Section section, Node block, int sectionChunkIndex, List<ChunkDraft> out) {
        if (block instanceof FencedCodeBlock fcb) {
            splitOversizeFence(fcb, section, out);
        } else if (block instanceof TableBlock tb) {
            splitOversizeTable(tb, section, out);
        } else if (block instanceof Paragraph p) {
            splitOversizeParagraph(p, section, out);
        } else if (block instanceof BulletList || block instanceof OrderedList) {
            splitOversizeList(block, section, out);
        } else {
            // Generic fallback for BlockQuote / IndentedCodeBlock / unknown:
            // tokenize the rendered text and slide a fixed window.
            tokenWindowSlice(renderBlock(block), section, out);
        }
    }

    private void splitOversizeFence(FencedCodeBlock fcb, Section section, List<ChunkDraft> out) {
        String info = fcb.getInfo() == null ? "" : fcb.getInfo();
        String literal = fcb.getLiteral() == null ? "" : fcb.getLiteral();
        int total = tokenCount(literal);
        int maxFence = policy.maxOversizeFenceTokens();

        if (total <= maxFence) {
            // Atomic — emit as one chunk even if larger than sizeTokens.
            String text = "```" + info + "\n" + literal + "```";
            out.add(new ChunkDraft(ChunkText.of(text), section.headingPath()));
            return;
        }

        // Line-group split. Budget per chunk = sizeTokens minus the fence
        // markers' overhead (a handful of tokens).
        metrics.incOversizeFenceSplit();
        String[] lines = literal.split("\n", -1);
        int sz = policy.sizeTokens() - 16;  // budget for ```lang\n + \n```
        List<String> buf = new ArrayList<>();
        int bufTok = 0;
        for (String line : lines) {
            int lt = tokenCount(line + "\n");
            if (!buf.isEmpty() && bufTok + lt > sz) {
                flushFenceBuf(buf, info, section, out);
                buf.clear();
                bufTok = 0;
            }
            buf.add(line);
            bufTok += lt;
        }
        if (!buf.isEmpty()) flushFenceBuf(buf, info, section, out);
    }

    private void flushFenceBuf(List<String> lines, String info, Section section, List<ChunkDraft> out) {
        String body = String.join("\n", lines);
        String text = "```" + info + "\n" + body + "\n```";
        out.add(new ChunkDraft(ChunkText.of(text), section.headingPath()));
    }

    private void splitOversizeTable(TableBlock tb, Section section, List<ChunkDraft> out) {
        // Use manual renderer for splitting: it guarantees pipe-delimited rows
        // with space-padded cells ("| col1 | col2 |") regardless of how the
        // MarkdownRenderer formats them.
        String rendered = renderTableManually(tb);
        String[] lines = rendered.split("\n", -1);
        if (lines.length <= 2) {
            out.add(new ChunkDraft(ChunkText.of(rendered), section.headingPath()));
            return;
        }
        String header = lines[0] + "\n" + lines[1];
        int sz = policy.sizeTokens() - tokenCount(header + "\n");
        List<String> buf = new ArrayList<>();
        int bufTok = 0;
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int lt = tokenCount(line + "\n");
            if (!buf.isEmpty() && bufTok + lt > sz) {
                flushTableBuf(buf, header, section, out);
                buf.clear();
                bufTok = 0;
            }
            buf.add(line);
            bufTok += lt;
        }
        if (!buf.isEmpty()) flushTableBuf(buf, header, section, out);
    }

    private void flushTableBuf(List<String> rows, String header, Section section, List<ChunkDraft> out) {
        String text = header + "\n" + String.join("\n", rows);
        out.add(new ChunkDraft(ChunkText.of(text), section.headingPath()));
    }

    /**
     * Renders a {@link TableBlock} as GFM pipe-delimited Markdown.
     * Uses {@link MarkdownRenderer} with {@link TablesExtension} as the primary
     * path; falls back to manual cell walking if the rendered output is blank
     * (guard against renderer edge-cases).
     */
    private String renderTableAsMarkdown(TableBlock tb) {
        String rendered = markdownRenderer.render(tb).trim();
        if (!rendered.isBlank()) {
            return rendered;
        }
        // Manual fallback: walk TableHead / TableBody children.
        return renderTableManually(tb);
    }

    private String renderTableManually(TableBlock tb) {
        StringBuilder sb = new StringBuilder();
        for (Node child = tb.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableHead head) {
                String headerRow = renderTableRow(head.getFirstChild());
                sb.append(headerRow).append("\n");
                // Build separator row based on number of pipes
                long cols = headerRow.chars().filter(c -> c == '|').count() - 1;
                sb.append("|");
                for (long i = 0; i < cols; i++) sb.append("---|");
                sb.append("\n");
            } else if (child instanceof TableBody body) {
                for (Node row = body.getFirstChild(); row != null; row = row.getNext()) {
                    sb.append(renderTableRow(row)).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String renderTableRow(Node rowNode) {
        if (!(rowNode instanceof TableRow)) return "";
        StringBuilder sb = new StringBuilder("|");
        for (Node cell = rowNode.getFirstChild(); cell != null; cell = cell.getNext()) {
            if (cell instanceof TableCell) {
                // Render children of the cell (inline text, code, etc.) to avoid
                // picking up the pipe separator that TableTextContentNodeRenderer
                // appends when rendering the TableCell node itself.
                StringBuilder cellText = new StringBuilder();
                for (Node child = cell.getFirstChild(); child != null; child = child.getNext()) {
                    cellText.append(textRenderer.render(child).trim());
                }
                sb.append(" ").append(cellText).append(" |");
            }
        }
        return sb.toString();
    }

    private void splitOversizeParagraph(Paragraph p, Section section, List<ChunkDraft> out) {
        String text = textRenderer.render(p);
        List<String> sentences = sentenceSplitter.split(text, Locale.KOREAN);
        if (sentences.isEmpty()) {
            tokenWindowSlice(text, section, out);
            return;
        }

        int sz = policy.sizeTokens();
        List<String> buf = new ArrayList<>();
        int bufTok = 0;
        for (String sentence : sentences) {
            int st = tokenCount(sentence);
            if (st > sz) {
                if (!buf.isEmpty()) {
                    out.add(new ChunkDraft(ChunkText.of(String.join("", buf)), section.headingPath()));
                    buf.clear();
                    bufTok = 0;
                }
                metrics.incOversizeSentenceFallback();
                tokenWindowSlice(sentence, section, out);
                continue;
            }
            if (!buf.isEmpty() && bufTok + st > sz) {
                out.add(new ChunkDraft(ChunkText.of(String.join("", buf)), section.headingPath()));
                buf.clear();
                bufTok = 0;
            }
            buf.add(sentence);
            bufTok += st;
        }
        if (!buf.isEmpty()) {
            out.add(new ChunkDraft(ChunkText.of(String.join("", buf)), section.headingPath()));
        }
    }

    private void splitOversizeList(Node list, Section section, List<ChunkDraft> out) {
        int sz = policy.sizeTokens();
        List<String> buf = new ArrayList<>();
        int bufTok = 0;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            String rendered = textRenderer.render(item);
            int it = tokenCount(rendered);
            if (it > sz) {
                if (!buf.isEmpty()) {
                    out.add(new ChunkDraft(ChunkText.of(String.join("\n", buf)), section.headingPath()));
                    buf.clear();
                    bufTok = 0;
                }
                for (Node inner = item.getFirstChild(); inner != null; inner = inner.getNext()) {
                    if (tokenCount(renderBlock(inner)) > sz) {
                        emitOversize(section, inner, 0, out);
                    } else {
                        out.add(new ChunkDraft(ChunkText.of(renderBlock(inner)), section.headingPath()));
                    }
                }
                continue;
            }
            if (!buf.isEmpty() && bufTok + it > sz) {
                out.add(new ChunkDraft(ChunkText.of(String.join("\n", buf)), section.headingPath()));
                buf.clear();
                bufTok = 0;
            }
            buf.add(rendered);
            bufTok += it;
        }
        if (!buf.isEmpty()) {
            out.add(new ChunkDraft(ChunkText.of(String.join("\n", buf)), section.headingPath()));
        }
    }

    private void tokenWindowSlice(String text, Section section, List<ChunkDraft> out) {
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
        if (block instanceof TableBlock tb) {
            return renderTableAsMarkdown(tb);
        }
        // Paragraph / BulletList / OrderedList / BlockQuote
        return textRenderer.render(block);
    }

    int tokenCount(String text) {
        return encoding.encode(text).size();
    }
}
