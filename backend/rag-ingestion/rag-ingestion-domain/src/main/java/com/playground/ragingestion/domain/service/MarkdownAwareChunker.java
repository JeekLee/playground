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
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Top-level markdown-aware chunker per ADR-13 §1 (M3.1 amendment).
 * Composes {@link SectionBuilder} + {@link WindowNormalizer}; on CommonMark
 * parse exception falls back to the historical token-window algorithm
 * (single root section, no heading metadata).
 *
 * <p>Stateless after construction. The {@link SentenceSplitter} dependency
 * is forwarded into {@link WindowNormalizer}; the {@link Encoding} is
 * loaded once here for the fallback path.
 */
public final class MarkdownAwareChunker {

    private static final Logger log = Logger.getLogger(MarkdownAwareChunker.class.getName());

    private final ChunkingPolicy policy;
    private final SectionBuilder sectionBuilder;
    private final WindowNormalizer windowNormalizer;
    private final Encoding encoding;
    private final Function<String, List<Section>> parseHook;

    public MarkdownAwareChunker(ChunkingPolicy policy, SentenceSplitter sentenceSplitter) {
        this(policy, sentenceSplitter, null);
    }

    static MarkdownAwareChunker forTesting(
            ChunkingPolicy policy,
            SentenceSplitter sentenceSplitter,
            Function<String, List<Section>> parseHook) {
        return new MarkdownAwareChunker(policy, sentenceSplitter, parseHook);
    }

    private MarkdownAwareChunker(
            ChunkingPolicy policy,
            SentenceSplitter sentenceSplitter,
            Function<String, List<Section>> parseHook) {
        this.policy = policy;
        this.sectionBuilder = new SectionBuilder();
        this.windowNormalizer = new WindowNormalizer(policy, sentenceSplitter);
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        EncodingType type = switch (policy.tokenizer().toLowerCase()) {
            case "cl100k-base", "cl100k_base" -> EncodingType.CL100K_BASE;
            case "o200k-base", "o200k_base" -> EncodingType.O200K_BASE;
            default -> throw new IllegalArgumentException(
                    "Unknown JTokkit encoding: " + policy.tokenizer());
        };
        this.encoding = registry.getEncoding(type);
        this.parseHook = parseHook;
    }

    public ChunkingPolicy policy() {
        return policy;
    }

    public List<ChunkDraft> chunk(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        try {
            List<Section> sections = parseHook != null
                    ? parseHook.apply(body)
                    : sectionBuilder.build(body);
            return windowNormalizer.normalize(sections);
        } catch (RuntimeException ex) {
            log.log(Level.SEVERE,
                    "rag-ingestion: markdown-aware parse failed — falling back to token-window. cause=" + ex);
            return fallback(body);
        }
    }

    private List<ChunkDraft> fallback(String body) {
        IntArrayList tokens = encoding.encode(body);
        int total = tokens.size();
        if (total == 0) {
            return List.of();
        }
        int size = policy.sizeTokens();
        int stride = policy.stride();
        int minTokens = policy.minChunkTokens();
        List<ChunkDraft> drafts = new ArrayList<>();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + size, total);
            drafts.add(new ChunkDraft(ChunkText.of(decodeRange(tokens, start, end)), List.of()));
            if (end == total) break;
            start += stride;
        }
        if (drafts.size() >= 2) {
            int lastStart = (drafts.size() - 1) * stride;
            int lastLength = total - lastStart;
            if (lastLength < minTokens) {
                int prevStart = (drafts.size() - 2) * stride;
                drafts.remove(drafts.size() - 1);
                drafts.remove(drafts.size() - 1);
                drafts.add(new ChunkDraft(ChunkText.of(decodeRange(tokens, prevStart, total)), List.of()));
            }
        }
        return List.copyOf(drafts);
    }

    private String decodeRange(IntArrayList tokens, int fromInclusive, int toExclusive) {
        IntArrayList slice = new IntArrayList(toExclusive - fromInclusive);
        for (int i = fromInclusive; i < toExclusive; i++) slice.add(tokens.get(i));
        return encoding.decode(slice);
    }
}
