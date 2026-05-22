package com.playground.docs.ingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.docs.ingestion.domain.model.vo.ChunkDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowNormalizerTest {

    private final SectionBuilder builder = new SectionBuilder();
    private final WindowNormalizer normalizer = new WindowNormalizer(
            ChunkingPolicy.DEFAULT, new JdkBreakIteratorSentenceSplitter());

    @Test
    void single_small_section_yields_one_chunk_with_full_heading_path() {
        String md = "# Title\n\nShort body.";
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).headingPath()).containsExactly("Title");
        assertThat(drafts.get(0).text().value()).contains("Short body.");
        assertThat(drafts.get(0).text().value()).contains("Title");
    }

    @Test
    void no_section_input_yields_empty_output() {
        assertThat(normalizer.normalize(List.of())).isEmpty();
    }

    @Test
    void multiple_small_sections_each_become_own_chunk_no_cross_section_pack() {
        String md = """
                # A
                a body
                # B
                b body
                # C
                c body
                """;
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));

        assertThat(drafts).hasSize(3);
        assertThat(drafts.get(0).headingPath()).containsExactly("A");
        assertThat(drafts.get(1).headingPath()).containsExactly("B");
        assertThat(drafts.get(2).headingPath()).containsExactly("C");
    }

    @Test
    void section_with_many_paragraphs_packs_until_size_then_flushes() {
        StringBuilder md = new StringBuilder("# Big section\n\n");
        String para = "The quick brown fox jumps over the lazy dog. ".repeat(40);
        for (int i = 0; i < 7; i++) {
            md.append(para).append("\n\n");
        }
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));

        assertThat(drafts).hasSizeBetween(3, 4);
        drafts.forEach(d -> {
            assertThat(d.headingPath()).containsExactly("Big section");
            assertThat(d.text().value()).isNotEmpty();
        });
    }

    @Test
    void oversize_fence_stays_atomic_when_within_maxOversizeFenceTokens() {
        // sizeTokens=800, maxOversizeFenceTokens=800 (default).
        // A 700-token fence is bigger than typical section pack but ≤ max,
        // so it lands as one chunk.
        String content = "x ".repeat(700);  // ~700 tokens
        String md = "# H\n\n```\n" + content + "\n```\n";
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).text().value()).contains("```");
    }

    @Test
    void oversize_fence_beyond_threshold_splits_into_valid_fences() {
        // Fence has ~1600 tokens — exceeds maxOversizeFenceTokens (800).
        // Each split chunk must re-open with ``` and the original language.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 800; i++) body.append("line").append(i).append("\n");
        String md = "# Code\n\n```python\n" + body + "```\n";
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));
        assertThat(drafts).hasSizeGreaterThan(1);
        drafts.subList(1, drafts.size()).forEach(d -> {
            assertThat(d.text().value()).contains("```python");
            assertThat(d.text().value().trim()).endsWith("```");
        });
    }

    @Test
    void oversize_paragraph_splits_on_sentences() {
        // One paragraph, no sentence > sizeTokens.
        String sent = "이것은 한 문장입니다. ";
        StringBuilder big = new StringBuilder("# P\n\n");
        for (int i = 0; i < 200; i++) big.append(sent);
        big.append("\n");
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(big.toString()));
        assertThat(drafts).hasSizeGreaterThan(1);
        // Chunks that contain sentence content (not heading-only) must end on a
        // sentence boundary (terminator char). The heading chunk "# P" is excluded.
        drafts.stream()
                .filter(d -> d.text().value().contains("이것은"))
                .forEach(d -> {
                    String t = d.text().value().trim();
                    // Use DOTALL so heading-prefix newlines don't break the match.
                    assertThat(t).matches("(?s).*[.!?]\\s*");
                });
    }

    @Test
    void single_sentence_larger_than_sizeTokens_falls_back_to_token_window() {
        // Pathological: one sentence ~1600 tokens with no terminator until end.
        String giant = "word ".repeat(1600) + ".";
        String md = "# G\n\n" + giant;
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md));
        assertThat(drafts).hasSizeGreaterThan(1);
        drafts.forEach(d -> assertThat(d.text().value().split("\\s+").length)
                .isLessThan(ChunkingPolicy.DEFAULT.sizeTokens() + 50));
    }

    @Test
    void gfm_table_oversize_repeats_header_row_on_each_split() {
        StringBuilder md = new StringBuilder("# T\n\n| col1 | col2 |\n|---|---|\n");
        for (int i = 0; i < 600; i++) md.append("| r").append(i).append(" | v").append(i).append(" |\n");
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));
        if (drafts.size() > 1) {
            drafts.subList(1, drafts.size()).forEach(d ->
                    assertThat(d.text().value()).contains("| col1 | col2 |"));
        }
    }

    @Test
    void second_and_later_chunks_of_a_section_get_heading_prefix() {
        // Force a section to split into ≥ 2 chunks.
        // SectionBuilder creates two sections: ["Outer"] (just the heading node)
        // and ["Outer","Inner"] (the ## Inner heading + body paragraphs).
        StringBuilder md = new StringBuilder("# Outer\n\n## Inner\n\n");
        String para = "Word ".repeat(200);  // ~200 tokens
        for (int i = 0; i < 8; i++) md.append(para).append("\n\n");
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));
        // Filter to only chunks from the ["Outer","Inner"] section.
        List<ChunkDraft> innerChunks = drafts.stream()
                .filter(d -> d.headingPath().contains("Inner"))
                .toList();
        assertThat(innerChunks.size()).isGreaterThan(1);
        // First chunk of the section has the heading inline naturally.
        assertThat(innerChunks.get(0).text().value()).contains("Inner");
        // Subsequent chunks open with the breadcrumb prefix.
        for (int i = 1; i < innerChunks.size(); i++) {
            assertThat(innerChunks.get(i).text().value())
                    .startsWith("> Context: ")
                    .contains("Outer")
                    .contains("Inner");
        }
    }

    @Test
    void heading_prefix_drops_top_levels_when_budget_overflows() {
        // overlapTokens default 120 → if the breadcrumb exceeds, drop top headings.
        // SectionBuilder creates sections for each heading level, then the deepest
        // section (["A...", "B...", "C..."]) holds the body paragraphs.
        String h1 = "A ".repeat(200);      // ~200 tokens
        String h2 = "B ".repeat(10);
        String h3 = "C ".repeat(5);
        StringBuilder md = new StringBuilder("# ").append(h1).append("\n## ").append(h2)
                .append("\n### ").append(h3).append("\n\n");
        String para = "Word ".repeat(200);
        for (int i = 0; i < 8; i++) md.append(para).append("\n\n");
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));
        // Find chunks from the deepest section (path depth = 3).
        List<ChunkDraft> deepChunks = drafts.stream()
                .filter(d -> d.headingPath().size() == 3)
                .toList();
        assertThat(deepChunks.size()).isGreaterThan(1);
        // Verify the second chunk's prefix does NOT contain the giant h1 text.
        String secondPrefix = deepChunks.get(1).text().value();
        assertThat(secondPrefix).startsWith("> Context: ");
        assertThat(secondPrefix.split("\n", 2)[0].length()).isLessThan(600);
    }

    @Test
    void short_trailing_chunk_in_a_section_merges_into_previous() {
        // Trailing-merge: a section ending with a < minChunkTokens remainder
        // gets folded into the previous chunk in the same section.
        StringBuilder md = new StringBuilder("# X\n\n");
        String big = "Word ".repeat(700);  // ~700 tokens — fits in 1 chunk
        md.append(big).append("\n\n");
        md.append("a b c.\n");             // ~3 tokens — would be a tiny trailer
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).text().value()).endsWith("a b c.");
    }

    @Test
    void trailing_merge_does_not_duplicate_heading_prefix() {
        // A section that produces 3 chunks where the last is < minChunkTokens.
        // After merge, the surviving chunks should each have AT MOST one
        // "> Context:" breadcrumb prefix.
        StringBuilder md = new StringBuilder("# Outer\n\n## Inner\n\n");
        String para = "Word ".repeat(200);
        for (int i = 0; i < 6; i++) md.append(para).append("\n\n");
        md.append("tiny.\n");  // forces a trailing chunk < 64 tokens
        List<ChunkDraft> drafts = normalizer.normalize(builder.build(md.toString()));

        drafts.forEach(d -> {
            String t = d.text().value();
            long contextCount = t.lines()
                    .filter(line -> line.startsWith("> Context:"))
                    .count();
            assertThat(contextCount).as("chunk should have at most one Context prefix").isLessThanOrEqualTo(1);
        });
    }
}
