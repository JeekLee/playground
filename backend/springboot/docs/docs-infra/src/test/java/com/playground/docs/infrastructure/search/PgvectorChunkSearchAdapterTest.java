package com.playground.docs.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit test for {@link PgvectorChunkSearchAdapter}'s pre-DB guards. The full
 * SQL (visibility filter + documentId filter + title join + HNSW ef_search
 * tuning) is exercised against a live pgvector instance in the docs-infra
 * integration suite — the same posture chat's PgvectorChunkRetrievalAdapter
 * uses (integration-only). This class covers the deterministic guard that
 * never touches the JdbcTemplate.
 */
class PgvectorChunkSearchAdapterTest {

    @Test
    void empty_embedding_short_circuits_without_touching_jdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PgvectorChunkSearchAdapter adapter = new PgvectorChunkSearchAdapter(jdbc);

        assertThat(adapter.search(UUID.randomUUID(), new float[] {}, 6, null)).isEmpty();
        assertThat(adapter.search(UUID.randomUUID(), null, 6, null)).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
