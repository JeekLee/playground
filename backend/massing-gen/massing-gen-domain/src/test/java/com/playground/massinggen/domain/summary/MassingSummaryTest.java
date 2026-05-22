package com.playground.massinggen.domain.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MassingSummary} per ADR-18 §5.
 */
class MassingSummaryTest {

    private Locale originalLocale;

    @BeforeEach
    void rememberLocale() {
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void formatsKoreanFixedString() {
        assertThat(MassingSummary.format(12, 3, 480.0))
                .isEqualTo("12실 · 3층 · 총 480 m²");
    }

    @Test
    void localeStability_USandKOREA_produceSameOutput() {
        Locale.setDefault(Locale.US);
        String us = MassingSummary.format(15, 4, 12345.0);
        Locale.setDefault(Locale.KOREA);
        String kr = MassingSummary.format(15, 4, 12345.0);
        assertThat(us).isEqualTo(kr);
        // Sanity — no thousands separator drift.
        assertThat(us).isEqualTo("15실 · 4층 · 총 12345 m²");
    }

    @Test
    void roundsFractionalAreaToZeroDecimals() {
        // %.0f rounds half-up by default — 479.4 → 479, 479.6 → 480.
        assertThat(MassingSummary.format(1, 1, 479.4))
                .isEqualTo("1실 · 1층 · 총 479 m²");
        assertThat(MassingSummary.format(1, 1, 479.6))
                .isEqualTo("1실 · 1층 · 총 480 m²");
    }
}
