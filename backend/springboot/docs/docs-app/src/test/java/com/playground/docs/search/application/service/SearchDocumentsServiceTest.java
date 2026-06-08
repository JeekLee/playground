package com.playground.docs.search.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.docs.search.application.port.ChunkSearchPort;
import com.playground.docs.search.application.port.QueryEmbeddingPort;
import com.playground.shared.chat.SourceRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link SearchDocumentsService} (agentic-search spec D1, D5;
 * SP3b spec D2). Uses hand-rolled fake ports so the topK clamp, documentId
 * pass-through, row→{@link SourceRef} mapping, and content truncation are
 * asserted without Spring AI or pgvector. {@code publicOrigin} is injected
 * explicitly (the @Value-bound constructor arg).
 */
class SearchDocumentsServiceTest {

    private static final String ORIGIN = "https://playground.jeeklee.com";

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
        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port, ORIGIN);
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
        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port, ORIGIN);
        UUID caller = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        service.search(caller, "q", 6, docId);

        assertThat(port.documentIdReceived).isTrue();
        assertThat(port.receivedDocumentId).isEqualTo(docId);
    }

    @Test
    void emitsSourceRefWithAbsoluteDocUriAndNoVisibility() {
        FakeChunkSearchPort port = new FakeChunkSearchPort();
        UUID d1 = UUID.randomUUID();
        port.rows = new ArrayList<>(List.of(
                new ChunkSearchPort.Row(d1, 3, "A", "본문", "private")));

        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port, ORIGIN);
        SearchDocumentsService.SearchOutcome outcome = service.search(UUID.randomUUID(), "q", 6, null);

        assertThat(outcome.results()).hasSize(1);
        SourceRef s = outcome.results().get(0);
        assertThat(s.sourceType()).isEqualTo("document");
        assertThat(s.title()).isEqualTo("A");
        assertThat(s.content()).isEqualTo("본문");
        assertThat(s.uri()).isEqualTo(ORIGIN + "/docs/" + d1);
        // documentId/chunkIndex/visibility/position are NOT on SourceRef — compile-enforced.
        assertThat(outcome.totalFound()).isEqualTo(1);
        assertThat(outcome.summary()).isEqualTo("q — 1건");
    }

    @Test
    void mapsMultipleRowsPreservingOrder() {
        FakeChunkSearchPort port = new FakeChunkSearchPort();
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        UUID docC = UUID.randomUUID();
        port.rows = new ArrayList<>(List.of(
                new ChunkSearchPort.Row(docA, 3, "Alpha", "short text", "private"),
                new ChunkSearchPort.Row(docB, 7, "Beta", "second", "public"),
                new ChunkSearchPort.Row(docC, 0, "Gamma", "another", "private")));

        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port, ORIGIN);
        SearchDocumentsService.SearchOutcome outcome =
                service.search(UUID.randomUUID(), "alpha query", 6, null);

        assertThat(outcome.results()).hasSize(3);
        assertThat(outcome.totalFound()).isEqualTo(3);
        assertThat(outcome.summary()).isEqualTo("alpha query — 3건");

        assertThat(outcome.results().get(0).title()).isEqualTo("Alpha");
        assertThat(outcome.results().get(0).content()).isEqualTo("short text");
        assertThat(outcome.results().get(0).uri()).isEqualTo(ORIGIN + "/docs/" + docA);
        assertThat(outcome.results().get(1).title()).isEqualTo("Beta");
        assertThat(outcome.results().get(1).uri()).isEqualTo(ORIGIN + "/docs/" + docB);
        assertThat(outcome.results().get(2).title()).isEqualTo("Gamma");
        assertThat(outcome.results().get(2).uri()).isEqualTo(ORIGIN + "/docs/" + docC);
    }

    @Test
    void truncatesContentTo600() {
        FakeChunkSearchPort port = new FakeChunkSearchPort();
        port.rows = new ArrayList<>(List.of(
                new ChunkSearchPort.Row(UUID.randomUUID(), 0, "Long", "x".repeat(700), "public")));

        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port, ORIGIN);
        SearchDocumentsService.SearchOutcome outcome =
                service.search(UUID.randomUUID(), "q", 6, null);

        assertThat(outcome.results().get(0).content()).hasSize(600);
        assertThat(outcome.results().get(0).content()).isEqualTo("x".repeat(600));
    }

    @Test
    void emptyResultsIsNormal() {
        FakeChunkSearchPort port = new FakeChunkSearchPort();
        port.rows = List.of();
        SearchDocumentsService service = new SearchDocumentsService(STUB_EMBEDDING, port, ORIGIN);

        SearchDocumentsService.SearchOutcome outcome =
                service.search(UUID.randomUUID(), "nothing", 6, null);

        assertThat(outcome.results()).isEmpty();
        assertThat(outcome.totalFound()).isZero();
        assertThat(outcome.summary()).isEqualTo("nothing — 0건");
    }
}
