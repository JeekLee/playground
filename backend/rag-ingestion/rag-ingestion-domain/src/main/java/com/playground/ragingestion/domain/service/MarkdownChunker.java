package com.playground.ragingestion.domain.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import java.util.ArrayList;
import java.util.List;

/**
 * Token-window markdown chunker per ADR-13 §1. Pure-Java algorithm — no
 * Spring, no JPA, no Kafka. Constructed once and reused across documents:
 * the JTokkit {@link Encoding} instance is thread-safe and the chunker keeps
 * no per-document state.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Tokenize the body via JTokkit's {@code cl100k-base} encoding.</li>
 *   <li>Slide a fixed window of {@link ChunkingPolicy#sizeTokens()} tokens
 *       with stride {@link ChunkingPolicy#stride()}; emit one
 *       {@link ChunkText} per window.</li>
 *   <li>If the trailing chunk is shorter than
 *       {@link ChunkingPolicy#minChunkTokens()}, merge it back into the
 *       previous chunk so the document never ends with a degenerate sliver.</li>
 * </ol>
 *
 * <p>The chunker treats the body as an opaque token stream — markdown fence
 * markers + heading prefixes are tokenized as their literal characters. A
 * markdown-aware chunker (fence-aware, heading-aware) is an M3.1 follow-up
 * per ADR-13 §1's "considered alternatives".
 *
 * <p>Empty bodies produce zero chunks. Bodies that produce a single chunk
 * (token count ≤ {@code sizeTokens}) emit exactly one element.
 */
public final class MarkdownChunker {

    private final ChunkingPolicy policy;
    private final Encoding encoding;

    public MarkdownChunker(ChunkingPolicy policy) {
        this.policy = policy;
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        // ADR-13 §1 pins cl100k-base; only the literal name is configurable. A
        // future model swap (e.g., o200k_base) lands as a new EncodingType
        // mapping in this switch.
        EncodingType type = switch (policy.tokenizer().toLowerCase()) {
            case "cl100k-base", "cl100k_base" -> EncodingType.CL100K_BASE;
            case "o200k-base", "o200k_base" -> EncodingType.O200K_BASE;
            default -> throw new IllegalArgumentException(
                    "Unknown JTokkit encoding: " + policy.tokenizer());
        };
        this.encoding = registry.getEncoding(type);
    }

    /** Convenience constructor for tests + the ADR-pinned defaults. */
    public MarkdownChunker() {
        this(ChunkingPolicy.DEFAULT);
    }

    public ChunkingPolicy policy() {
        return policy;
    }

    /**
     * Split the supplied body into chunks per the configured policy.
     * Returns an unmodifiable list; the empty body yields the empty list.
     */
    public List<ChunkText> chunk(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        IntArrayList tokens = encoding.encode(body);
        int total = tokens.size();
        if (total == 0) {
            return List.of();
        }
        int size = policy.sizeTokens();
        int stride = policy.stride();
        int minTokens = policy.minChunkTokens();
        List<ChunkText> chunks = new ArrayList<>();
        int start = 0;
        while (start < total) {
            int end = Math.min(start + size, total);
            chunks.add(decodeRange(tokens, start, end));
            if (end == total) {
                break;
            }
            start += stride;
        }
        // Merge a degenerate trailing chunk (shorter than minChunkTokens)
        // back into the previous one. The merge concatenates the *token*
        // ranges so the merged chunk has its tokens decoded once — preserving
        // boundary character correctness.
        if (chunks.size() >= 2) {
            int lastStart = (chunks.size() - 1) * stride;
            int lastEnd = total;
            int lastLength = lastEnd - lastStart;
            if (lastLength < minTokens) {
                int prevStart = (chunks.size() - 2) * stride;
                chunks.remove(chunks.size() - 1);
                chunks.remove(chunks.size() - 1);
                chunks.add(decodeRange(tokens, prevStart, total));
            }
        }
        return List.copyOf(chunks);
    }

    private ChunkText decodeRange(IntArrayList tokens, int fromInclusive, int toExclusive) {
        IntArrayList slice = new IntArrayList(toExclusive - fromInclusive);
        for (int i = fromInclusive; i < toExclusive; i++) {
            slice.add(tokens.get(i));
        }
        return ChunkText.of(encoding.decode(slice));
    }
}
