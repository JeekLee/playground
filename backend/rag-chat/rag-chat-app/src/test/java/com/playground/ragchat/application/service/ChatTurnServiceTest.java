package com.playground.ragchat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.ragchat.application.dto.ChatTurnRequest;
import com.playground.ragchat.application.port.ChatGenerationPort;
import com.playground.ragchat.application.port.ChunkRetrievalPort;
import com.playground.ragchat.application.port.ConcurrentStreamLockPort;
import com.playground.ragchat.application.port.EmbeddingPort;
import com.playground.ragchat.application.port.TokenBucketPort;
import com.playground.ragchat.application.properties.RagChatProperties;
import com.playground.ragchat.application.repository.MessageRepository;
import com.playground.ragchat.application.repository.SessionRepository;
import com.playground.ragchat.application.tool.ToolDispatcherPort;
import com.playground.ragchat.domain.enums.Visibility;
import com.playground.ragchat.domain.model.ChatSession;
import com.playground.ragchat.domain.model.MessageCitation;
import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.DocumentId;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.ragchat.domain.service.CitationExtractor;
import com.playground.ragchat.domain.service.HistoryTruncator;
import com.playground.ragchat.domain.service.PromptTemplate;
import com.playground.ragchat.domain.service.TokenCounter;
import com.playground.shared.error.AbstractException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ChatTurnServiceTest {

    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private TokenBucketPort tokenBucketPort;
    private ConcurrentStreamLockPort lockPort;
    private EmbeddingPort embeddingPort;
    private ChunkRetrievalPort chunkRetrievalPort;
    private ChatGenerationPort chatGenerationPort;
    private AutoTitleService autoTitleService;
    private ToolDispatcherPort toolDispatcherPort;

    private ChatTurnService chatTurnService;

    private final UserId caller = UserId.of(UUID.randomUUID());
    private final SessionId sessionId = SessionId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        tokenBucketPort = mock(TokenBucketPort.class);
        lockPort = mock(ConcurrentStreamLockPort.class);
        embeddingPort = mock(EmbeddingPort.class);
        chunkRetrievalPort = mock(ChunkRetrievalPort.class);
        chatGenerationPort = mock(ChatGenerationPort.class);
        autoTitleService = mock(AutoTitleService.class);
        toolDispatcherPort = mock(ToolDispatcherPort.class);

        TokenCounter tokenCounter = new TokenCounter();
        CitationExtractor citationExtractor = new CitationExtractor();
        PromptTemplate promptTemplate = new PromptTemplate(tokenCounter, citationExtractor);
        HistoryTruncator historyTruncator = new HistoryTruncator(tokenCounter);

        chatTurnService = new ChatTurnService(
                sessionRepository,
                messageRepository,
                tokenBucketPort,
                lockPort,
                embeddingPort,
                chunkRetrievalPort,
                chatGenerationPort,
                historyTruncator,
                tokenCounter,
                promptTemplate,
                autoTitleService,
                new ActiveTurnRegistry(),
                toolDispatcherPort,
                new ObjectMapper(),
                RagChatProperties.defaults(),
                Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC));

        when(autoTitleService.generate(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void rateLimited_throwsBeforeStream() {
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.deny(42L));
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "hi");
        assertThatThrownBy(() -> chatTurnService.stream(req).blockLast())
                .isInstanceOf(AbstractException.class)
                .satisfies(t -> assertThat(((AbstractException) t).errorCode().code())
                        .isEqualTo("CHAT-RATE-LIMIT-001"));
    }

    @Test
    void sessionNotFound_throws404() {
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        when(sessionRepository.findOwned(sessionId, caller)).thenReturn(Optional.empty());
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "hi");
        assertThatThrownBy(() -> chatTurnService.stream(req).blockLast())
                .isInstanceOf(AbstractException.class)
                .satisfies(t -> assertThat(((AbstractException) t).errorCode().code())
                        .isEqualTo("CHAT-NOT-FOUND-001"));
    }

    @Test
    void messageTooLarge_throws413() {
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "x".repeat(5000));
        assertThatThrownBy(() -> chatTurnService.stream(req).blockLast())
                .isInstanceOf(AbstractException.class)
                .satisfies(t -> assertThat(((AbstractException) t).errorCode().code())
                        .isEqualTo("CHAT-VALIDATION-001"));
    }

    @Test
    void happyPath_emitsRetrievalThenTokensThenDoneAndPersistsCitedSubset() {
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "New chat", now, now)));
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of());
        when(messageRepository.countUserMessages(sessionId)).thenReturn(1);
        when(embeddingPort.embedQuery(any())).thenReturn(new float[1024]);

        DocumentId docA = DocumentId.of(UUID.randomUUID());
        DocumentId docB = DocumentId.of(UUID.randomUUID());
        DocumentId docC = DocumentId.of(UUID.randomUUID());
        List<RetrievedChunk> chunks = List.of(
                new RetrievedChunk(1, docA, 0, "text A", "Doc A", caller, Visibility.PUBLIC),
                new RetrievedChunk(2, docB, 0, "text B", "Doc B", caller, Visibility.PUBLIC),
                new RetrievedChunk(3, docC, 0, "text C", "Doc C", caller, Visibility.PRIVATE));
        when(chunkRetrievalPort.retrieve(eq(caller), any(), anyInt())).thenReturn(chunks);

        // Stream cites only [1] and [3] — [2] is retrieved but not referenced.
        when(chatGenerationPort.stream(any(), anyInt()))
                .thenReturn(Flux.just("Per [1] the policy is ", "X. See also [3]."));

        ConcurrentStreamLockPort.Handle handle = mock(ConcurrentStreamLockPort.Handle.class);
        when(lockPort.acquire(caller)).thenReturn(handle);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "what's the policy?");

        List<ChatStreamEvent> events = chatTurnService.stream(req).collectList().block();
        assertThat(events).isNotNull();
        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.Phase.class);
        ChatStreamEvent.Phase retrieval = (ChatStreamEvent.Phase) events.get(0);
        assertThat(retrieval.step()).isEqualTo("retrieval");
        // PR B grammar: phase data carries the candidate count only, not
        // the chunk list. Citation cards arrive via the terminal Done.
        assertThat(retrieval.data()).containsEntry("count", 3);
        assertThat(events.get(1)).isInstanceOf(ChatStreamEvent.Token.class);
        assertThat(((ChatStreamEvent.Token) events.get(1)).delta()).startsWith("Per [1]");
        assertThat(events.get(2)).isInstanceOf(ChatStreamEvent.Token.class);
        assertThat(((ChatStreamEvent.Token) events.get(2)).delta()).contains("[3]");
        assertThat(events.get(3)).isInstanceOf(ChatStreamEvent.Done.class);
        ChatStreamEvent.Done done = (ChatStreamEvent.Done) events.get(3);
        @SuppressWarnings("unchecked")
        List<com.playground.ragchat.application.dto.CitationDto> citedDtos =
                (List<com.playground.ragchat.application.dto.CitationDto>) done.citations();
        assertThat(citedDtos).hasSize(2);
        // PR — renumbered citations: original [1] and [3] become [1] and [2]
        // in first-encounter order; [2] never appeared in the text.
        assertThat(citedDtos).extracting(com.playground.ragchat.application.dto.CitationDto::n)
                .containsExactly(1, 2);

        // 2 messages saved (user + assistant). Persisted citations carry
        // the new dense positions to match the rewritten message text.
        verify(messageRepository, times(2)).save(any());
        verify(messageRepository, times(1)).saveCitations(argThat((List<MessageCitation> list) ->
                list.size() == 2
                && list.stream().anyMatch(c -> c.position() == 1)
                && list.stream().anyMatch(c -> c.position() == 2)
                && list.stream().noneMatch(c -> c.position() == 3)));
        verify(handle, times(1)).release();
    }
}
