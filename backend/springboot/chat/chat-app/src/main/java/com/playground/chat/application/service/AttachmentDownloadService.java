package com.playground.chat.application.service;

import com.playground.chat.application.port.BlobStoragePort;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.id.AttachmentId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.error.ExceptionCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Owner-only attachment download use-case per ADR-20 §D4. Resolves an
 * attachment by id scoped to the caller (the attachment's message owner must
 * equal {@code X-User-Id}; non-owner OR missing → 404, tenant isolation per
 * ADR-14 §6.5), then opens a streaming handle from MinIO.
 */
@Service
@RequiredArgsConstructor
public class AttachmentDownloadService {

    private final AttachmentRepository attachmentRepository;
    private final BlobStoragePort blobStoragePort;

    /**
     * Resolve the owned attachment + open its blob. Throws
     * {@code ATTACHMENT_NOT_FOUND} (404) for a missing attachment, a non-owner
     * caller, or a purged blob.
     */
    public Download open(AttachmentId attachmentId, UserId caller) {
        Attachment attachment = resolveOwned(attachmentId, caller);
        // Blob purged from MinIO but the row survives → 404, indistinguishable
        // from "not found" to the client (don't leak the dangling row).
        return openKey(attachment, attachment.storageKey());
    }

    /**
     * Resolve the owned attachment + open its <em>preview</em> blob — the .glb
     * sibling agent-tools uploads next to the .3dm (same MinIO prefix,
     * extension swapped; design spec 2026-06-05-massing-glb-preview).
     * 415 when the attachment type has no preview representation; 404 when
     * the .glb is absent (legacy rows, failed store_glb).
     */
    public Download openPreview(AttachmentId attachmentId, UserId caller) {
        Attachment attachment = resolveOwned(attachmentId, caller);
        String key = attachment.storageKey();
        if (!key.endsWith(".3dm")) {
            throw ExceptionCreator.of(ChatErrorCode.PREVIEW_NOT_SUPPORTED).build();
        }
        // Key is producer-written (agent-tools, ADR-20 §D3 revised), never
        // caller-derived — the suffix swap introduces no traversal surface.
        String glbKey = key.substring(0, key.length() - ".3dm".length()) + ".glb";
        return openKey(attachment, glbKey);
    }

    /** Owner-scoped lookup; missing OR non-owner → 404 (tenant isolation, ADR-14 §6.5). */
    private Attachment resolveOwned(AttachmentId attachmentId, UserId caller) {
        return attachmentRepository.findOwned(attachmentId, caller)
                .orElseThrow(() -> ExceptionCreator.of(ChatErrorCode.ATTACHMENT_NOT_FOUND).build());
    }

    /** Open a blob by key into a {@link Download}; absent blob → 404. */
    private Download openKey(Attachment attachment, String storageKey) {
        return blobStoragePort.get(storageKey)
                .map(handle -> new Download(attachment, handle))
                .orElseThrow(() -> ExceptionCreator.of(ChatErrorCode.ATTACHMENT_NOT_FOUND).build());
    }

    /** Resolved attachment metadata + an open MinIO read handle. */
    public record Download(Attachment attachment, BlobStoragePort.BlobHandle handle) {}
}
