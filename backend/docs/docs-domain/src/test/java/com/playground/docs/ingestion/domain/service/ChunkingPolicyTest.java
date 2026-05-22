package com.playground.docs.ingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ChunkingPolicyTest {

    @Test
    void default_policy_matches_adr_13_pins() {
        ChunkingPolicy p = ChunkingPolicy.DEFAULT;
        assertThat(p.sizeTokens()).isEqualTo(800);
        assertThat(p.overlapTokens()).isEqualTo(120);
        assertThat(p.minChunkTokens()).isEqualTo(64);
        assertThat(p.tokenizer()).isEqualTo("cl100k-base");
        assertThat(p.maxOversizeFenceTokens()).isEqualTo(800);
        assertThat(p.preserveHeadingPath()).isTrue();
    }

    @Test
    void max_oversize_fence_tokens_must_be_positive() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ChunkingPolicy(800, 120, 64, "cl100k-base", 0, true));
    }

    @Test
    void stride_still_derives_from_size_minus_overlap() {
        ChunkingPolicy p = ChunkingPolicy.DEFAULT;
        assertThat(p.stride()).isEqualTo(680);
    }
}
