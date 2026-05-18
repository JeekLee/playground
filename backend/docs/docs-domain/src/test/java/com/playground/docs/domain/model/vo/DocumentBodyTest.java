package com.playground.docs.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocumentBodyTest {

    @Test
    void empty_body_is_allowed() {
        assertThat(new DocumentBody("").isEmpty()).isTrue();
        assertThat(DocumentBody.empty().value()).isEmpty();
    }

    @Test
    void of_normalizes_null_to_empty() {
        assertThat(DocumentBody.of(null).value()).isEmpty();
    }

    @Test
    void body_at_one_megabyte_is_accepted() {
        byte[] payload = new byte[DocumentBody.MAX_OCTET_LENGTH];
        java.util.Arrays.fill(payload, (byte) 'a');
        String s = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(new DocumentBody(s).value()).hasSize(DocumentBody.MAX_OCTET_LENGTH);
    }

    @Test
    void body_over_one_megabyte_is_rejected() {
        byte[] payload = new byte[DocumentBody.MAX_OCTET_LENGTH + 1];
        java.util.Arrays.fill(payload, (byte) 'a');
        String s = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new DocumentBody(s)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void octet_length_uses_utf8_not_char_count() {
        // A 4-byte UTF-8 emoji surrogate pair — char-count is 2 but octet count is 4.
        // Build a string whose octet length is exactly MAX_OCTET_LENGTH + 4 but whose
        // char count is well under MAX_OCTET_LENGTH.
        int charsForOverflow = (DocumentBody.MAX_OCTET_LENGTH / 4) + 1; // each emoji = 4 bytes
        StringBuilder sb = new StringBuilder(charsForOverflow * 2);
        String emoji = "😀"; // 😀, 4 bytes in UTF-8, 2 UTF-16 chars
        for (int i = 0; i < charsForOverflow; i++) {
            sb.append(emoji);
        }
        String s = sb.toString();
        assertThatThrownBy(() -> new DocumentBody(s)).isInstanceOf(IllegalArgumentException.class);
    }
}
