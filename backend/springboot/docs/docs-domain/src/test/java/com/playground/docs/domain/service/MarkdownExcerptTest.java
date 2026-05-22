package com.playground.docs.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownExcerptTest {

    @Test
    void null_and_empty_yield_empty_string() {
        assertThat(MarkdownExcerpt.of(null)).isEmpty();
        assertThat(MarkdownExcerpt.of("")).isEmpty();
        assertThat(MarkdownExcerpt.of("   ")).isEmpty();
    }

    @Test
    void short_plain_text_passes_through_untouched() {
        assertThat(MarkdownExcerpt.of("hello world")).isEqualTo("hello world");
    }

    @Test
    void strips_heading_markers() {
        assertThat(MarkdownExcerpt.of("# Hello\n## World")).isEqualTo("Hello World");
    }

    @Test
    void strips_bold_italic_strike() {
        assertThat(MarkdownExcerpt.of("**bold** _italic_ ~~strike~~")).isEqualTo("bold italic strike");
    }

    @Test
    void link_text_kept_url_stripped() {
        assertThat(MarkdownExcerpt.of("see [the docs](https://example.com)"))
                .isEqualTo("see the docs");
    }

    @Test
    void inline_code_text_kept_backticks_stripped() {
        assertThat(MarkdownExcerpt.of("call `foo()` then")).isEqualTo("call foo() then");
    }

    @Test
    void fenced_code_block_dropped() {
        String body = "intro\n```java\nSystem.out.println(\"hi\");\n```\nafter";
        assertThat(MarkdownExcerpt.of(body)).isEqualTo("intro after");
    }

    @Test
    void images_dropped() {
        assertThat(MarkdownExcerpt.of("before ![alt text](https://x/y.png) after"))
                .isEqualTo("before after");
    }

    @Test
    void blockquotes_and_list_markers_stripped() {
        String body = "> a quote\n- item one\n- item two\n1. ordered";
        assertThat(MarkdownExcerpt.of(body)).isEqualTo("a quote item one item two ordered");
    }

    @Test
    void truncates_to_160_chars_with_ellipsis() {
        String body = "a".repeat(200);
        String excerpt = MarkdownExcerpt.of(body);
        assertThat(excerpt).hasSize(160 + 1); // 160 chars + ellipsis
        assertThat(excerpt).endsWith("…");
        assertThat(excerpt.substring(0, 160)).isEqualTo("a".repeat(160));
    }

    @Test
    void byte_stable_for_identical_input() {
        String body = "# Hi\n\nSome **bold** text with a [link](https://example.com) and `code`.";
        String first = MarkdownExcerpt.of(body);
        String second = MarkdownExcerpt.of(body);
        assertThat(first).isEqualTo(second);
    }
}
