package com.playground.docs.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocumentPathTest {

    @Test
    void root_path_is_valid() {
        assertThat(new DocumentPath("/").value()).isEqualTo("/");
        assertThat(DocumentPath.ROOT.value()).isEqualTo("/");
    }

    @Test
    void single_segment_path_is_valid() {
        assertThat(new DocumentPath("/agents/").value()).isEqualTo("/agents/");
    }

    @Test
    void nested_segments_are_valid() {
        assertThat(new DocumentPath("/agents/build-log/").value()).isEqualTo("/agents/build-log/");
    }

    @Test
    void of_normalizes_null_to_root() {
        assertThat(DocumentPath.of(null).value()).isEqualTo("/");
    }

    @Test
    void of_normalizes_empty_to_root() {
        assertThat(DocumentPath.of("").value()).isEqualTo("/");
    }

    @Test
    void rejects_path_without_leading_slash() {
        assertThatThrownBy(() -> new DocumentPath("agents/")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_path_without_trailing_slash() {
        assertThatThrownBy(() -> new DocumentPath("/agents")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_uppercase_segments() {
        assertThatThrownBy(() -> new DocumentPath("/Agents/")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_segments_starting_with_hyphen() {
        assertThatThrownBy(() -> new DocumentPath("/-bad/")).isInstanceOf(IllegalArgumentException.class);
    }
}
