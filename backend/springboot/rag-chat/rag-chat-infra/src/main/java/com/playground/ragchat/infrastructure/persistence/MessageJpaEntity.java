package com.playground.ragchat.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mirror of {@link com.playground.ragchat.domain.model.Message} per ADR-14 §F. */
@Entity
@Table(name = "messages", schema = "chat")
public class MessageJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "retrieval_k")
    private Integer retrievalK;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageJpaEntity() {
        // for JPA
    }

    public MessageJpaEntity(
            UUID id,
            UUID sessionId,
            UUID userId,
            String role,
            String content,
            Integer tokensIn,
            Integer tokensOut,
            Integer retrievalK,
            Instant createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.retrievalK = retrievalK;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Integer getTokensIn() {
        return tokensIn;
    }

    public Integer getTokensOut() {
        return tokensOut;
    }

    public Integer getRetrievalK() {
        return retrievalK;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
