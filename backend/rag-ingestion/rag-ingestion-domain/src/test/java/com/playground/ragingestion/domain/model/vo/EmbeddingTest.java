package com.playground.ragingestion.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmbeddingTest {

    @Test
    void accepts_1024_dim_vector() {
        float[] values = new float[Embedding.DIMENSION];
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 0.001f;
        }
        Embedding e = Embedding.of(values);
        assertThat(e.dimension()).isEqualTo(Embedding.DIMENSION);
    }

    @Test
    void rejects_wrong_dimension() {
        assertThatThrownBy(() -> Embedding.of(new float[1023]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Embedding.of(new float[1025]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Embedding.of(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equality_is_array_content_equality() {
        float[] a = new float[Embedding.DIMENSION];
        float[] b = new float[Embedding.DIMENSION];
        a[0] = 1.0f;
        b[0] = 1.0f;
        assertThat(Embedding.of(a)).isEqualTo(Embedding.of(b));

        b[0] = 2.0f;
        assertThat(Embedding.of(a)).isNotEqualTo(Embedding.of(b));
    }

    @Test
    void factory_clones_input_array_so_mutation_does_not_leak() {
        float[] values = new float[Embedding.DIMENSION];
        Embedding e = Embedding.of(values);
        values[0] = 999.0f;
        assertThat(e.values()[0]).isZero();
    }
}
