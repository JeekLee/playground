package com.playground.ragingestion.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.ragingestion.application.dto.DocumentBody;
import com.playground.ragingestion.application.port.BodyFetchPort;
import com.playground.ragingestion.application.port.DistributedLockPort;
import com.playground.ragingestion.application.port.EmbeddingPort;
import com.playground.ragingestion.application.repository.ChunkRepository;
import com.playground.ragingestion.domain.enums.Visibility;
import com.playground.ragingestion.domain.event.DocumentDeletedEvent;
import com.playground.ragingestion.domain.event.DocumentIngested;
import com.playground.ragingestion.domain.event.DocumentUploadedEvent;
import com.playground.ragingestion.domain.event.DocumentVisibilityChangedEvent;
import com.playground.ragingestion.domain.model.DocumentChunk;
import com.playground.ragingestion.domain.model.id.AuthorId;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import com.playground.ragingestion.domain.model.vo.Embedding;
import org.assertj.core.api.SoftAssertions;
import com.playground.ragingestion.domain.service.ChunkingPolicy;
import com.playground.ragingestion.domain.service.JdkBreakIteratorSentenceSplitter;
import com.playground.ragingestion.domain.service.MarkdownAwareChunker;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

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

    Clock clock;
    MarkdownAwareChunker chunker;
    IngestionService service;

    @BeforeEach
    void setup() {
        clock = Clock.fixed(Instant.parse("2026-05-20T12:00:00Z"), ZoneOffset.UTC);
        // Small chunker so a short test body still produces multiple chunks.
        chunker = new MarkdownAwareChunker(
                new ChunkingPolicy(8, 2, 2, "cl100k-base", 8, true),
                new JdkBreakIteratorSentenceSplitter());
        service = new IngestionService(
                chunkRepository, bodyFetchPort, embeddingPort, lockPort, chunker, events, clock);
        // The lock adapter runs the supplier inline for unit tests.
        when(lockPort.runWithLock(any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(3);
            return work.get();
        });
    }

    @Test
    void handleUploaded_short_circuits_when_body_checksum_unchanged() {
        DocumentId docId = DocumentId.of(UUID.randomUUID());
        AuthorId userId = AuthorId.of(UUID.randomUUID());
        String checksum = BodyChecksum.compute("unchanged body").value();
        DocumentUploadedEvent event = new DocumentUploadedEvent(
                docId, userId, Visibility.PRIVATE, "title", "/", checksum);

        when(chunkRepository.findBodyChecksum(docId))
                .thenReturn(Optional.of(BodyChecksum.of(checksum)));
        when(chunkRepository.countByDocument(docId)).thenReturn(7);

        service.handleUploaded(event);

        // No body fetch, no embedding — the whole pipeline short-circuits.
        verify(bodyFetchPort, never()).fetchBody(any());
        verify(embeddingPort, never()).embed(anyList());
        verify(chunkRepository, never()).replaceAll(any(), anyList());

        // Defensive re-emission still happens per ADR-13 §12 step 3.
        ArgumentCaptor<DocumentIngested> emitted = ArgumentCaptor.forClass(DocumentIngested.class);
        verify(events).publishEvent(emitted.capture());
        assertThat(emitted.getValue().documentId()).isEqualTo(docId);
        assertThat(emitted.getValue().chunkCount()).isEqualTo(7);
        assertThat(emitted.getValue().bodyChecksum()).isEqualTo(checksum);
    }

    @Test
    void handleUploaded_full_pipeline_chunks_embeds_and_replaces() {
        DocumentId docId = DocumentId.of(UUID.randomUUID());
        AuthorId userId = AuthorId.of(UUID.randomUUID());
        String body = "alpha beta gamma delta epsilon zeta eta theta iota kappa "
                + "lambda mu nu xi omicron pi rho sigma tau upsilon.";
        String checksum = BodyChecksum.compute(body).value();

        when(chunkRepository.findBodyChecksum(docId)).thenReturn(Optional.empty());
        when(bodyFetchPort.fetchBody(docId)).thenReturn(new DocumentBody(
                docId, body, BodyChecksum.of(checksum), Instant.now(clock)));
        when(embeddingPort.embed(anyList())).thenAnswer(invocation -> {
            List<ChunkText> texts = invocation.getArgument(0);
            return texts.stream().map(t -> dummyEmbedding(1.0f)).toList();
        });

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                docId, userId, Visibility.PUBLIC, "title", "/", checksum);
        service.handleUploaded(event);

        ArgumentCaptor<List<DocumentChunk>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).replaceAll(eq(docId), chunkCaptor.capture());
        List<DocumentChunk> chunks = chunkCaptor.getValue();
        assertThat(chunks).isNotEmpty();
        chunks.forEach(c -> {
            assertThat(c.documentId()).isEqualTo(docId);
            assertThat(c.userId()).isEqualTo(userId);
            assertThat(c.visibility()).isEqualTo(Visibility.PUBLIC);
            assertThat(c.bodyChecksum().value()).isEqualTo(checksum);
        });

        ArgumentCaptor<DocumentIngested> emitted = ArgumentCaptor.forClass(DocumentIngested.class);
        verify(events).publishEvent(emitted.capture());
        assertThat(emitted.getValue().chunkCount()).isEqualTo(chunks.size());
        assertThat(emitted.getValue().visibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void handleUploaded_empty_body_purges_and_emits_zero_chunk_event() {
        DocumentId docId = DocumentId.of(UUID.randomUUID());
        AuthorId userId = AuthorId.of(UUID.randomUUID());
        String checksum = BodyChecksum.compute("").value();

        when(chunkRepository.findBodyChecksum(docId)).thenReturn(Optional.empty());
        when(bodyFetchPort.fetchBody(docId)).thenReturn(new DocumentBody(
                docId, "", BodyChecksum.of(checksum), Instant.now(clock)));

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                docId, userId, Visibility.PRIVATE, "empty", "/", checksum);
        service.handleUploaded(event);

        verify(chunkRepository).deleteAll(docId);
        verify(embeddingPort, never()).embed(anyList());
        verify(chunkRepository, never()).replaceAll(any(), anyList());

        ArgumentCaptor<DocumentIngested> emitted = ArgumentCaptor.forClass(DocumentIngested.class);
        verify(events).publishEvent(emitted.capture());
        assertThat(emitted.getValue().chunkCount()).isZero();
    }

    @Test
    void handleVisibilityChanged_updates_without_body_fetch_or_embedding() {
        DocumentId docId = DocumentId.of(UUID.randomUUID());
        AuthorId userId = AuthorId.of(UUID.randomUUID());
        DocumentVisibilityChangedEvent event = new DocumentVisibilityChangedEvent(
                docId, userId, Visibility.PRIVATE, Visibility.PUBLIC, Instant.now(clock));

        when(chunkRepository.updateVisibility(docId, Visibility.PUBLIC)).thenReturn(5);

        service.handleVisibilityChanged(event);

        verify(chunkRepository).updateVisibility(docId, Visibility.PUBLIC);
        verify(bodyFetchPort, never()).fetchBody(any());
        verify(embeddingPort, never()).embed(anyList());
        // ADR-13 §3 — visibility-changed does not emit DocumentIngested.
        verify(events, never()).publishEvent(any());
    }

    @Test
    void handleDeleted_purges_without_emitting_event() {
        DocumentId docId = DocumentId.of(UUID.randomUUID());
        AuthorId userId = AuthorId.of(UUID.randomUUID());
        DocumentDeletedEvent event = new DocumentDeletedEvent(docId, userId);

        when(chunkRepository.deleteAll(docId)).thenReturn(3);
        service.handleDeleted(event);

        verify(chunkRepository).deleteAll(docId);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void handleUploaded_acquires_per_document_lock() {
        DocumentId docId = DocumentId.of(UUID.randomUUID());
        AuthorId userId = AuthorId.of(UUID.randomUUID());
        String checksum = BodyChecksum.compute("body").value();
        when(chunkRepository.findBodyChecksum(docId))
                .thenReturn(Optional.of(BodyChecksum.of(checksum)));
        when(chunkRepository.countByDocument(docId)).thenReturn(0);

        service.handleUploaded(new DocumentUploadedEvent(
                docId, userId, Visibility.PRIVATE, "t", "/", checksum));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(lockPort, times(1)).runWithLock(keyCaptor.capture(), any(), any(), any());
        assertThat(keyCaptor.getValue()).isEqualTo("document:" + docId.value());
    }

    @Test
    void handleUploaded_propagates_heading_path_from_draft_to_chunk() {
        DocumentId docId = DocumentId.of(UUID.randomUUID());
        AuthorId userId = AuthorId.of(UUID.randomUUID());
        // Markdown body with a heading so SectionBuilder assigns headingPath = ["Alpha"].
        // The body under the heading is long enough to produce at least one chunk with
        // the small ChunkingPolicy(8 tokens) used in setup().
        String body = "# Alpha\n\n"
                + "one two three four five six seven eight nine ten eleven twelve thirteen.";
        String checksum = BodyChecksum.compute(body).value();

        when(chunkRepository.findBodyChecksum(docId)).thenReturn(Optional.empty());
        when(bodyFetchPort.fetchBody(docId)).thenReturn(new DocumentBody(
                docId, body, BodyChecksum.of(checksum), Instant.now(clock)));
        when(embeddingPort.embed(anyList())).thenAnswer(invocation -> {
            List<ChunkText> texts = invocation.getArgument(0);
            return texts.stream().map(t -> dummyEmbedding(1.0f)).toList();
        });

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                docId, userId, Visibility.PUBLIC, "heading-test", "/", checksum);
        service.handleUploaded(event);

        ArgumentCaptor<List<DocumentChunk>> cap = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).replaceAll(eq(docId), cap.capture());
        List<DocumentChunk> chunks = cap.getValue();
        assertThat(chunks).isNotEmpty();

        SoftAssertions soft = new SoftAssertions();
        chunks.forEach(c ->
                soft.assertThat(c.headingPath())
                        .as("headingPath for chunk index %d", chunks.indexOf(c))
                        .containsExactly("Alpha"));
        soft.assertAll();
    }

    private static Embedding dummyEmbedding(float value) {
        float[] v = new float[Embedding.DIMENSION];
        v[0] = value;
        return Embedding.of(v);
    }
}
