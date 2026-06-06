package com.playground.chat.domain.model;

import com.playground.chat.domain.model.id.AttachmentId;
import com.playground.chat.domain.model.id.MessageId;
import java.time.Instant;
import java.util.Objects;

/**
 * A message-bound file artifact per ADR-20 §D1. Produced by a tool during a
 * chat turn and bound to the <b>assistant</b> message that produced it. The
 * record is a small pointer at the blob: it carries the MinIO storage key +
 * metadata, <b>never the bytes</b>.
 *
 * <p>Persisted in {@code chat.message_attachments}; the bytes live in MinIO
 * under {@link #storageKey()}. The kind discriminator ({@code tool-artifact}
 * today) leaves room for future attachment kinds (uploads, etc.).
 *
 * @param id          UUID
 * @param messageId   the assistant message that produced this artifact
 *                    (app-level FK to {@code chat.messages.id}, ADR-14 §11 style)
 * @param kind        attachment kind discriminator (e.g. {@code tool-artifact})
 * @param filename    RFC-6266 download name, e.g. {@code massing-<slug>-<ts>.3dm}
 * @param contentType MIME type, stored so the FE can render type-aware previews
 *                    and the download endpoint can set the right Content-Type
 * @param sizeBytes   blob size for the Content-Length header + FE size display
 * @param storageKey  MinIO object key — the only pointer to the bytes
 * @param toolName    which tool produced it (e.g. {@code generate_massing})
 * @param briefTitle  human-readable title of the brief that produced this artifact;
 *                    {@code null} for legacy rows created before this field was added
 * @param createdAt   creation timestamp
 */
public record Attachment(
        AttachmentId id,
        MessageId messageId,
        String kind,
        String filename,
        String contentType,
        long sizeBytes,
        String storageKey,
        String toolName,
        String briefTitle,
        Instant createdAt) {

    /** The {@code kind} value for artifacts produced by a tool call (ADR-20 §D1). */
    public static final String KIND_TOOL_ARTIFACT = "tool-artifact";

    public Attachment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }

    /**
     * Factory for a tool-produced artifact bound to an assistant message
     * (ADR-20 §D3). Generates the {@link AttachmentId} and stamps the kind
     * as {@link #KIND_TOOL_ARTIFACT}.
     */
    public static Attachment toolArtifact(
            AttachmentId id,
            MessageId messageId,
            String filename,
            String contentType,
            long sizeBytes,
            String storageKey,
            String toolName,
            String briefTitle,
            Instant createdAt) {
        return new Attachment(
                id, messageId, KIND_TOOL_ARTIFACT, filename, contentType,
                sizeBytes, storageKey, toolName, briefTitle, createdAt);
    }
}
