package com.playground.ragchat.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** JPA mirror of {@link com.playground.ragchat.domain.model.MessageCitation} per ADR-14 §F. */
@Entity
@Table(name = "message_citations", schema = "chat")
@IdClass(MessageCitationJpaEntity.Pk.class)
public class MessageCitationJpaEntity {

    @Id
    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Id
    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "chunk_index", nullable = false, updatable = false)
    private int chunkIndex;

    protected MessageCitationJpaEntity() {
        // for JPA
    }

    public MessageCitationJpaEntity(UUID messageId, int position, UUID documentId, int chunkIndex) {
        this.messageId = messageId;
        this.position = position;
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public int getPosition() {
        return position;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    /** Composite PK matching the (message_id, position) PRIMARY KEY in DDL. */
    public static class Pk implements Serializable {
        private UUID messageId;
        private int position;

        public Pk() {
            // for JPA
        }

        public Pk(UUID messageId, int position) {
            this.messageId = messageId;
            this.position = position;
        }

        public UUID getMessageId() {
            return messageId;
        }

        public void setMessageId(UUID messageId) {
            this.messageId = messageId;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk other)) return false;
            return position == other.position && Objects.equals(messageId, other.messageId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(messageId, position);
        }
    }
}
