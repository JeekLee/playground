package com.playground.docs.infrastructure.ingestion.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Unit test for the single-query {@code embedQuery} path added to
 * {@link SparkInferenceEmbeddingAdapter} for the search_documents tool
 * (agentic-search spec D1). Mocks the Spring AI {@link EmbeddingModel} so the
 * batch-of-one conversion is asserted without a live gateway.
 */
class SparkInferenceEmbeddingAdapterTest {

    @Test
    void embedQuery_returns_first_vector_from_batch_of_one() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        float[] vector = {0.1f, 0.2f, 0.3f};
        EmbeddingResponse response = new EmbeddingResponse(
                List.of(new Embedding(vector, 0)));
        when(model.embedForResponse(anyList())).thenReturn(response);

        SparkInferenceEmbeddingAdapter adapter = new SparkInferenceEmbeddingAdapter(model);

        assertThat(adapter.embedQuery("how to build")).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void embedQuery_rejects_blank_query() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        SparkInferenceEmbeddingAdapter adapter = new SparkInferenceEmbeddingAdapter(model);

        assertThatThrownBy(() -> adapter.embedQuery("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
