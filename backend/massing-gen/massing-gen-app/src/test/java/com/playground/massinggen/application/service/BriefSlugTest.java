package com.playground.massinggen.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BriefSlugTest {

    @Test
    void asciiLowercased() {
        assertThat(BriefSlug.of("My Competition Brief")).isEqualTo("my-competition-brief");
    }

    @Test
    void koreanPreserved() {
        // Hangul code points are alphabetic — they pass through Character.isLetterOrDigit.
        assertThat(BriefSlug.of("KFI 도서관 brief"))
                .isEqualTo("kfi-도서관-brief");
    }

    @Test
    void multipleNonAlphanumericsCollapse() {
        assertThat(BriefSlug.of("foo   bar---baz!!!qux"))
                .isEqualTo("foo-bar-baz-qux");
    }

    @Test
    void leadingAndTrailingHyphensStripped() {
        assertThat(BriefSlug.of("---foo---")).isEqualTo("foo");
    }

    @Test
    void empty_returnsBriefPlaceholder() {
        assertThat(BriefSlug.of("")).isEqualTo("brief");
        assertThat(BriefSlug.of("!!!---")).isEqualTo("brief");
    }

    @Test
    void cappedAt40Chars() {
        String long_ = "a".repeat(100);
        assertThat(BriefSlug.of(long_)).hasSize(40);
    }
}
