package com.playground.ragingestion.domain.model.vo;

import java.util.Arrays;
import java.util.Objects;

/**
 * 1024-dimensional dense embedding vector emitted by BGE-M3 (ADR-04 + ADR-13
 * §10). The dimension constant is duplicated on the persistence side
 * ({@code vector(1024)} in the Flyway DDL); a mismatch surfaces as an
 * Hibernate exception at the bulk insert.
 *
 * <p>Backed by a primitive float array; equality is array-content equality so
 * round-trip tests behave intuitively.
 */
public record Embedding(float[] values) {

    /** BGE-M3 dense head produces 1024 floats per chunk. */
    public static final int DIMENSION = 1024;

    public Embedding {
        Objects.requireNonNull(values, "Embedding.values must not be null");
        if (values.length != DIMENSION) {
            throw new IllegalArgumentException(
                    "Embedding must have " + DIMENSION + " dimensions, got " + values.length);
        }
    }

    public static Embedding of(float[] values) {
        return new Embedding(values.clone());
    }

    public int dimension() {
        return values.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Embedding other)) return false;
        return Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Embedding[dim=" + values.length + "]";
    }
}
