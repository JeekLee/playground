package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragingestion.domain.model.vo.ChunkText;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    @Test
    void empty_body_produces_no_chunks() {
        MarkdownChunker chunker = new MarkdownChunker();
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void small_body_produces_one_chunk_containing_the_full_text() {
        MarkdownChunker chunker = new MarkdownChunker();
        String body = "Hello playground. This is a tiny markdown body.";
        List<ChunkText> chunks = chunker.chunk(body);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).value()).isEqualTo(body);
    }

    @Test
    void large_body_produces_multiple_overlapping_chunks_with_default_policy() {
        // Build a body large enough to require multiple 800-token windows.
        // English words tokenize to ~1 token each in cl100k_base; repeat a
        // sentence 600 times to exceed 800 tokens by a wide margin.
        String sentence = "The quick brown fox jumps over the lazy dog. ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append(sentence);
        }
        MarkdownChunker chunker = new MarkdownChunker();
        List<ChunkText> chunks = chunker.chunk(sb.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        // Each chunk (except maybe the merged trailing one) covers ~800
        // tokens. Spot-check: every chunk has non-empty text.
        chunks.forEach(c -> assertThat(c.value()).isNotEmpty());
    }

    @Test
    void overlap_is_observable_in_consecutive_chunks() {
        // 800-token window + 120-token overlap means consecutive chunks
        // share a common substring of ~120 tokens. The exact text overlap
        // depends on the cl100k tokenizer's word boundaries, but the
        // substring is non-trivial.
        String sentence = "Markdown chunker preserves context across boundaries. ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append(sentence);
        }
        MarkdownChunker chunker = new MarkdownChunker();
        List<ChunkText> chunks = chunker.chunk(sb.toString());

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        String first = chunks.get(0).value();
        String second = chunks.get(1).value();
        // The first chunk's tail must appear in the second chunk's head —
        // overlap guarantees this. We take the last 40 chars of chunk 1
        // and assert it appears in chunk 2 (40 chars is well under the
        // 120-token overlap budget).
        String tail = first.substring(Math.max(0, first.length() - 40));
        assertThat(second).contains(tail);
    }

    @Test
    void custom_policy_overrides_size_and_overlap() {
        ChunkingPolicy small = new ChunkingPolicy(20, 4, 4, "cl100k-base", 20, true);
        MarkdownChunker chunker = new MarkdownChunker(small);
        String body = "alpha beta gamma delta epsilon zeta eta theta iota kappa "
                + "lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega.";
        List<ChunkText> chunks = chunker.chunk(body);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunker.policy().sizeTokens()).isEqualTo(20);
        assertThat(chunker.policy().overlapTokens()).isEqualTo(4);
    }

    @Test
    void deterministic_chunking_for_the_same_input() {
        MarkdownChunker chunker = new MarkdownChunker();
        String body = "Deterministic test body. ".repeat(300);

        List<ChunkText> a = chunker.chunk(body);
        List<ChunkText> b = chunker.chunk(body);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void chunking_policy_rejects_invalid_inputs() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new ChunkingPolicy(0, 0, 0, "cl100k-base", 800, true))
                .isInstanceOf(IllegalArgumentException.class);
        // overlapTokens == sizeTokens — invalid per the policy invariant.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new ChunkingPolicy(800, 800, 64, "cl100k-base", 800, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
        // minChunkTokens must be strictly less than sizeTokens.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new ChunkingPolicy(800, 120, 800, "cl100k-base", 800, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minChunkTokens");
        // Blank tokenizer.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new ChunkingPolicy(800, 120, 64, "  ", 800, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenizer");
    }

    @Test
    void unknown_tokenizer_fails_at_chunker_construction() {
        ChunkingPolicy unknown = new ChunkingPolicy(800, 120, 64, "no-such-tokenizer", 800, true);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new MarkdownChunker(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-tokenizer");
    }
}
