package com.playground.chat.application.dto;

import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.id.DocumentId;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Aggregate view returned by {@code GET /api/chat/sessions/{id}/messages}. */
public record SessionDetailView(SessionId sessionId, String title, List<MessageView> messages) {

    public record MessageView(
            MessageId id,
            Role role,
            String content,
            Instant createdAt,
            Integer tokensIn,
            Integer tokensOut,
            List<CitationView> citations,
            Optional<Attachment> attachment) {}

    public record CitationView(
            int position,
            DocumentId documentId,
            int chunkIndex,
            String title,
            String excerpt,
            boolean deleted) {}
}
