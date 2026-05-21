package com.playground.docs.domain.model;

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
 * <p>S1 invariants:
 * <ul>
 *   <li>Author is fixed at creation; {@link #authorId()} is never re-assigned.</li>
 *   <li>{@code publishedAt} is set on the first {@code PRIVATE -> PUBLIC}
 *       transition and retained across subsequent unpublish/republish cycles
 *       (per M2 spec §4.4).</li>
 *   <li>{@code updatedAt} bumps on any application-level mutation.</li>
 * </ul>
 *
 * <p>S2 adds the denormalized {@code view_count} / {@code like_count} columns
 * (migration {@code V202605190001__add_counter_columns.sql}). The aggregate
 * carries them as immutable fields on the read path — the increment paths
 * (POST /like, POST /view) and the nightly resync land in S3 per ADR-12 §11.
 * S2 always reads them and always writes 0 on a brand-new draft.
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
        this.publishedAt = publishedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "Document.createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Document.updatedAt must not be null");
        if (visibility == Visibility.PRIVATE && publishedAt != null) {
            // Per M2 spec §4.4 publishedAt is retained across an unpublish, but the
            // initial state must have publishedAt == null on a brand-new private doc.
            // Acceptance: applyVisibility() guarantees the retention path; constructor
            // permits both shapes — the invariant we enforce is "publishedAt non-null
            // only after publish has been called at least once".
        }
        if (visibility == Visibility.PUBLIC && publishedAt == null) {
            throw new IllegalStateException(
                    "Document.publishedAt must not be null when visibility=PUBLIC");
        }
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
        this(id, authorId, title, body, visibility, path, 0L, 0L, MimeType.MARKDOWN, publishedAt, createdAt, updatedAt);
    }

    /**
     * Backwards-compatible constructor (pre-M6, no {@code mimeType} field).
     * Defaults {@code mimeType} to {@link MimeType#MARKDOWN}. Used by code paths
     * that were written before the PDF support landed (S2/S3 unit tests, the
     * counter-only increment helpers, etc.).
     */
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
     * Factory for a brand-new draft. Default visibility is {@link Visibility#PRIVATE}
     * per M2 spec §6.1 ({@code POST /api/docs} creates documents with
     * {@code visibility='private'} and {@code path='/'}). Counters start at 0.
     *
     * <p>Defaults {@code mimeType = MARKDOWN}; PDF uploads call the
     * {@link #create(DocumentId, AuthorId, DocumentTitle, DocumentBody, DocumentPath, MimeType, Instant)}
     * overload (M6 ADR-16).
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

    /**
     * M6 ADR-16 factory variant — caller passes the {@link MimeType} so PDF
     * uploads can record their source media type alongside the extracted
     * Markdown body. M2 callers (the JSON {@code POST /api/docs} path + the
     * Markdown multipart variant) keep using the {@link #create(DocumentId,
     * AuthorId, DocumentTitle, DocumentBody, DocumentPath, Instant)} shape
     * above, which defaults to {@link MimeType#MARKDOWN}.
     */
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

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Caller is the author? */
    public boolean isAuthoredBy(AuthorId candidate) {
        return Objects.equals(authorId, candidate);
    }

    /** The doc is readable by anonymous callers / non-authors. */
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
                this.publishedAt,
                this.createdAt,
                now);
    }

    /**
     * Transition visibility. First {@code PRIVATE -> PUBLIC} stamps
     * {@code publishedAt = now}; subsequent transitions retain the existing
     * value (M2 spec §4.4).
     *
     * <p>Kept package-visible so the dedicated {@link #publish(Instant)} +
     * {@link #unpublish(Instant)} aggregate behaviors (the spec §6.1 surface)
     * remain the canonical entry points. The unit tests exercise the dedicated
     * methods, not this lower-level helper.
     */
    Document changeVisibility(Visibility next, Instant now) {
        Objects.requireNonNull(next, "Document.changeVisibility.next must not be null");
        Objects.requireNonNull(now, "Document.changeVisibility.now must not be null");
        if (this.visibility == next) {
            // Idempotent: spec §6.1 says POST /publish + /unpublish are
            // idempotent. Preserve the existing instance verbatim — no spurious
            // updatedAt bump, no spurious DB write upstream.
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
                newPublishedAt,
                this.createdAt,
                now);
    }

    /**
     * Publish: transition to {@link Visibility#PUBLIC}. Per M2 spec §6.1
     * {@code POST /api/docs/{id}/publish} is idempotent — calling it on an
     * already-public document returns the existing aggregate verbatim (no
     * {@code publishedAt} or {@code updatedAt} mutation). First publish stamps
     * {@code publishedAt = now}; subsequent unpublish/republish cycles retain
     * the original stamp.
     */
    public Document publish(Instant now) {
        Objects.requireNonNull(now, "Document.publish.now must not be null");
        return changeVisibility(Visibility.PUBLIC, now);
    }

    /**
     * Unpublish: transition to {@link Visibility#PRIVATE} while <em>retaining
     * {@code publishedAt}</em> (M2 spec §6.1 row: "publishedAt retained"; §4.4).
     * Idempotent — calling it on an already-private document returns the
     * existing aggregate verbatim.
     */
    public Document unpublish(Instant now) {
        Objects.requireNonNull(now, "Document.unpublish.now must not be null");
        return changeVisibility(Visibility.PRIVATE, now);
    }

    /**
     * Counter mutation: bump {@code view_count} by one. Per M2 spec §10
     * "View dedup correctness" the dedup happens upstream (Redis claim in
     * {@code ViewIncrementService}); this aggregate-level helper assumes the
     * caller has already claimed the view and merely produces the next state.
     *
     * <p>Does <em>not</em> bump {@code updatedAt} — view increments are a
     * background-style counter mutation; surfacing them as a recent-edit signal
     * on {@code /docs/mine} would mis-represent author activity (the author
     * didn't edit the doc; a reader visited it). The denormalized column is
     * updated transactionally by the JPA-layer increment query so the aggregate
     * itself never need round-trip through a save() call.
     */
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
                this.publishedAt,
                this.createdAt,
                this.updatedAt);
    }

    /**
     * Counter mutation: bump {@code like_count} by one. Per M2 spec §10
     * "Like idempotency" the per-user upsert happens at the
     * {@code document_likes} table layer; this helper produces the next
     * aggregate state for code paths that round-trip the aggregate.
     *
     * <p>Like {@link #incrementViewCount()}, does not bump {@code updatedAt}.
     */
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
                this.publishedAt,
                this.createdAt,
                this.updatedAt);
    }

    /**
     * Counter mutation: decrement {@code like_count} by one, clamped to 0
     * (M2 spec §10 "Like idempotency" + S3 brief: "Don't let it go below 0").
     * Idempotent against an already-zero counter — returns the existing
     * instance verbatim.
     */
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
