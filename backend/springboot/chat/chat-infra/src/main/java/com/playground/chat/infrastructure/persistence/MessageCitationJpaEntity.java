package com.playground.chat.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mirror of {@link com.playground.chat.domain.model.MessageCitation} (SP3b
 * spec D6). Manual mirror only — real read/write goes through
 * {@link MessageRepositoryJdbcAdapter}; this entity exists to keep Hibernate
 * {@code ddl-auto=validate} in sync with the corpus-agnostic schema.
 */
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

    /** Corpus discriminator ("document" today). Non-null per SourceRef. */
    @Column(name = "source_type", nullable = false, updatable = false)
    private String sourceType;

    /** Snapshot of the human-facing label at persist time (SP3b spec D6). */
    @Column(name = "title", updatable = false)
    private String title;

    /** Snapshot of the cited text at persist time (≤600 chars; SP3b spec D6). */
    @Column(name = "content", updatable = false)
    private String content;

    /** Snapshot of the absolute source access URL at persist time (SP3b spec D6). */
    @Column(name = "uri", updatable = false)
    private String uri;

    protected MessageCitationJpaEntity() {
        // for JPA
    }

    public MessageCitationJpaEntity(UUID messageId, int position, String sourceType,
                                    String title, String content, String uri) {
        this.messageId = messageId;
        this.position = position;
        this.sourceType = sourceType;
        this.title = title;
        this.content = content;
        this.uri = uri;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public int getPosition() {
        return position;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getUri() {
        return uri;
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
