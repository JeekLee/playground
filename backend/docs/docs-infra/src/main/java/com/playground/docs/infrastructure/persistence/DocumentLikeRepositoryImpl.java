package com.playground.docs.infrastructure.persistence;

import com.playground.docs.application.repository.DocumentLikeRepository;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** JPA-backed adapter satisfying {@link DocumentLikeRepository}. */
@Repository
public class DocumentLikeRepositoryImpl implements DocumentLikeRepository {

    private final DocumentLikeJpaRepository jpa;

    public DocumentLikeRepositoryImpl(DocumentLikeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean insertIfAbsent(DocumentId documentId, AuthorId userId) {
        return jpa.insertIfAbsent(documentId.value(), userId.value());
    }

    @Override
    public boolean deleteIfPresent(DocumentId documentId, AuthorId userId) {
        return jpa.deleteIfPresent(documentId.value(), userId.value());
    }

    @Override
    public boolean existsBy(DocumentId documentId, AuthorId userId) {
        return jpa.existsBy(documentId.value(), userId.value());
    }

    @Override
    public Set<UUID> findLikedDocumentIds(AuthorId userId, Collection<UUID> documentIds) {
        return new HashSet<>(jpa.findLikedDocumentIds(userId.value(), documentIds));
    }
}
