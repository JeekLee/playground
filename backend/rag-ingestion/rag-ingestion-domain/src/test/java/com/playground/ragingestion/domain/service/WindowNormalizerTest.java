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
}
