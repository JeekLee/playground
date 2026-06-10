package com.playground.chat.application.service;

import com.playground.chat.application.dto.CitationDto;
import com.playground.chat.application.dto.SessionDetailView;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.MessageCitation;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.chat.SourceRef;
import com.playground.shared.error.ExceptionCreator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Read-side assembly of a {@link SessionDetailView} (history reload): batch-load
 * the session's messages + their citation snapshots + tool-artifact attachments
 * and stitch them into per-message views. Extracted from {@code SessionService}
 * so the CRUD service stays thin (#250).
 *
 * <p>No cross-schema read — each {@link CitationDto} is built straight from the
 * corpus-agnostic {@code SourceRef} frozen on the citation row at persist time
 * (SP3b spec D5); the FE {@code isStaleCitation} heuristic keys off
 * {@code title === null}.
 */
@Component
@RequiredArgsConstructor
public class SessionDetailLoader {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;

    /** Resolve session detail (404 if not owned). */
    public SessionDetailView load(SessionId id, UserId caller) {
        ChatSession session = sessionRepository.findOwned(id, caller)
                .orElseThrow(() -> ExceptionCreator.of(ChatErrorCode.SESSION_NOT_FOUND).build());

        List<Message> messages = messageRepository.findBySession(id);

        // Citation rows live keyed by message id; load them all in one batch.
        List<MessageId> assistantIds = messages.stream()
                .filter(m -> m.role() == com.playground.chat.domain.enums.Role.ASSISTANT)
                .map(Message::id)
                .toList();
        List<MessageCitation> citations = assistantIds.isEmpty()
                ? List.of()
                : messageRepository.findCitationsForMessages(assistantIds);

        Map<MessageId, List<MessageCitation>> citationsByMessage = new HashMap<>();
        for (MessageCitation c : citations) {
            citationsByMessage.computeIfAbsent(c.messageId(), k -> new ArrayList<>()).add(c);
        }

        // Attachments: one per assistant message (tool-artifact, ADR-20 §D1).
        List<Attachment> attachments = assistantIds.isEmpty()
                ? List.of()
                : attachmentRepository.findByMessages(assistantIds);
        Map<MessageId, Attachment> attachmentByMessage = new HashMap<>();
        for (Attachment a : attachments) {
            attachmentByMessage.put(a.messageId(), a);
        }

        // Build each CitationDto straight from the persisted snapshot
        // (the corpus-agnostic SourceRef frozen at persist time per SP3b spec
        // D5). No cross-schema read — history reload reads chat's own table
        // only. The FE isStaleCitation heuristic keys off title === null.
        List<SessionDetailView.MessageView> views = new ArrayList<>(messages.size());
        for (Message m : messages) {
            List<MessageCitation> raw = citationsByMessage.getOrDefault(m.id(), List.of());
            List<CitationDto> snapshotCitations = new ArrayList<>(raw.size());
            for (MessageCitation rc : raw) {
                SourceRef s = rc.source();
                snapshotCitations.add(new CitationDto(
                        rc.position(), s.sourceType(), s.title(), s.content(), s.uri()));
            }
            views.add(new SessionDetailView.MessageView(
                    m.id(), m.role(), m.content(), m.createdAt(), m.tokensIn(), m.tokensOut(),
                    snapshotCitations,
                    Optional.ofNullable(attachmentByMessage.get(m.id()))));
        }
        return new SessionDetailView(session.id(), session.title(), views);
    }
}
