package com.playground.docs.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mirror of {@link com.playground.docs.domain.model.Document} per
 * ADR-02 v2. Mapping to/from the domain type lives in {@link DocumentMapper}.
 *
 * <p>Schema {@code docs.documents} columns mirror the M2 spec §4.1 DDL
 * (S1 subset — no {@code view_count} / {@code like_count}, no
 * {@code document_likes} FK; those land in M2 S2).
 */
@Entity
@Table(name = "documents", schema = "docs")
public class DocumentJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "visibility", nullable = false)
    private String visibility;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "like_count", nullable = false)
    private long likeCount;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentJpaEntity() {
        // for JPA
    }

    public DocumentJpaEntity(
            UUID id,
            UUID userId,
            String title,
            String body,
            String visibility,
            String path,
            long viewCount,
            long likeCount,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.body = body;
        this.visibility = visibility;
        this.path = path;
        this.viewCount = viewCount;
        this.likeCount = likeCount;
        this.publishedAt = publishedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getVisibility() {
        return visibility;
    }

    public String getPath() {
        return path;
    }

    public long getViewCount() {
        return viewCount;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setViewCount(long viewCount) {
        this.viewCount = viewCount;
    }

    public void setLikeCount(long likeCount) {
        this.likeCount = likeCount;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
