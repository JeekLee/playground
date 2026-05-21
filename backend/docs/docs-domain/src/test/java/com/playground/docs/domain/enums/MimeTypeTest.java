package com.playground.docs.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MimeTypeTest {

    @Test
    void wire_value_round_trips_for_both_known_types() {
        assertThat(MimeType.fromWire("text/markdown")).isEqualTo(MimeType.MARKDOWN);
        assertThat(MimeType.fromWire("application/pdf")).isEqualTo(MimeType.PDF);
        assertThat(MimeType.MARKDOWN.wireValue()).isEqualTo("text/markdown");
        assertThat(MimeType.PDF.wireValue()).isEqualTo("application/pdf");
    }

    @Test
    void from_wire_is_case_insensitive() {
        assertThat(MimeType.fromWire("Text/Markdown")).isEqualTo(MimeType.MARKDOWN);
        assertThat(MimeType.fromWire("APPLICATION/PDF")).isEqualTo(MimeType.PDF);
    }

    @Test
    void from_wire_defaults_null_to_markdown() {
        // Existing rows pre-M6 had no mime_type; the JPA mapper feeds null
        // here when an old row is read after the migration's NOT NULL default
        // has been applied. Belt-and-braces: null still resolves to MARKDOWN.
        assertThat(MimeType.fromWire(null)).isEqualTo(MimeType.MARKDOWN);
    }

    @Test
    void from_wire_rejects_unknown_types() {
        assertThatThrownBy(() -> MimeType.fromWire("application/json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
