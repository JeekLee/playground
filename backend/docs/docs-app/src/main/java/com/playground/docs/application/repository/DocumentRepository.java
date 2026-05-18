package com.playground.docs.application.repository;

import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import java.util.List;
import java.util.Optional;

/**
 * Repository port per ADR-02 v2 (placement = application layer, return types =
 * domain types). The JPA-backed implementation lives in {@code docs-infra} as
 * {@code DocumentRepositoryImpl}.
 *
 * <p>S1 contract — only the methods the single-author CRUD slice needs.
 * Search / community feed / folder listing land in M2 S2 / S3.
 */
public interface DocumentRepository {

    Optional<Document> findById(DocumentId id);

    /**
     * All documents owned by the supplied author, sorted
     * {@code updated_at DESC} per M2 spec §6.1 row {@code GET /api/docs?scope=mine}.
     */
    List<Document> findAllByAuthor(AuthorId author);

    Document save(Document document);

    void deleteById(DocumentId id);
}
