package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownAwareChunkerTest {

    private final MarkdownAwareChunker chunker = new MarkdownAwareChunker(
            ChunkingPolicy.DEFAULT, new JdkBreakIteratorSentenceSplitter());

    @Test
    void empty_body_yields_no_chunks() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void small_no_heading_body_yields_one_root_section_chunk() {
        List<ChunkDraft> drafts = chunker.chunk("Hello playground.");
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).headingPath()).isEmpty();
        assertThat(drafts.get(0).text().value()).contains("Hello playground");
    }

    @Test
    void chunks_re_parse_as_valid_commonmark() {
        // Invariant: each chunk must round-trip through the parser without
        // exception. The "unclosed fence" failure mode of the old chunker
        // is what this test guards against.
        String md = loadFixture("05-oversize-fence.md");
        List<ChunkDraft> drafts = chunker.chunk(md);
        org.commonmark.parser.Parser p = org.commonmark.parser.Parser.builder().build();
        drafts.forEach(d -> p.parse(d.text().value()));
    }

    @Test
    void parse_fallback_kicks_in_when_commonmark_throws() {
        // commonmark-java is permissive; we synthesize the failure path by
        // injecting a stub parser via a package-private ctor used only by
        // tests.
        MarkdownAwareChunker failing = MarkdownAwareChunker.forTesting(
                ChunkingPolicy.DEFAULT,
                new JdkBreakIteratorSentenceSplitter(),
                body -> { throw new IllegalStateException("synthetic parse failure"); });

        List<ChunkDraft> drafts = failing.chunk("Some body that the new chunker rejects.");
        assertThat(drafts).isNotEmpty();
        drafts.forEach(d -> assertThat(d.headingPath()).isEmpty());
    }

    private String loadFixture(String name) {
        try {
            Path p = Path.of("src/test/resources/chunker-fixtures", name);
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
