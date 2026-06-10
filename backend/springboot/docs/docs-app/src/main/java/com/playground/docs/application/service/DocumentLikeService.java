package com.playground.docs.application.service;

import com.playground.docs.application.repository.DocumentLikeRepository;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case service for the like-toggle endpoints per M2 spec §6.1 +
 * §10 "Like idempotency" + ADR-12 §11.
 *
 * <p>Both surfaces ({@code POST} / {@code DELETE /api/docs/{id}/like}) are
 * idempotent — repeat invocations from the same user leave the counter
 * unchanged. The denormalized {@code like_count} on {@code docs.documents}
 * is maintained transactionally with the originating insert/delete so a
 * concurrent request always sees the correct row state under SERIALIZABLE
 * isolation (Postgres default READ COMMITTED is sufficient here because the
 * row-level lock on the {@code documents} row serializes the increment).
 *
 * <p>Per spec §6.1 likes are allowed on any visibility (UI gates the button
 * to public docs; the server keeps the contract simple). The author is
 * permitted to like their own document.
 */
@Service
@RequiredArgsConstructor
public class DocumentLikeService {

    private final DocumentRepository documentRepository;
    private final DocumentLikeRepository likeRepository;

    /**
     * Per M2 spec §6.1 row {@code POST /api/docs/{id}/like}. Idempotent
     * upsert; returns 204 either way. The {@code like_count} bump runs in
     * the same transaction as the insert so the denormalized counter and
     * the source-of-truth row stay in lockstep.
     *
     * @param documentId the document UUID
     * @param callerId   the authenticated caller's user UUID
     * @throws DocumentNotFoundException when the document doesn't exist (404)
     */
    @Transactional
    public void like(UUID documentId, UUID callerId) {
        DocumentId id = DocumentId.of(documentId);
        // Existence gate so a like on a deleted/garbage UUID returns 404
        // rather than silently inserting an orphan row. The FK has
        // ON DELETE CASCADE so concurrent deletes won't strand the like
        // row, but the existence check is cheap and gives the right HTTP
        // shape per spec §6.5.
        if (documentRepository.findById(id).isEmpty()) {
            throw new DocumentNotFoundException(id);
        }
        AuthorId user = AuthorId.of(callerId);
        boolean inserted = likeRepository.insertIfAbsent(id, user);
        if (inserted) {
            documentRepository.incrementLikeCount(id);
        }
    }

    /**
     * Per M2 spec §6.1 row {@code DELETE /api/docs/{id}/like}. Idempotent
     * delete. The {@code like_count} decrement clamps to 0 at the SQL layer
     * via {@code GREATEST(like_count - 1, 0)} so a partial-failure drift
     * (manual SQL delete of a like row outside this app, for instance)
     * cannot push the denormalized counter negative.
     */
    @Transactional
    public void unlike(UUID documentId, UUID callerId) {
        DocumentId id = DocumentId.of(documentId);
        if (documentRepository.findById(id).isEmpty()) {
            throw new DocumentNotFoundException(id);
        }
        AuthorId user = AuthorId.of(callerId);
        boolean deleted = likeRepository.deleteIfPresent(id, user);
        if (deleted) {
            documentRepository.decrementLikeCount(id);
        }
    }
}
