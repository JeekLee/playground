package com.playground.docs.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocumentTitleTest {

    @Test
    void simple_title_is_accepted() {
        assertThat(new DocumentTitle("Hello").value()).isEqualTo("Hello");
    }

    @Test
    void rejects_blank_title() {
        assertThatThrownBy(() -> new DocumentTitle("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_empty_title() {
        assertThatThrownBy(() -> new DocumentTitle("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_title() {
        assertThatThrownBy(() -> new DocumentTitle(null)).isInstanceOf(NullPointerException.class);
    }
}
