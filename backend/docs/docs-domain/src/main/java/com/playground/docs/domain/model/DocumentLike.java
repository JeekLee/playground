package com.playground.docs.domain.model;

import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.time.Instant;
import java.util.Objects;

/**
 * The {@code DocumentLike} aggregate per M2 spec §4.1 — a per-user like row
 * on {@code docs.document_likes}. Composite identity {@code (document_id,
 * user_id)} gives idempotent toggle at the DB layer (the per-milestone ADR-12
 * §11 brief: {@code INSERT ... ON CONFLICT DO NOTHING}).
 *
 * <p>POJO — no Spring, no JPA. The infrastructure layer mirrors the row shape
 * via its own {@code DocumentLikeJpaEntity}.
 *
 * @param documentId aggregate root reference (FK → docs.documents.id, cascade on doc delete)
 * @param userId     liker's identity id (app-level FK to identity.users — no DB constraint per ADR-12 §8)
 * @param likedAt    DB-default timestamp when the row landed
 */
public record DocumentLike(DocumentId documentId, AuthorId userId, Instant likedAt) {

    public DocumentLike {
        Objects.requireNonNull(documentId, "DocumentLike.documentId must not be null");
        Objects.requireNonNull(userId, "DocumentLike.userId must not be null");
        Objects.requireNonNull(likedAt, "DocumentLike.likedAt must not be null");
    }
}
