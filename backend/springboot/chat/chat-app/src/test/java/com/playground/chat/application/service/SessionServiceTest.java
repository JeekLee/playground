package com.playground.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.playground.chat.application.dto.SessionDetailView;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.MessageCitation;
import com.playground.chat.domain.model.id.DocumentId;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * loadDetail builds CitationView straight from the persisted snapshot
 * (title/excerpt/visibility on chat.message_citations) — no cross-schema
 * resolver (agentic-search spec D2).
 */
class SessionServiceTest {

    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private AttachmentRepository attachmentRepository;
    private SessionService service;

    private final UserId caller = UserId.of(UUID.randomUUID());
    private final SessionId sessionId = SessionId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        attachmentRepository = mock(AttachmentRepository.class);
        service = new SessionService(
                sessionRepository, messageRepository, attachmentRepository,
                Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void loadDetail_buildsCitationViewFromSnapshot_withoutResolver() {
        Instant now = Instant.parse("2026-06-07T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "T", now, now)));

        MessageId assistantId = MessageId.of(UUID.randomUUID());
        Message assistant = new Message(
                assistantId, sessionId, caller, Role.ASSISTANT, "answer [1]",
                3, 4, 1, now);
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of(assistant));

        DocumentId docId = DocumentId.of(UUID.randomUUID());
        MessageCitation snapshot = new MessageCitation(
                assistantId, 1, docId, 2, "Doc Title", "the cited excerpt text", "private");
        when(messageRepository.findCitationsForMessages(any())).thenReturn(List.of(snapshot));
        when(attachmentRepository.findByMessages(any())).thenReturn(List.of());

        SessionDetailView detail = service.loadDetail(sessionId, caller);

        assertThat(detail.messages()).hasSize(1);
        List<SessionDetailView.CitationView> citations = detail.messages().get(0).citations();
        assertThat(citations).hasSize(1);
        SessionDetailView.CitationView view = citations.get(0);
        assertThat(view.position()).isEqualTo(1);
        assertThat(view.documentId()).isEqualTo(docId);
        assertThat(view.chunkIndex()).isEqualTo(2);
        assertThat(view.title()).isEqualTo("Doc Title");
        assertThat(view.excerpt()).isEqualTo("the cited excerpt text");
        // Snapshot rows are never stale — title is non-null, so the FE
        // isStaleCitation heuristic does not fire.
        assertThat(view.deleted()).isFalse();
    }

    @Test
    void loadDetail_emptyCitations_yieldsEmptyView() {
        Instant now = Instant.parse("2026-06-07T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "T", now, now)));
        Message user = new Message(
                MessageId.of(UUID.randomUUID()), sessionId, caller, Role.USER, "hi",
                null, null, null, now);
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of(user));

        SessionDetailView detail = service.loadDetail(sessionId, caller);

        assertThat(detail.messages()).hasSize(1);
        assertThat(detail.messages().get(0).citations()).isEmpty();
    }
}
