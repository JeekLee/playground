package com.playground.metrics.app.dto;

import java.util.Map;

/**
 * A single (timestamp, value) sample from a Prometheus instant query
 * ({@code /api/v1/query}). The {@code labels} map carries the result series'
 * label set (e.g., {@code service=docs-api}).
 */
public record PrometheusSample(Map<String, String> labels, long ts, double value) {
}
