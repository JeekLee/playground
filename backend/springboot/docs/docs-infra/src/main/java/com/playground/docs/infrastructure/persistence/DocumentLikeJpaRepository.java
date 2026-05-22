package com.playground.docs.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Native-SQL backed repository for {@code docs.document_likes}. Spring Data
 * JPA doesn't expose a clean {@code INSERT ... ON CONFLICT DO NOTHING}
 * surface, so the toggle methods drop down to {@link EntityManager}'s
 * native query API.
 *
 * <p>Idempotency guarantees come from the composite PK on the table: the
 * {@code ON CONFLICT (document_id, user_id) DO NOTHING} clause turns a
 * duplicate-key error into a "0 rows affected" result the application
 * service can branch on.
 */
@Repository
public class DocumentLikeJpaRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * Insert a like row if not present; returns whether a row was actually
     * inserted.
     */
    @Transactional
    public boolean insertIfAbsent(UUID documentId, UUID userId) {
        int affected = em.createNativeQuery(
                        "INSERT INTO docs.document_likes (document_id, user_id) "
                                + "VALUES (:documentId, :userId) "
                                + "ON CONFLICT (document_id, user_id) DO NOTHING")
                .setParameter("documentId", documentId)
                .setParameter("userId", userId)
                .executeUpdate();
        return affected > 0;
    }

    /** Delete a like row if present; returns whether a row was actually deleted. */
    @Transactional
    public boolean deleteIfPresent(UUID documentId, UUID userId) {
        int affected = em.createNativeQuery(
                        "DELETE FROM docs.document_likes "
                                + "WHERE document_id = :documentId AND user_id = :userId")
                .setParameter("documentId", documentId)
                .setParameter("userId", userId)
                .executeUpdate();
        return affected > 0;
    }

    @Transactional(readOnly = true)
    public boolean existsBy(UUID documentId, UUID userId) {
        Object result = em.createNativeQuery(
                        "SELECT 1 FROM docs.document_likes "
                                + "WHERE document_id = :documentId AND user_id = :userId "
                                + "LIMIT 1")
                .setParameter("documentId", documentId)
                .setParameter("userId", userId)
                .getResultList()
                .stream()
                .findFirst()
                .orElse(null);
        return result != null;
    }

    /**
     * Page-batch lookup: which of {@code documentIds} did this user like?
     * Returns the subset that exist in {@code document_likes}.
     */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<UUID> findLikedDocumentIds(UUID userId, Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        return em.createNativeQuery(
                        "SELECT document_id FROM docs.document_likes "
                                + "WHERE user_id = :userId AND document_id IN (:documentIds)")
                .setParameter("userId", userId)
                .setParameter("documentIds", documentIds)
                .getResultList();
    }
}
