package com.playground.ragchat.api.dto;

import com.playground.ragchat.application.dto.SessionDetailView;
import com.playground.ragchat.application.repository.SessionRepository;
import com.playground.ragchat.domain.model.ChatSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shapes for the session CRUD endpoints per spec §5.3. */
public final class SessionResponses {

    private SessionResponses() {}

    public record CreateSessionResponse(UUID sessionId, String title) {
        public static CreateSessionResponse from(ChatSession s) {
            return new CreateSessionResponse(s.id().value(), s.title());
        }
    }

    public record SessionSummaryDto(UUID id, String title, Instant updatedAt, int messageCount) {
        public static SessionSummaryDto from(SessionRepository.SessionSummary s) {
            return new SessionSummaryDto(s.id().value(), s.title(), s.updatedAt(), s.messageCount());
        }
    }

    public record SessionListResponse(List<SessionSummaryDto> sessions) {}

    public record RenameSessionRequest(String title) {}

    public record RenameSessionResponse(UUID sessionId, String title) {
        public static RenameSessionResponse from(ChatSession s) {
            return new RenameSessionResponse(s.id().value(), s.title());
        }
    }

    public record MessageHistoryResponse(UUID sessionId, String title, List<MessageDto> messages) {
        public static MessageHistoryResponse from(SessionDetailView detail) {
            List<MessageDto> msgs = detail.messages().stream().map(m -> new MessageDto(
                    m.id().value(),
                    m.role().wireValue(),
                    m.content(),
                    m.createdAt(),
                    m.tokensIn(),
                    m.tokensOut(),
                    m.citations().stream().map(c -> new CitationDto(
                            c.position(), c.documentId().value(), c.chunkIndex(),
                            c.title(), c.excerpt(), c.deleted())).toList()))
                    .toList();
            return new MessageHistoryResponse(detail.sessionId().value(), detail.title(), msgs);
        }
    }

    public record MessageDto(
            UUID id,
            String role,
            String content,
            Instant createdAt,
            Integer tokensIn,
            Integer tokensOut,
            List<CitationDto> citations) {}

    public record CitationDto(
            int position,
            UUID documentId,
            int chunkIndex,
            String title,
            String excerpt,
            boolean deleted) {}
}
