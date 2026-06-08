package com.playground.chat.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.playground.chat.application.dto.CitationDto;
import com.playground.chat.application.dto.SessionDetailView;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.ChatSession;
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

    /** Gateway-relative download URL prefix per ADR-20 §D4. */
    private static final String ATTACHMENT_DOWNLOAD_PREFIX = "/api/chat/attachments/";

    public record MessageHistoryResponse(UUID sessionId, String title, List<MessageDto> messages) {
        public static MessageHistoryResponse from(SessionDetailView detail) {
            List<MessageDto> msgs = detail.messages().stream().map(m -> {
                AttachmentWire attachmentWire = m.attachment()
                        .map(a -> new AttachmentWire(
                                a.id().value(),
                                a.filename(),
                                a.contentType(),
                                a.sizeBytes(),
                                ATTACHMENT_DOWNLOAD_PREFIX + a.id().value(),
                                a.toolName(),
                                a.briefTitle()))
                        .orElse(null);
                return new MessageDto(
                        m.id().value(),
                        m.role().wireValue(),
                        m.content(),
                        m.createdAt(),
                        m.tokensIn(),
                        m.tokensOut(),
                        m.citations(),
                        attachmentWire);
            }).toList();
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
            List<CitationDto> citations,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            AttachmentWire attachment) {}

    /** Wire shape for a tool-produced file attachment on a historical message. */
    public record AttachmentWire(
            UUID id,
            String filename,
            String contentType,
            long sizeBytes,
            String downloadUrl,
            String toolName,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String briefTitle) {}
}
