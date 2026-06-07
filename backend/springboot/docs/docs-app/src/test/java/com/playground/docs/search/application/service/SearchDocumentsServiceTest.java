package com.playground.docs.search.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.docs.search.application.port.ChunkSearchPort;
import com.playground.docs.search.application.port.QueryEmbeddingPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link SearchDocumentsService} (agentic-search spec D1, D5).
 * Uses hand-rolled fake ports so the topK clamp, documentId pass-through,
 * row→result mapping, and excerpt truncation are asserted without Spring AI
 * or pgvector.
 */
class SearchDocumentsServiceTest {

    /** Captures the k + documentId the service forwards, returns canned rows. */
    private static final class FakeChunkSearchPort implements ChunkSearchPort {
        int receivedK;
        UUID receivedDocumentId;
        boolean documentIdReceived;
        List<Row> rows = List.of();

        @Override
        public List<Row> search(UUID callerId, float[] embedding, int k, UUID documentIdOrNull) {
            this.receivedK = k;
            this.receivedDocumentId = documentIdOrNull;
            this.documentIdReceived = true;
            return rows;
        }
    }

    private static final QueryEmbeddingPort STUB_EMBEDDING = query -> new float[] {0.1f, 0.2f};

    @Test
    void clampsTopKInto1to20() {
        FakeChunkSearchPort port = new FakeChunkSearchPort();
        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port);
        UUID caller = UUID.randomUUID();

        service.search(caller, "q", 0, null);
        assertThat(port.receivedK).isEqualTo(1);

        service.search(caller, "q", 50, null);
        assertThat(port.receivedK).isEqualTo(20);

        service.search(caller, "q", null, null);
        assertThat(port.receivedK).isEqualTo(6);
    }

    @Test
    void passesDocumentIdFilterThrough() {
        FakeChunkSearchPort port = new FakeChunkSearchPort();
        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port);
        UUID caller = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        service.search(caller, "q", 6, docId);

        assertThat(port.documentIdReceived).isTrue();
        assertThat(port.receivedDocumentId).isEqualTo(docId);
    }

    @Test
    void mapsRowsToResultsWithPositions1toK() {
        FakeChunkSearchPort port = new FakeChunkSearchPort();
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        UUID docC = UUID.randomUUID();
        String longText = "x".repeat(900);
        port.rows = new ArrayList<>(List.of(
                new ChunkSearchPort.Row(docA, 3, "Alpha", "short text", "private"),
                new ChunkSearchPort.Row(docB, 7, "Beta", longText, "public"),
                new ChunkSearchPort.Row(docC, 0, "Gamma", "another", "private")));

        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port);
        SearchDocumentsService.SearchOutcome outcome =
                service.search(UUID.randomUUID(), "alpha query", 6, null);

        assertThat(outcome.results()).hasSize(3);
        assertThat(outcome.totalFound()).isEqualTo(3);
        assertThat(outcome.summary()).isEqualTo("alpha query — 3건");

        SearchDocumentsService.Result r1 = outcome.results().get(0);
        assertThat(r1.position()).isEqualTo(1);
        assertThat(r1.documentId()).isEqualTo(docA);
        assertThat(r1.chunkIndex()).isEqualTo(3);
        assertThat(r1.title()).isEqualTo("Alpha");
        assertThat(r1.excerpt()).isEqualTo("short text");
        assertThat(r1.visibility()).isEqualTo("private");

        assertThat(outcome.results().get(1).position()).isEqualTo(2);
        assertThat(outcome.results().get(2).position()).isEqualTo(3);

        // long text head-truncated to 600 chars
        assertThat(outcome.results().get(1).excerpt()).hasSize(600);
        assertThat(outcome.results().get(1).excerpt()).isEqualTo("x".repeat(600));
    }

    @Test
    void emptyResultsIsNormal() {
        FakeChunkSearchPort port = new FakeChunkSearchPort();
        port.rows = List.of();
        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port);

        SearchDocumentsService.SearchOutcome outcome =
                service.search(UUID.randomUUID(), "nothing", 6, null);

        assertThat(outcome.results()).isEmpty();
        assertThat(outcome.totalFound()).isZero();
        assertThat(outcome.summary()).isEqualTo("nothing — 0건");
    }
}
