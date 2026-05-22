package com.playground.identity.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AvatarUrlTest {

    @Test
    void null_input_yields_EMPTY() {
        assertThat(AvatarUrl.of(null)).isEqualTo(AvatarUrl.EMPTY);
        assertThat(AvatarUrl.of(null).value()).isNull();
    }

    @Test
    void blank_input_is_normalized_to_null() {
        assertThat(AvatarUrl.of("   ").value()).isNull();
    }

    @Test
    void retains_real_url() {
        AvatarUrl url = AvatarUrl.of("https://lh3.googleusercontent.com/a/x");
        assertThat(url.asOptional()).contains("https://lh3.googleusercontent.com/a/x");
    }
}
