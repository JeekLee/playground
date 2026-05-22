package com.playground.docs.ingestion.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BodyChecksumTest {

    @Test
    void compute_matches_known_sha256() {
        // SHA-256("") = e3b0c442... (FIPS test vector). We canonicalize on the
        // empty string because the docs BC also accepts empty bodies.
        BodyChecksum empty = BodyChecksum.compute("");
        assertThat(empty.value())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void compute_is_stable_across_calls_for_the_same_input() {
        String body = "The quick brown fox jumps over the lazy dog";
        assertThat(BodyChecksum.compute(body)).isEqualTo(BodyChecksum.compute(body));
    }

    @Test
    void compute_differs_for_different_inputs() {
        assertThat(BodyChecksum.compute("a")).isNotEqualTo(BodyChecksum.compute("b"));
    }

    @Test
    void of_rejects_wrong_length() {
        assertThatThrownBy(() -> BodyChecksum.of("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BodyChecksum.of("0".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BodyChecksum.of("0".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_accepts_correctly_sized_hex() {
        String hex = "0".repeat(64);
        assertThat(BodyChecksum.of(hex).value()).isEqualTo(hex);
    }

    @Test
    void matches_docs_DocumentBody_checksum_format() {
        // The docs BC's DocumentBody.checksum() uses the same algorithm
        // (UTF-8 octets → SHA-256 → lowercase hex). Both sides comparable
        // byte-for-byte by construction.
        BodyChecksum computed = BodyChecksum.compute("playground markdown body");
        assertThat(computed.value()).hasSize(64);
        assertThat(computed.value()).matches("[0-9a-f]+");
    }
}
