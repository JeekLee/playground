package com.playground.ragchat.application.service;

import com.playground.ragchat.application.port.BlobStoragePort;
import com.playground.ragchat.application.repository.AttachmentRepository;
import com.playground.ragchat.domain.exception.RagChatErrorCode;
import com.playground.ragchat.domain.model.Attachment;
import com.playground.ragchat.domain.model.id.AttachmentId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.shared.error.ExceptionCreator;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Owner-only attachment download use-case per ADR-20 §D4. Resolves an
 * attachment by id scoped to the caller (the attachment's message owner must
 * equal {@code X-User-Id}; non-owner OR missing → 404, tenant isolation per
 * ADR-14 §6.5), then opens a streaming handle from MinIO.
 */
@Service
public class AttachmentDownloadService {

    private final AttachmentRepository attachmentRepository;
    private final BlobStoragePort blobStoragePort;

    public AttachmentDownloadService(
            AttachmentRepository attachmentRepository,
            BlobStoragePort blobStoragePort) {
        this.attachmentRepository = attachmentRepository;
        this.blobStoragePort = blobStoragePort;
    }

    /**
     * Resolve the owned attachment + open its blob. Throws
     * {@code ATTACHMENT_NOT_FOUND} (404) for a missing attachment, a non-owner
     * caller, or a purged blob.
     */
    public Download open(AttachmentId attachmentId, UserId caller) {
        Attachment attachment = attachmentRepository.findOwned(attachmentId, caller)
                .orElseThrow(() -> ExceptionCreator.of(RagChatErrorCode.ATTACHMENT_NOT_FOUND).build());

        Optional<BlobStoragePort.BlobHandle> handle = blobStoragePort.get(attachment.storageKey());
        if (handle.isEmpty()) {
            // Blob purged from MinIO but the row survives — indistinguishable
            // from "not found" to the client (don't leak the dangling row).
            throw ExceptionCreator.of(RagChatErrorCode.ATTACHMENT_NOT_FOUND).build();
        }
        return new Download(attachment, handle.get());
    }

    /** Resolved attachment metadata + an open MinIO read handle. */
    public record Download(Attachment attachment, BlobStoragePort.BlobHandle handle) {}
}
