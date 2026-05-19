package com.playground.metrics.domain;

/**
 * Loki label set standard per ADR-15 §11 — every shipped log line carries
 * {@code container}, {@code service}, {@code source}. {@code level} is NOT a
 * label; it is queried via {@code | json | level=~"WARN|ERROR"} inside the
 * LogQL pipeline (kept out of the Loki inverted index to avoid label
 * cardinality blowup — Loki best practice).
 */
public record LokiLabelSet(String container, String service, String source) {

    public static final String SOURCE_DOCKER = "docker";

    public LokiLabelSet {
        if (container == null || service == null || source == null) {
            throw new IllegalArgumentException("Loki labels must not be null");
        }
    }
}
