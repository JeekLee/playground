package com.playground.metrics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RangeAndStepTest {

    @Test
    void rangeParsesAllFivePresets() {
        assertThat(Range.parseOrDefault("15m")).isEqualTo(Range.M_15);
        assertThat(Range.parseOrDefault("1h")).isEqualTo(Range.H_1);
        assertThat(Range.parseOrDefault("6h")).isEqualTo(Range.H_6);
        assertThat(Range.parseOrDefault("24h")).isEqualTo(Range.H_24);
        assertThat(Range.parseOrDefault("7d")).isEqualTo(Range.D_7);
    }

    @Test
    void rangeDefaultsTo1h() {
        assertThat(Range.parseOrDefault(null)).isEqualTo(Range.H_1);
        assertThat(Range.parseOrDefault("")).isEqualTo(Range.H_1);
    }

    @Test
    void rangeRejectsUnknownToken() {
        assertThatThrownBy(() -> Range.parseOrDefault("99x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stepParsesTokens() {
        assertThat(Step.parse("30s").duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(Step.parse("1m").duration()).isEqualTo(Duration.ofMinutes(1));
        assertThat(Step.parse("5m").duration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void stepDefaultsTo30s() {
        assertThat(Step.parseOrDefault(null).duration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void stepRejectsOutOfRange() {
        // 2h exceeds 1h max
        assertThatThrownBy(() -> Step.parse("2h"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
