package com.playground.docs.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.docs.application.repository.DocumentLikeRepository;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DocumentLikeService} per M2 spec §6.1 + §10 +
 * ADR-12 §11.
 *
 * <p>Two invariants the service must hold:
 * <ol>
 *   <li>Idempotency: a like row's existence dictates whether the counter
 *       bumps. Repeating POST /like does not double-bump.</li>
 *   <li>Counter parity with the source-of-truth table: the {@code like_count}
 *       column is only mutated when {@code document_likes} actually changed.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DocumentLikeServiceTest {

    @Mock
    DocumentRepository documentRepository;
    @Mock
    DocumentLikeRepository likeRepository;

    private DocumentLikeService service;

    @BeforeEach
    void setUp() {
        service = new DocumentLikeService(documentRepository, likeRepository);
    }

    @Test
    void like_inserts_row_and_bumps_counter_on_first_call() {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(newDoc(docId)));
        when(likeRepository.insertIfAbsent(id, AuthorId.of(caller))).thenReturn(true);

        service.like(docId, caller);

        verify(likeRepository).insertIfAbsent(id, AuthorId.of(caller));
        verify(documentRepository).incrementLikeCount(id);
    }

    @Test
    void like_is_idempotent_no_counter_bump_on_repeat() {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(newDoc(docId)));
        when(likeRepository.insertIfAbsent(id, AuthorId.of(caller))).thenReturn(false);

        service.like(docId, caller);

        // Repository upsert reported no insert (already liked) → no counter bump.
        verify(documentRepository, never()).incrementLikeCount(id);
    }

    @Test
    void unlike_decrements_counter_when_row_existed() {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(newDoc(docId)));
        when(likeRepository.deleteIfPresent(id, AuthorId.of(caller))).thenReturn(true);

        service.unlike(docId, caller);

        verify(documentRepository).decrementLikeCount(id);
    }

    @Test
    void unlike_is_idempotent_no_decrement_when_row_absent() {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(newDoc(docId)));
        when(likeRepository.deleteIfPresent(id, AuthorId.of(caller))).thenReturn(false);

        service.unlike(docId, caller);

        verify(documentRepository, never()).decrementLikeCount(id);
    }

    @Test
    void like_returns_404_when_document_missing() {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.like(docId, caller))
                .isInstanceOf(DocumentNotFoundException.class);
        verify(likeRepository, never()).insertIfAbsent(id, AuthorId.of(caller));
        verify(documentRepository, never()).incrementLikeCount(id);
    }

    @Test
    void unlike_returns_404_when_document_missing() {
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlike(docId, caller))
                .isInstanceOf(DocumentNotFoundException.class);
        verify(likeRepository, never()).deleteIfPresent(id, AuthorId.of(caller));
        verify(documentRepository, never()).decrementLikeCount(id);
    }

    @Test
    void like_twice_then_unlike_once_lands_at_zero() {
        // Counter parity walk-through: first like → +1, second like (idempotent) → +0,
        // unlike → -1. Final delta to like_count: 0. Matches the spec §10 idempotency NFR.
        UUID docId = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(newDoc(docId)));
        // Stubbed in order: first insertIfAbsent → true, second → false (idempotent),
        // deleteIfPresent → true (we did insert it).
        when(likeRepository.insertIfAbsent(id, AuthorId.of(caller)))
                .thenReturn(true).thenReturn(false);
        when(likeRepository.deleteIfPresent(id, AuthorId.of(caller))).thenReturn(true);

        service.like(docId, caller);
        service.like(docId, caller);
        service.unlike(docId, caller);

        verify(documentRepository, times(1)).incrementLikeCount(id);
        verify(documentRepository, times(1)).decrementLikeCount(id);
    }

    private Document newDoc(UUID docId) {
        return Document.create(
                DocumentId.of(docId),
                AuthorId.of(UUID.randomUUID()),
                DocumentTitle.of("Title"),
                DocumentBody.of("body"),
                DocumentPath.ROOT,
                Instant.parse("2026-05-18T00:00:00Z"));
    }
}
