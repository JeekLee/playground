package com.playground.docs.infrastructure.persistence;

import com.playground.docs.domain.enums.Visibility;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;

/**
 * Hand-written mapper per ADR-02 v2 (MapStruct rejected for M0–M5). Bridges
 * the {@link Document} domain aggregate and the {@link DocumentJpaEntity}
 * persistence mirror.
 */
public final class DocumentMapper {

    private DocumentMapper() {}

    public static Document toDomain(DocumentJpaEntity entity) {
        return new Document(
                DocumentId.of(entity.getId()),
                AuthorId.of(entity.getUserId()),
                DocumentTitle.of(entity.getTitle()),
                DocumentBody.of(entity.getBody()),
                Visibility.fromWire(entity.getVisibility()),
                DocumentPath.of(entity.getPath()),
                entity.getPublishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public static DocumentJpaEntity toEntity(Document doc) {
        return new DocumentJpaEntity(
                doc.id().value(),
                doc.authorId().value(),
                doc.title().value(),
                doc.body().value(),
                doc.visibility().wireValue(),
                doc.path().value(),
                doc.publishedAt(),
                doc.createdAt(),
                doc.updatedAt());
    }

    /** Copy domain-mutable fields onto a managed entity (preserves JPA identity). */
    public static DocumentJpaEntity copyMutable(Document source, DocumentJpaEntity managed) {
        managed.setTitle(source.title().value());
        managed.setBody(source.body().value());
        managed.setVisibility(source.visibility().wireValue());
        managed.setPath(source.path().value());
        managed.setPublishedAt(source.publishedAt());
        managed.setUpdatedAt(source.updatedAt());
        return managed;
    }
}
