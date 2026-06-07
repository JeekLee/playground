package com.playground.chat.application.service;

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
import com.playground.shared.error.ExceptionCreator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session CRUD per spec §5.3. Ownership enforced at the SQL layer
 * (every read/write filters on {@code user_id}). 404 ("session not found")
 * is returned both for "not in DB" and "not yours" per the spec §5.1
 * existence-leak-neutral wording.
 */
@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final Clock clock;

    public SessionService(SessionRepository sessionRepository, MessageRepository messageRepository,
                          AttachmentRepository attachmentRepository, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.clock = clock;
    }

    @Transactional
    public ChatSession create(UserId caller) {
        ChatSession session = ChatSession.newSession(caller, clock.instant());
        return sessionRepository.save(session);
    }

    public List<SessionRepository.SessionSummary> list(UserId caller) {
        return sessionRepository.listForUser(caller);
    }

    @Transactional
    public ChatSession rename(SessionId id, UserId caller, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw ExceptionCreator.of(ChatErrorCode.SESSION_TITLE_BLANK).build();
        }
        boolean updated = sessionRepository.rename(id, caller, newTitle);
        if (!updated) {
            throw ExceptionCreator.of(ChatErrorCode.SESSION_NOT_FOUND).build();
        }
        return sessionRepository.findOwned(id, caller)
                .orElseThrow(() -> ExceptionCreator.of(ChatErrorCode.SESSION_NOT_FOUND).build());
    }

    @Transactional
    public void delete(SessionId id, UserId caller) {
        boolean deleted = sessionRepository.deleteOwned(id, caller);
        if (!deleted) {
            // Idempotent per spec §5.3 — return 200/204 if the session was already gone.
            // We still validate ownership semantics; "not owned" is indistinguishable
            // from "already deleted" by design.
        }
    }

    /** Resolve session detail (404 if not owned). */
    public SessionDetailView loadDetail(SessionId id, UserId caller) {
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

        // Build each CitationView straight from the persisted snapshot
        // (title/excerpt/visibility frozen at persist time per agentic-search
        // spec D2). No cross-schema read — history reload reads chat's own table
        // only. Snapshot rows always carry a title, so deleted=false: the FE
        // isStaleCitation (title-null) heuristic never fires for new rows.
        List<SessionDetailView.MessageView> views = new ArrayList<>(messages.size());
        for (Message m : messages) {
            List<MessageCitation> raw = citationsByMessage.getOrDefault(m.id(), List.of());
            List<SessionDetailView.CitationView> snapshotCitations = new ArrayList<>(raw.size());
            for (MessageCitation rc : raw) {
                snapshotCitations.add(new SessionDetailView.CitationView(
                        rc.position(), rc.documentId(), rc.chunkIndex(),
                        rc.title(), rc.excerpt(), false));
            }
            views.add(new SessionDetailView.MessageView(
                    m.id(), m.role(), m.content(), m.createdAt(), m.tokensIn(), m.tokensOut(),
                    snapshotCitations,
                    Optional.ofNullable(attachmentByMessage.get(m.id()))));
        }
        return new SessionDetailView(session.id(), session.title(), views);
    }
}
