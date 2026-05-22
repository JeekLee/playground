package com.playground.docs.infrastructure.persistence;

import com.playground.docs.domain.enums.ExtractionStatus;
import com.playground.docs.domain.enums.MimeType;
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
                entity.getViewCount(),
                entity.getLikeCount(),
                MimeType.fromWire(entity.getMimeType()),
                ExtractionStatus.fromWire(entity.getExtractionStatus()),
                entity.getExtractionReason(),
                entity.getSourceObjectKey(),
                entity.getSourceSizeBytes(),
                entity.getSourceMime(),
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
                doc.viewCount(),
                doc.likeCount(),
                doc.mimeType().wireValue(),
                doc.extractionStatus().wireValue(),
                doc.extractionReason(),
                doc.sourceObjectKey(),
                doc.sourceSizeBytes(),
                doc.sourceMime(),
                doc.publishedAt(),
                doc.createdAt(),
                doc.updatedAt());
    }

    /**
     * Copy domain-mutable fields onto a managed entity (preserves JPA identity).
     * The counters are NOT copied here — they're maintained transactionally with
     * the originating mutation (S3) and would otherwise get clobbered when an
     * unrelated edit reads-modifies-writes the aggregate.
     */
    public static DocumentJpaEntity copyMutable(Document source, DocumentJpaEntity managed) {
        managed.setTitle(source.title().value());
        managed.setBody(source.body().value());
        managed.setVisibility(source.visibility().wireValue());
        managed.setPath(source.path().value());
        managed.setMimeType(source.mimeType().wireValue());
        managed.setExtractionStatus(source.extractionStatus().wireValue());
        managed.setExtractionReason(source.extractionReason());
        managed.setSourceObjectKey(source.sourceObjectKey());
        managed.setSourceSizeBytes(source.sourceSizeBytes());
        managed.setSourceMime(source.sourceMime());
        managed.setPublishedAt(source.publishedAt());
        managed.setUpdatedAt(source.updatedAt());
        return managed;
    }
}
