package com.playground.docs.ingestion.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.docs.ingestion.application.dto.DocumentBody;
import com.playground.docs.ingestion.application.port.BodyFetchPort;
import com.playground.docs.ingestion.application.port.DistributedLockPort;
import com.playground.docs.ingestion.application.port.EmbeddingPort;
import com.playground.docs.ingestion.application.repository.ChunkOwnerMeta;
import com.playground.docs.ingestion.application.repository.ChunkRepository;
import com.playground.docs.ingestion.domain.enums.Visibility;
import com.playground.docs.ingestion.domain.event.DocumentIngested;
import com.playground.docs.ingestion.domain.exception.RagIngestionErrorCode;
import com.playground.docs.ingestion.domain.model.DocumentChunk;
import com.playground.docs.ingestion.domain.model.id.AuthorId;
import com.playground.docs.ingestion.domain.model.id.DocumentId;
import com.playground.docs.ingestion.domain.model.vo.BodyChecksum;
import com.playground.docs.ingestion.domain.model.vo.ChunkText;
import com.playground.docs.ingestion.domain.model.vo.Embedding;
import com.playground.docs.ingestion.domain.service.ChunkingPolicy;
import com.playground.docs.ingestion.domain.service.JdkBreakIteratorSentenceSplitter;
import com.playground.docs.ingestion.domain.service.MarkdownAwareChunker;
import com.playground.shared.error.ExceptionCreator;
import com.playground.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link ReembedService}. Ports are stubbed with Mockito;
 * {@link MarkdownAwareChunker} runs for real with a tiny policy so tests can
 * verify heading-path propagation without integration infrastructure.
 */
@ExtendWith(MockitoExtension.class)
class ReembedServiceTest {

    @Mock
    ChunkRepository chunkRepository;
    @Mock
    BodyFetchPort bodyFetchPort;
    @Mock
    EmbeddingPort embeddingPort;
    @Mock
    DistributedLockPort lockPort;
    @Mock
    ApplicationEventPublisher events;

    // Chunker is expensive to initialize (JTokkit loads the full token map once
    // per registry) — share a single instance across all tests via a static
    // field to avoid OOM when each @BeforeEach would re-initialize the registry.
    static final MarkdownAwareChunker CHUNKER = new MarkdownAwareChunker(
            new ChunkingPolicy(8, 2, 2, "cl100k-base", 8, true),
            new JdkBreakIteratorSentenceSplitter());

    Clock clock;
    ReembedService service;

    // Fixed test fixtures
    static final UUID DOC_UUID = UUID.randomUUID();
    static final UUID USER_UUID = UUID.randomUUID();
    static final DocumentId DOC_ID = DocumentId.of(DOC_UUID);
    static final AuthorId USER_ID = AuthorId.of(USER_UUID);
    static final ChunkOwnerMeta OWNER = new ChunkOwnerMeta(USER_ID, Visibility.PRIVATE);

    @BeforeEach
    void setup() {
        clock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC);
        // Two-step construction: create a shadow with self=null, spy it, then
        // pass the spy as `self` so self.reembedInTx() calls work correctly
        // when the runWithLock stub invokes the lambda inline.
        ReembedService shadow = new ReembedService(
                CHUNKER, embeddingPort, chunkRepository, bodyFetchPort, lockPort, events, clock, null);
        ReembedService selfSpy = Mockito.spy(shadow);
        service = new ReembedService(
                CHUNKER, embeddingPort, chunkRepository, bodyFetchPort, lockPort, events, clock, selfSpy);

        // Lock runs supplier inline (unit test) — same pattern as IngestionServiceTest.
        when(lockPort.runWithLock(any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(3);
            return work.get();
        });
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void reembedOne_success_replaces_chunks_and_publishes_ingested() {
        String body = "# Heading\n\n"
                + "one two three four five six seven eight nine ten eleven twelve.";
        String checksum = BodyChecksum.compute(body).value();

        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.of(OWNER));
        when(bodyFetchPort.fetchBody(DOC_ID)).thenReturn(
                new DocumentBody(DOC_ID, body, BodyChecksum.of(checksum), Instant.now(clock)));
        when(embeddingPort.embed(anyList())).thenAnswer(inv -> {
            List<ChunkText> texts = inv.getArgument(0);
            return texts.stream().map(t -> dummyEmbedding()).toList();
        });

        ReembedService.Outcome outcome = service.reembedOne(DOC_ID);

        assertThat(outcome).isEqualTo(ReembedService.Outcome.SUCCESS);

