package com.playground.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SourceRefTest {

    @Test
    void holdsAllFields() {
        SourceRef ref = new SourceRef("document", "KFI 지침서", "본문 발췌", "https://x/docs/abc");

        assertThat(ref.sourceType()).isEqualTo("document");
        assertThat(ref.title()).isEqualTo("KFI 지침서");
        assertThat(ref.content()).isEqualTo("본문 발췌");
        assertThat(ref.uri()).isEqualTo("https://x/docs/abc");
    }

    @Test
    void allowsNullTitleContentUri() {
        SourceRef ref = new SourceRef("document", null, null, null);

        assertThat(ref.sourceType()).isEqualTo("document");
        assertThat(ref.title()).isNull();
        assertThat(ref.content()).isNull();
        assertThat(ref.uri()).isNull();
    }

    @Test
    void rejectsBlankSourceType() {
        assertThatThrownBy(() -> new SourceRef("  ", "t", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRef(null, "t", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
