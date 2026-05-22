package com.playground.metrics.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Allowed range presets for {@code GET /api/metrics/dashboard} and
 * {@code GET /api/metrics/timeseries} per spec §5.1 + ADR-15 §C. The five
 * presets are pinned at compile time; any other value returns 400
 * ({@code METRICS-VALIDATION-003}).
 */
public enum Range {
    M_15("15m", Duration.ofMinutes(15)),
    H_1("1h", Duration.ofHours(1)),
    H_6("6h", Duration.ofHours(6)),
    H_24("24h", Duration.ofHours(24)),
    D_7("7d", Duration.ofDays(7));

    private final String token;
    private final Duration duration;

    Range(String token, Duration duration) {
        this.token = token;
        this.duration = duration;
    }

    public String token() {
        return token;
    }

    public Duration duration() {
        return duration;
    }

    /** Returns the canonical default ({@code 1h}) when input is null / blank. */
    public static Range parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return H_1;
        }
        return parse(value).orElseThrow(() ->
                new IllegalArgumentException("Unknown range token: " + value));
    }

    public static Optional<Range> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        for (Range r : values()) {
            if (r.token.equalsIgnoreCase(trimmed)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public static List<String> tokens() {
        return Arrays.stream(values()).map(Range::token).toList();
    }
}
