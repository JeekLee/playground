package com.playground.metrics.app.dto;

import com.playground.metrics.domain.TimeseriesPoint;
import java.util.List;
import java.util.Map;

/**
 * A single Prometheus range-query result series — one label set, many
 * timestamped values. Returned by {@code /api/v1/query_range}.
 */
public record PrometheusSeries(Map<String, String> labels, List<TimeseriesPoint> points) {
}
