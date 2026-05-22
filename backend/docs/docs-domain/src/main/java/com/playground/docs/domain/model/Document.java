package com.playground.docs.domain.model;

import com.playground.docs.domain.enums.ExtractionStatus;
import com.playground.docs.domain.enums.MimeType;
import com.playground.docs.domain.enums.Visibility;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;
import java.time.Instant;
import java.util.Objects;

/**
 * The {@code Document} aggregate root per M2 spec §4 + ADR-02 v2. POJO — no
 * Spring, no JPA, no Jackson annotations. The
 * {@code com.playground.docs.infrastructure.persistence.DocumentJpaEntity}
 * mirror in -infra carries the persistence concerns; the hand-written
 * {@code DocumentMapper} bridges them.
 *
 * <p>Mutations follow the aggregate-root pattern — they return a new instance
 * rather than mutating in place. The application service writes the new
 * instance through the repository port and the JPA adapter copies the mutable
 * columns onto the managed entity.
 *
 * <p>M6.1 additions (ADR-12 §A12.3 / §A12.4):
 * <ul>
 *   <li>{@code extractionStatus} — the async extraction lifecycle state.</li>
 *   <li>{@code extractionReason} — human-readable failure context (only set on
 *       {@code FAILED}).</li>
 *   <li>{@code sourceObjectKey} — MinIO object key for the original blob.</li>
 *   <li>{@code sourceSizeBytes} — original blob length in bytes (for the
 *       download endpoint's {@code Content-Length}).</li>
 *   <li>{@code sourceMime} — stored multipart media type. Usually mirrors
 *       {@code mimeType.wireValue()} for new uploads.</li>
 * </ul>
 * Pre-M6.1 rows backfill with {@code extractionStatus=EXTRACTED} and null
 * source-blob columns (their bytes were discarded in the pre-MinIO regime).
 */
public final class Document {