        // replaceAll called with correct documentId, userId, visibility, headingPath.
        ArgumentCaptor<List<DocumentChunk>> chunksCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).replaceAll(eq(DOC_ID), chunksCaptor.capture());
        List<DocumentChunk> chunks = chunksCaptor.getValue();
        assertThat(chunks).isNotEmpty();
        chunks.forEach(c -> {
            assertThat(c.documentId()).isEqualTo(DOC_ID);
            assertThat(c.userId()).isEqualTo(USER_ID);
            assertThat(c.visibility()).isEqualTo(Visibility.PRIVATE);
            assertThat(c.headingPath()).containsExactly("Heading");
            assertThat(c.bodyChecksum().value()).isEqualTo(checksum);
        });

        // DocumentIngested published with correct fields.
        ArgumentCaptor<DocumentIngested> ingested = ArgumentCaptor.forClass(DocumentIngested.class);
        verify(events).publishEvent(ingested.capture());
        assertThat(ingested.getValue().documentId()).isEqualTo(DOC_ID);
        assertThat(ingested.getValue().userId()).isEqualTo(USER_ID);
        assertThat(ingested.getValue().visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(ingested.getValue().chunkCount()).isEqualTo(chunks.size());
        assertThat(ingested.getValue().bodyChecksum()).isEqualTo(checksum);
    }

    @Test
    void reembedOne_success_acquires_same_lock_key_as_ingestion_service() {
        String body = "alpha beta gamma delta epsilon zeta eta theta iota kappa.";
        String checksum = BodyChecksum.compute(body).value();

        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.of(OWNER));
        when(bodyFetchPort.fetchBody(DOC_ID)).thenReturn(
                new DocumentBody(DOC_ID, body, BodyChecksum.of(checksum), Instant.now(clock)));
        when(embeddingPort.embed(anyList())).thenAnswer(inv -> {
            List<ChunkText> texts = inv.getArgument(0);
            return texts.stream().map(t -> dummyEmbedding()).toList();
        });

        service.reembedOne(DOC_ID);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(lockPort, times(1)).runWithLock(keyCaptor.capture(), any(), any(), any());
        // Must match IngestionService.lockKey(documentId) — "document:<uuid>"
        assertThat(keyCaptor.getValue()).isEqualTo("document:" + DOC_UUID);
    }

    @Test
    void reembedOne_success_propagates_distinct_heading_paths_for_two_h1_sections() {
        String body = "# Section A\n\n"
                + "one two three four five six seven eight nine.\n\n"
                + "# Section B\n\n"
                + "alpha beta gamma delta epsilon zeta eta theta iota.";
        String checksum = BodyChecksum.compute(body).value();

        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.of(OWNER));
        when(bodyFetchPort.fetchBody(DOC_ID)).thenReturn(
                new DocumentBody(DOC_ID, body, BodyChecksum.of(checksum), Instant.now(clock)));
        when(embeddingPort.embed(anyList())).thenAnswer(inv -> {
            List<ChunkText> texts = inv.getArgument(0);
            return texts.stream().map(t -> dummyEmbedding()).toList();
        });

        ReembedService.Outcome outcome = service.reembedOne(DOC_ID);

        assertThat(outcome).isEqualTo(ReembedService.Outcome.SUCCESS);

        ArgumentCaptor<List<DocumentChunk>> cap = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).replaceAll(eq(DOC_ID), cap.capture());
        List<DocumentChunk> chunks = cap.getValue();

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks).anyMatch(c -> c.headingPath().equals(List.of("Section A")));
        assertThat(chunks).anyMatch(c -> c.headingPath().equals(List.of("Section B")));
        chunks.forEach(c -> {
            boolean isA = c.headingPath().equals(List.of("Section A"));
            boolean isB = c.headingPath().equals(List.of("Section B"));
            assertThat(isA || isB)
                    .as("headingPath must be [Section A] or [Section B], got %s", c.headingPath())
                    .isTrue();
        });
    }

    // -------------------------------------------------------------------------
    // Skipped paths
    // -------------------------------------------------------------------------

    @Test
    void reembedOne_skipped_when_no_existing_chunks_for_document() {
        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.empty());

        ReembedService.Outcome outcome = service.reembedOne(DOC_ID);

        assertThat(outcome).isEqualTo(ReembedService.Outcome.SKIPPED);
        verify(bodyFetchPort, never()).fetchBody(any());
        verify(embeddingPort, never()).embed(anyList());
        verify(chunkRepository, never()).replaceAll(any(), anyList());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void reembedOne_skipped_when_body_fetch_returns_404() {
        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.of(OWNER));
        // Simulate DocsBodyFetchAdapter throwing NotFoundException on 404.
        NotFoundException notFound = (NotFoundException) ExceptionCreator
                .of(RagIngestionErrorCode.DOCUMENT_NOT_FOUND, DOC_ID.toString())
                .build();
        when(bodyFetchPort.fetchBody(DOC_ID)).thenThrow(notFound);

        ReembedService.Outcome outcome = service.reembedOne(DOC_ID);

        assertThat(outcome).isEqualTo(ReembedService.Outcome.SKIPPED);
        verify(chunkRepository, never()).replaceAll(any(), anyList());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void reembedOne_skipped_for_empty_body_purges_and_emits_zero_chunk_ingested() {
        String emptyBody = "";
        String checksum = BodyChecksum.compute(emptyBody).value();

        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.of(OWNER));
        when(bodyFetchPort.fetchBody(DOC_ID)).thenReturn(
                new DocumentBody(DOC_ID, emptyBody, BodyChecksum.of(checksum), Instant.now(clock)));

        ReembedService.Outcome outcome = service.reembedOne(DOC_ID);

        // Empty body is still a SUCCESS at the outcome level (we processed it
        // and emitted the ingested event), mirroring IngestionService behaviour.
        assertThat(outcome).isEqualTo(ReembedService.Outcome.SUCCESS);
        verify(chunkRepository).deleteAll(DOC_ID);
        verify(chunkRepository, never()).replaceAll(any(), anyList());
        verify(embeddingPort, never()).embed(anyList());

        ArgumentCaptor<DocumentIngested> cap = ArgumentCaptor.forClass(DocumentIngested.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue().chunkCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // Failed paths
    // -------------------------------------------------------------------------

    @Test
    void reembedOne_failed_when_embedding_port_throws() {
        String body = "alpha beta gamma delta epsilon zeta eta theta iota kappa.";
        String checksum = BodyChecksum.compute(body).value();

        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.of(OWNER));
        when(bodyFetchPort.fetchBody(DOC_ID)).thenReturn(
                new DocumentBody(DOC_ID, body, BodyChecksum.of(checksum), Instant.now(clock)));
        when(embeddingPort.embed(anyList())).thenThrow(
                new RuntimeException("embedding service unavailable"));

        ReembedService.Outcome outcome = service.reembedOne(DOC_ID);

        assertThat(outcome).isEqualTo(ReembedService.Outcome.FAILED);
        verify(chunkRepository, never()).replaceAll(any(), anyList());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void reembedOne_failed_when_chunk_repository_throws_on_replace() {
        String body = "alpha beta gamma delta epsilon zeta eta theta iota kappa.";
        String checksum = BodyChecksum.compute(body).value();

        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.of(OWNER));
        when(bodyFetchPort.fetchBody(DOC_ID)).thenReturn(
                new DocumentBody(DOC_ID, body, BodyChecksum.of(checksum), Instant.now(clock)));
        when(embeddingPort.embed(anyList())).thenAnswer(inv -> {
            List<ChunkText> texts = inv.getArgument(0);
            return texts.stream().map(t -> dummyEmbedding()).toList();
        });
        // replaceAll is a void method — use doThrow
        org.mockito.Mockito.doThrow(new RuntimeException("DB write failure"))
                .when(chunkRepository).replaceAll(any(), anyList());

        ReembedService.Outcome outcome = service.reembedOne(DOC_ID);

        assertThat(outcome).isEqualTo(ReembedService.Outcome.FAILED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void reembedOne_failed_when_service_unavailable_exception_thrown_from_body_fetch() {
        when(chunkRepository.findOwnerMeta(DOC_ID)).thenReturn(Optional.of(OWNER));
        // 5xx that exhausted retries surfaces as ServiceUnavailableException (not NotFoundException).
        com.playground.shared.error.ServiceUnavailableException svcUnavail =
                (com.playground.shared.error.ServiceUnavailableException) ExceptionCreator
                        .of(RagIngestionErrorCode.DOCS_BODY_FETCH_FAILED, DOC_ID.toString())
                        .build();
        when(bodyFetchPort.fetchBody(DOC_ID)).thenThrow(svcUnavail);

        ReembedService.Outcome outcome = service.reembedOne(DOC_ID);

        assertThat(outcome).isEqualTo(ReembedService.Outcome.FAILED);
        verify(chunkRepository, never()).replaceAll(any(), anyList());
        verify(events, never()).publishEvent(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Embedding dummyEmbedding() {
        float[] v = new float[Embedding.DIMENSION];
        v[0] = 0.5f;
        return Embedding.of(v);
    }
}
