package com.playground.docs.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the Vision OCR code-fence stripping helper. The full
 * adapter (ChatClient + Spring AI Media) is exercised via the Spring AI
 * test fixtures in the docs-infra integration test; this class only covers
 * the deterministic post-processing.
 */
class VisionOcrAdapterTest {

    @Test
    void strip_code_fence_removes_markdown_wrapper() {
        String wrapped = "```markdown\n# heading\n\nbody\n```";
        assertThat(VisionOcrAdapter.stripCodeFence(wrapped)).isEqualTo("# heading\n\nbody");
    }

    @Test
    void strip_code_fence_removes_bare_triple_backtick() {
        String wrapped = "```\nplain text\n```";
        assertThat(VisionOcrAdapter.stripCodeFence(wrapped)).isEqualTo("plain text");
    }

    @Test
    void strip_code_fence_leaves_un_wrapped_output_alone() {
        String raw = "# heading\n\nbody with ```inline code``` mid-paragraph.";
        assertThat(VisionOcrAdapter.stripCodeFence(raw)).isEqualTo(raw);
    }

    @Test
    void strip_code_fence_handles_null_and_empty() {
        assertThat(VisionOcrAdapter.stripCodeFence(null)).isEqualTo("");
        assertThat(VisionOcrAdapter.stripCodeFence("")).isEqualTo("");
        assertThat(VisionOcrAdapter.stripCodeFence("   ")).isEqualTo("");
    }
}
