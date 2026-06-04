package com.playground.ragchat.application.repository;

import com.playground.ragchat.domain.model.Attachment;
import com.playground.ragchat.domain.model.id.AttachmentId;
import com.playground.ragchat.domain.model.id.MessageId;
import com.playground.ragchat.domain.model.id.UserId;
import java.util.List;
import java.util.Optional;

/**
 * Repository port for {@code chat.message_attachments} per ADR-20 §D1.
 */
public interface AttachmentRepository {

    /** Insert one attachment row; returns the persisted attachment. */
    Attachment save(Attachment attachment);

    /** Bulk-insert attachment rows for an assistant message. No-op on empty list. */
    void saveAll(List<Attachment> attachments);

    /**
     * Resolve an attachment by id, joined to its owning message's {@code user_id}.
     * Used by the owner-only download endpoint: returns the attachment only when
     * the joined message owner equals {@code caller} (tenant isolation, ADR-14
     * §6.5 style). A missing attachment OR a non-owner caller yields empty.
     */
    Optional<Attachment> findOwned(AttachmentId attachmentId, UserId caller);

    /** Load attachments for a set of assistant message ids. Used by the history endpoint. */
    List<Attachment> findByMessages(List<MessageId> messageIds);
}
