package com.playground.ragchat.application.dto;

import com.playground.ragchat.domain.enums.Role;
import com.playground.ragchat.domain.model.id.DocumentId;
import com.playground.ragchat.domain.model.id.MessageId;
import com.playground.ragchat.domain.model.id.SessionId;
import java.time.Instant;
import java.util.List;

/** Aggregate view returned by {@code GET /api/rag/chat/sessions/{id}/messages}. */
public record SessionDetailView(SessionId sessionId, String title, List<MessageView> messages) {

    public record MessageView(
            MessageId id,
            Role role,
            String content,
            Instant createdAt,
            Integer tokensIn,
            Integer tokensOut,
            List<CitationView> citations) {}

    public record CitationView(
            int position,
            DocumentId documentId,
            int chunkIndex,
            String title,
            String excerpt,
            boolean deleted) {}
}
