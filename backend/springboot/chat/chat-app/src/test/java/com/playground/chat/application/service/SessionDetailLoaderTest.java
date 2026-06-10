package com.playground.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.playground.chat.application.dto.CitationDto;
import com.playground.chat.application.dto.SessionDetailView;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.domain.enums.Role;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.MessageCitation;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.chat.SourceRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SessionDetailLoader builds {@link CitationDto} straight from the persisted
 * snapshot (the corpus-agnostic SourceRef on chat.message_citations) — no
 * cross-schema resolver (SP3b spec D5). Moved out of SessionServiceTest when
 * the read-side assembly was extracted from SessionService (#250).
 */
class SessionDetailLoaderTest {

    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private AttachmentRepository attachmentRepository;
    private SessionDetailLoader loader;

    private final UserId caller = UserId.of(UUID.randomUUID());
    private final SessionId sessionId = SessionId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        attachmentRepository = mock(AttachmentRepository.class);
        loader = new SessionDetailLoader(sessionRepository, messageRepository, attachmentRepository);
    }

    @Test
    void load_buildsCitationDtoFromSnapshot_withoutResolver() {
        Instant now = Instant.parse("2026-06-07T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "T", now, now)));

        MessageId assistantId = MessageId.of(UUID.randomUUID());
        Message assistant = new Message(
                assistantId, sessionId, caller, Role.ASSISTANT, "answer [1]",
                3, 4, 1, now);
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of(assistant));

        MessageCitation snapshot = new MessageCitation(
                assistantId, 1, new SourceRef(
                        "document", "Doc Title", "the cited excerpt text",
                        "https://o/docs/d1"));
        when(messageRepository.findCitationsForMessages(any())).thenReturn(List.of(snapshot));
        when(attachmentRepository.findByMessages(any())).thenReturn(List.of());

        SessionDetailView detail = loader.load(sessionId, caller);

        assertThat(detail.messages()).hasSize(1);
        List<CitationDto> citations = detail.messages().get(0).citations();
        assertThat(citations).hasSize(1);
        CitationDto view = citations.get(0);
        assertThat(view.n()).isEqualTo(1);
        assertThat(view.sourceType()).isEqualTo("document");
        assertThat(view.title()).isEqualTo("Doc Title");
        assertThat(view.content()).isEqualTo("the cited excerpt text");
        assertThat(view.uri()).isEqualTo("https://o/docs/d1");
    }

    @Test
    void load_emptyCitations_yieldsEmptyView() {
        Instant now = Instant.parse("2026-06-07T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "T", now, now)));
        Message user = new Message(
                MessageId.of(UUID.randomUUID()), sessionId, caller, Role.USER, "hi",
                null, null, null, now);
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of(user));

        SessionDetailView detail = loader.load(sessionId, caller);

        assertThat(detail.messages()).hasSize(1);
        assertThat(detail.messages().get(0).citations()).isEmpty();
    }
}