    private final DocumentId id;
    private final AuthorId authorId;
    private final DocumentTitle title;
    private final DocumentBody body;
    private final Visibility visibility;
    private final DocumentPath path;
    private final long viewCount;
    private final long likeCount;
    private final MimeType mimeType;
    private final ExtractionStatus extractionStatus;
    private final String extractionReason;
    private final String sourceObjectKey;
    private final Long sourceSizeBytes;
    private final String sourceMime;
    private final Instant publishedAt;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Document(
            DocumentId id,
            AuthorId authorId,
            DocumentTitle title,
            DocumentBody body,
            Visibility visibility,
            DocumentPath path,
            long viewCount,
            long likeCount,
            MimeType mimeType,
            ExtractionStatus extractionStatus,
            String extractionReason,
            String sourceObjectKey,
            Long sourceSizeBytes,
            String sourceMime,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "Document.id must not be null");
        this.authorId = Objects.requireNonNull(authorId, "Document.authorId must not be null");
        this.title = Objects.requireNonNull(title, "Document.title must not be null");
        this.body = Objects.requireNonNull(body, "Document.body must not be null");
        this.visibility = Objects.requireNonNull(visibility, "Document.visibility must not be null");
        this.path = Objects.requireNonNull(path, "Document.path must not be null");
        if (viewCount < 0) {
            throw new IllegalArgumentException("Document.viewCount must be >= 0");
        }
        if (likeCount < 0) {
            throw new IllegalArgumentException("Document.likeCount must be >= 0");
        }
        this.viewCount = viewCount;
        this.likeCount = likeCount;
        this.mimeType = mimeType == null ? MimeType.MARKDOWN : mimeType;
        this.extractionStatus = extractionStatus == null ? ExtractionStatus.EXTRACTED : extractionStatus;
        this.extractionReason = extractionReason;
        this.sourceObjectKey = sourceObjectKey;
        this.sourceSizeBytes = sourceSizeBytes;
        this.sourceMime = sourceMime;
        this.publishedAt = publishedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "Document.createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Document.updatedAt must not be null");
        if (visibility == Visibility.PUBLIC && publishedAt == null) {
            throw new IllegalStateException(
                    "Document.publishedAt must not be null when visibility=PUBLIC");
        }
    }

    /** Backwards-compatible constructor — defaults the M6.1 extraction columns. */
    public Document(
            DocumentId id,
            AuthorId authorId,
            DocumentTitle title,
            DocumentBody body,
            Visibility visibility,
            DocumentPath path,
            long viewCount,
            long likeCount,
            MimeType mimeType,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
        this(id, authorId, title, body, visibility, path, viewCount, likeCount, mimeType,
                ExtractionStatus.EXTRACTED, null, null, null, null,
                publishedAt, createdAt, updatedAt);
    }

    /**
     * Backwards-compatible constructor (no counter fields) used by tests that
     * pre-date the S2 counter columns. Defaults {@code viewCount} and
     * {@code likeCount} to 0.
     */
    public Document(
            DocumentId id,
            AuthorId authorId,
            DocumentTitle title,
            DocumentBody body,
            Visibility visibility,
            DocumentPath path,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
        this(id, authorId, title, body, visibility, path, 0L, 0L, MimeType.MARKDOWN,
                publishedAt, createdAt, updatedAt);
    }

    /** Pre-M6 constructor (no mimeType). Defaults to MARKDOWN. */
    public Document(
            DocumentId id,
            AuthorId authorId,
            DocumentTitle title,
            DocumentBody body,
            Visibility visibility,
            DocumentPath path,
            long viewCount,
            long likeCount,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
        this(id, authorId, title, body, visibility, path, viewCount, likeCount, MimeType.MARKDOWN,
                publishedAt, createdAt, updatedAt);
    }

    /**
     * Factory for a brand-new draft — synchronous path. Defaults to
     * {@link ExtractionStatus#EXTRACTED} (the body is already provided).
     */
    public static Document create(
            DocumentId id,
            AuthorId authorId,
            DocumentTitle title,
            DocumentBody body,
            DocumentPath path,
            Instant now) {
        return create(id, authorId, title, body, path, MimeType.MARKDOWN, now);
    }

    /** M6 factory variant — synchronous path with explicit mime type. */
    public static Document create(
            DocumentId id,
            AuthorId authorId,
            DocumentTitle title,
            DocumentBody body,
            DocumentPath path,
            MimeType mimeType,
            Instant now) {
        Objects.requireNonNull(now, "Document.create.now must not be null");
        return new Document(
                id,
                authorId,
                title,
                body,
                Visibility.PRIVATE,
                path,
                0L,
                0L,
                mimeType == null ? MimeType.MARKDOWN : mimeType,
                ExtractionStatus.EXTRACTED,
                null,
                null,
                null,
                null,
                null,
                now,
                now);
    }

    /**
     * M6.1 factory — async extraction path. The body starts empty, the
     * document is INSERTed with {@link ExtractionStatus#PENDING_EXTRACTION},
     * the source-blob columns are stamped from the MinIO upload, and the
     * extraction worker materializes the body asynchronously.
     */
    public static Document createPendingExtraction(
            DocumentId id,
            AuthorId authorId,
            DocumentTitle title,
            DocumentPath path,
            MimeType mimeType,
            String sourceObjectKey,
            long sourceSizeBytes,
            String sourceMime,
            Instant now) {
        Objects.requireNonNull(now, "Document.createPendingExtraction.now must not be null");
        Objects.requireNonNull(sourceObjectKey, "sourceObjectKey must not be null for async-extraction uploads");
        return new Document(
                id,
                authorId,
                title,
                DocumentBody.empty(),
                Visibility.PRIVATE,
                path,
                0L,
                0L,
                mimeType == null ? MimeType.MARKDOWN : mimeType,
                ExtractionStatus.PENDING_EXTRACTION,
                null,
                sourceObjectKey,
                sourceSizeBytes,
                sourceMime,
                null,
                now,
                now);
    }

    public DocumentId id() {
        return id;
    }

    public AuthorId authorId() {
        return authorId;
    }

    public DocumentTitle title() {
        return title;
    }

    public DocumentBody body() {
        return body;
    }

    public Visibility visibility() {
        return visibility;
    }

    public DocumentPath path() {
        return path;
    }

    public long viewCount() {
        return viewCount;
    }

    public long likeCount() {
        return likeCount;
    }

    public MimeType mimeType() {
        return mimeType;
    }

    public ExtractionStatus extractionStatus() {
        return extractionStatus;
    }

    public String extractionReason() {
        return extractionReason;
    }

    public String sourceObjectKey() {
        return sourceObjectKey;
    }

    public Long sourceSizeBytes() {
        return sourceSizeBytes;
    }

    public String sourceMime() {
        return sourceMime;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isAuthoredBy(AuthorId candidate) {
        return Objects.equals(authorId, candidate);
    }

    public boolean isPublic() {
        return visibility == Visibility.PUBLIC;
    }

    /**
     * Returns a new aggregate with the supplied fields replaced. Nulls mean
     * "leave unchanged". Visibility is handled via
     * {@link #changeVisibility(Visibility, Instant)} because it carries the
     * {@code publishedAt} side effect.
     */
    public Document edit(
            DocumentTitle newTitle,
            DocumentBody newBody,
            DocumentPath newPath,
            Instant now) {
        Objects.requireNonNull(now, "Document.edit.now must not be null");
        return new Document(
                this.id,
                this.authorId,
                newTitle == null ? this.title : newTitle,
                newBody == null ? this.body : newBody,
                this.visibility,
                newPath == null ? this.path : newPath,
                this.viewCount,
                this.likeCount,
                this.mimeType,
                this.extractionStatus,
                this.extractionReason,
                this.sourceObjectKey,
                this.sourceSizeBytes,
                this.sourceMime,
                this.publishedAt,
                this.createdAt,
                now);
    }

    /**
     * M6.1 — record an extraction transition. Sets the new status, optional
     * reason (only meaningful on {@code FAILED}), replaces the body (typically
     * non-empty on {@code EXTRACTED}; pass {@code null} to leave unchanged),
     * and bumps {@code updatedAt}.
     */
    public Document withExtraction(
            ExtractionStatus newStatus,
            String reason,
            DocumentBody newBody,
            Instant now) {
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new Document(
                this.id,
                this.authorId,
                this.title,
                newBody == null ? this.body : newBody,
                this.visibility,
                this.path,
                this.viewCount,
                this.likeCount,
                this.mimeType,
                newStatus,
                reason,
                this.sourceObjectKey,
                this.sourceSizeBytes,
                this.sourceMime,
                this.publishedAt,
                this.createdAt,
                now);
    }

    Document changeVisibility(Visibility next, Instant now) {
        Objects.requireNonNull(next, "Document.changeVisibility.next must not be null");
        Objects.requireNonNull(now, "Document.changeVisibility.now must not be null");
        if (this.visibility == next) {
            return this;
        }
        Instant newPublishedAt = this.publishedAt;
        if (next == Visibility.PUBLIC && this.publishedAt == null) {
            newPublishedAt = now;
        }
        return new Document(
                this.id,
                this.authorId,
                this.title,
                this.body,
                next,
                this.path,
                this.viewCount,
                this.likeCount,
                this.mimeType,
                this.extractionStatus,
                this.extractionReason,
                this.sourceObjectKey,
                this.sourceSizeBytes,
                this.sourceMime,
                newPublishedAt,
                this.createdAt,
                now);
    }

    public Document publish(Instant now) {
        Objects.requireNonNull(now, "Document.publish.now must not be null");
        return changeVisibility(Visibility.PUBLIC, now);
    }

    public Document unpublish(Instant now) {
        Objects.requireNonNull(now, "Document.unpublish.now must not be null");
        return changeVisibility(Visibility.PRIVATE, now);
    }

    public Document incrementViewCount() {
        return new Document(
                this.id,
                this.authorId,
                this.title,
                this.body,
                this.visibility,
                this.path,
                this.viewCount + 1,
                this.likeCount,
                this.mimeType,
                this.extractionStatus,
                this.extractionReason,
                this.sourceObjectKey,
                this.sourceSizeBytes,
                this.sourceMime,
                this.publishedAt,
                this.createdAt,
                this.updatedAt);
    }

    public Document incrementLikeCount() {
        return new Document(
                this.id,
                this.authorId,
                this.title,
                this.body,
                this.visibility,
                this.path,
                this.viewCount,
                this.likeCount + 1,
                this.mimeType,
                this.extractionStatus,
                this.extractionReason,
                this.sourceObjectKey,
                this.sourceSizeBytes,
                this.sourceMime,
                this.publishedAt,
                this.createdAt,
                this.updatedAt);
    }

    public Document decrementLikeCount() {
        if (this.likeCount <= 0) {
            return this;
        }
        return new Document(
                this.id,
                this.authorId,
                this.title,
                this.body,
                this.visibility,
                this.path,
                this.viewCount,
                this.likeCount - 1,
                this.mimeType,
                this.extractionStatus,
                this.extractionReason,
                this.sourceObjectKey,
                this.sourceSizeBytes,
                this.sourceMime,
                this.publishedAt,
                this.createdAt,
                this.updatedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Document other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
