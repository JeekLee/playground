package com.playground.chat.application.tool;

import java.util.Objects;

/**
 * A NON-LLM file artifact emitted by a tool alongside its LLM-visible result
 * per ADR-20 §D2 + §D3 revised. The dispatcher detects the {@code {result, artifact}}
 * envelope and splits the artifact off the LLM path.
 *
 * <p>Per ADR-20 §D3 revised, agent-tools owns the MinIO write path: the artifact
 * carries metadata only (no raw bytes). {@code storageKey} identifies the object
 * that agent-tools already uploaded; chat records it in
 * {@code chat.message_attachments} without touching MinIO for writes.
 *
 * @param filename    download name, e.g. {@code massing-<slug>-<ts>.3dm}
 * @param contentType MIME type; {@code null}/blank falls back to
 *                    {@code application/octet-stream}
 * @param sizeBytes   byte length of the stored artifact
 * @param storageKey  MinIO object key, e.g.
 *                    {@code architecture/massing/20260604/<uuid>/<filename>}
 */
public record ToolArtifact(String filename, String contentType, long sizeBytes, String storageKey) {

    public ToolArtifact {
        Objects.requireNonNull(filename, "ToolArtifact.filename must not be null");
        Objects.requireNonNull(storageKey, "ToolArtifact.storageKey must not be null");
    }

    public String contentTypeOrDefault() {
        return (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
    }
}
