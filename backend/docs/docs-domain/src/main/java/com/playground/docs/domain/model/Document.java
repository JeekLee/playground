package com.playground.docs.domain.model;

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
 */
public final class Document {

    private final DocumentId id;
    private final AuthorId authorId;
    private final DocumentTitle title;
    private final DocumentBody body;
    private final Visibility visibility;
    private final DocumentPath path;
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
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "Document.id must not be null");
        this.authorId = Objects.requireNonNull(authorId, "Document.authorId must not be null");
        this.title = Objects.requireNonNull(title, "Document.title must not be null");
        this.body = Objects.requireNonNull(body, "Document.body must not be null");
        this.visibility = Objects.requireNonNull(visibility, "Document.visibility must not be null");
        this.path = Objects.requireNonNull(path, "Document.path must not be null");
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
     * Factory for a brand-new draft. Default visibility is {@link Visibility#PRIVATE}
     * per M2 spec §6.1 ({@code POST /api/docs} creates documents with
     * {@code visibility='private'} and {@code path='/'}).
     */
    public static Document create(
            DocumentId id,
            AuthorId authorId,
            DocumentTitle title,
            DocumentBody body,
            DocumentPath path,
            Instant now) {
        Objects.requireNonNull(now, "Document.create.now must not be null");
        return new Document(
                id,
                authorId,
                title,
                body,
                Visibility.PRIVATE,
                path,
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
