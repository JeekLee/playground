package com.playground.metrics.domain;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step value object for range-vector queries (PromQL {@code step} parameter).
 * Per spec §5.3 the frontend sends opaque tokens like {@code 30s} / {@code 1m}
 * / {@code 5m}; this value object validates and parses them into a
 * {@link Duration}. Invalid input throws {@link IllegalArgumentException};
 * the controller maps that to 400.
 *
 * <p>Step is constrained to {@code 1s..1h} to prevent abuse — extreme steps
 * (e.g., {@code 1ms}) would force Prometheus to evaluate millions of points.
 */
public record Step(Duration duration, String token) {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)(s|m|h)$");
    private static final Duration MIN = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofHours(1);

    public Step {
        if (duration == null || token == null) {
            throw new IllegalArgumentException("step duration / token must not be null");
        }
        if (duration.compareTo(MIN) < 0 || duration.compareTo(MAX) > 0) {
            throw new IllegalArgumentException(
                    "step must be between 1s and 1h, was: " + duration);
        }
    }

    public static Step parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return new Step(Duration.ofSeconds(30), "30s");
        }
        return parse(value);
    }

    public static Step parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("step must not be null");
        }
        Matcher m = PATTERN.matcher(value.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("invalid step token: " + value);
        }
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2);
        Duration d = switch (unit) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> throw new IllegalArgumentException("unknown step unit: " + unit);
        };
        return new Step(d, value.trim());
    }
}
