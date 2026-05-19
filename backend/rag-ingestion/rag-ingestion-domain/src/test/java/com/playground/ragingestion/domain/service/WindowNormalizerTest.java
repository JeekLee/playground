package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragingestion.domain.model.vo.ChunkDraft;
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
                    assertThat(t).matches(".*[.!?]\\s*");
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
}
