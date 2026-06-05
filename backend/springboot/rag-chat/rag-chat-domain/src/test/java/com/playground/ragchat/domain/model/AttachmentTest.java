package com.playground.ragchat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playground.ragchat.domain.model.id.AttachmentId;
import com.playground.ragchat.domain.model.id.MessageId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link Attachment} domain entity per ADR-20 §D1. */
class AttachmentTest {

    @Test
    void toolArtifact_stampsKindAndCopiesFields() {
        AttachmentId id = AttachmentId.generate();
        MessageId messageId = MessageId.generate();
        Instant now = Instant.parse("2026-06-04T12:00:00Z");

        Attachment a = Attachment.toolArtifact(
                id, messageId, "massing-한글-1.3dm", "application/octet-stream",
                31_000L, "chat/s/m/a-massing-한글-1.3dm", "generate_massing", null, now);

        assertThat(a.id()).isEqualTo(id);
        assertThat(a.messageId()).isEqualTo(messageId);
        assertThat(a.kind()).isEqualTo(Attachment.KIND_TOOL_ARTIFACT);
        assertThat(a.kind()).isEqualTo("tool-artifact");
        assertThat(a.filename()).isEqualTo("massing-한글-1.3dm");
        assertThat(a.contentType()).isEqualTo("application/octet-stream");
        assertThat(a.sizeBytes()).isEqualTo(31_000L);
        assertThat(a.storageKey()).isEqualTo("chat/s/m/a-massing-한글-1.3dm");
        assertThat(a.toolName()).isEqualTo("generate_massing");
        assertThat(a.createdAt()).isEqualTo(now);
    }

    @Test
    void rejectsNegativeSize() {
        assertThatThrownBy(() -> Attachment.toolArtifact(
                AttachmentId.generate(), MessageId.generate(), "f.3dm",
                "application/octet-stream", -1L, "key", "tool", null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes");
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThatThrownBy(() -> new Attachment(
                null, MessageId.generate(), "tool-artifact", "f.3dm",
                "application/octet-stream", 1L, "key", "tool", null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Attachment(
                AttachmentId.generate(), MessageId.generate(), "tool-artifact", "f.3dm",
                "application/octet-stream", 1L, null, "tool", null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }
}
