package com.playground.metrics.domain;

/**
 * A single (timestamp, value) sample from a Prometheus range query. The
 * timestamp is the unix epoch seconds carried by Prometheus's
 * {@code /api/v1/query_range} response shape.
 */
public record TimeseriesPoint(long ts, double value) {
}
