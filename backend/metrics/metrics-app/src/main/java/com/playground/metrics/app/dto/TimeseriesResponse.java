package com.playground.metrics.app.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonFormat.Shape;
import java.util.List;

/**
 * Single-chart timeseries payload per spec §5.3.
 *
 * @param metric metric id the caller requested
 * @param range range token (e.g., {@code "1h"})
 * @param step step token (e.g., {@code "30s"})
 * @param series one or more labeled series with their {@code [ts, val]} points
 * @param unit human-friendly unit (e.g., {@code "MB"}, {@code "req/s"})
 */
public record TimeseriesResponse(
        String metric,
        String range,
        String step,
        List<Series> series,
        String unit) {

    public record Series(String label, List<Point> points) {
    }

    /**
     * A {@code [timestamp, value]} pair carried as a 2-element JSON array per
     * spec §5.3 ({@code [1715763600, 380]}). The {@link JsonFormat} hint
     * tells Jackson to serialize the record as a positional array rather than
     * a {@code {ts, value}} object.
     */
    @JsonFormat(shape = Shape.ARRAY)
    public record Point(long ts, double value) {
    }
}
