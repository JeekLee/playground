package com.playground.docs.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playground.shared.error.BadRequestException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeedCursorTest {

    @Test
    void null_or_blank_decode_returns_null() {
        assertThat(FeedCursor.decode(null)).isNull();
        assertThat(FeedCursor.decode("")).isNull();
        assertThat(FeedCursor.decode("   ")).isNull();
    }

    @Test
    void roundtrip_encode_decode_preserves_pair() {
        Instant ts = Instant.parse("2026-05-18T01:02:03.456Z");
        UUID id = UUID.randomUUID();
        String cursor = FeedCursor.encode(ts, id);
        FeedCursor.Decoded back = FeedCursor.decode(cursor);
        assertThat(back).isNotNull();
        assertThat(back.publishedAt()).isEqualTo(ts);
        assertThat(back.id()).isEqualTo(id);
    }

    @Test
    void encode_returns_null_when_either_field_is_null() {
        assertThat(FeedCursor.encode(null, UUID.randomUUID())).isNull();
        assertThat(FeedCursor.encode(Instant.now(), null)).isNull();
    }

    @Test
    void malformed_base64_yields_400() {
        assertThatThrownBy(() -> FeedCursor.decode("not-base64!!"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void valid_base64_but_no_separator_yields_400() {
        // base64 of "noseparator"
        String bad = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("noseparator".getBytes());
        assertThatThrownBy(() -> FeedCursor.decode(bad)).isInstanceOf(BadRequestException.class);
    }
}
