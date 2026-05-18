package com.playground.docs.infrastructure.persistence;

import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/** JPA-backed adapter satisfying {@link DocumentRepository}. */
@Repository
public class DocumentRepositoryImpl implements DocumentRepository {

    private final DocumentJpaRepository jpaRepository;

    public DocumentRepositoryImpl(DocumentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Document> findById(DocumentId id) {
        return jpaRepository.findById(id.value()).map(DocumentMapper::toDomain);
    }

    @Override
    public List<Document> findAllByAuthor(AuthorId author) {
        return jpaRepository.findAllByUserOrderedForMine(author.value()).stream()
                .map(DocumentMapper::toDomain)
                .toList();
    }

    @Override
    public List<Document> findPublicFeed(Instant cursorPublishedAt, UUID cursorId, int limit) {
        List<DocumentJpaEntity> rows = (cursorPublishedAt == null || cursorId == null)
                ? jpaRepository.findPublicFeedFirstPage(Limit.of(limit))
                : jpaRepository.findPublicFeedAfter(cursorPublishedAt, cursorId, Limit.of(limit));
        return rows.stream().map(DocumentMapper::toDomain).toList();
    }

    @Override
    public List<Document> findPublicFeedByAuthor(
            AuthorId author, Instant cursorPublishedAt, UUID cursorId, int limit) {
        List<DocumentJpaEntity> rows = (cursorPublishedAt == null || cursorId == null)
                ? jpaRepository.findPublicFeedByAuthorFirstPage(author.value(), Limit.of(limit))
                : jpaRepository.findPublicFeedByAuthorAfter(
                        author.value(), cursorPublishedAt, cursorId, Limit.of(limit));
        return rows.stream().map(DocumentMapper::toDomain).toList();
    }

    @Override
    public Document save(Document document) {
        DocumentJpaEntity entity = jpaRepository.findById(document.id().value())
                .map(managed -> DocumentMapper.copyMutable(document, managed))
                .orElseGet(() -> DocumentMapper.toEntity(document));
        DocumentJpaEntity persisted = jpaRepository.save(entity);
        return DocumentMapper.toDomain(persisted);
    }

    @Override
    public void deleteById(DocumentId id) {
        jpaRepository.deleteById(id.value());
    }
}
