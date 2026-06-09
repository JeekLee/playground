package com.playground.chat.application.service;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.chat.application.dto.ChatTurnRequest;
import com.playground.chat.application.port.ChatGenerationPort;
import com.playground.chat.application.port.ConcurrentStreamLockPort;
import com.playground.chat.application.port.TokenBucketPort;
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.application.tool.ToolBinding;
import com.playground.chat.application.tool.ToolDispatcherPort;
import com.playground.chat.application.tool.ToolInvocationResult;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.MessageCitation;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.chat.domain.service.CitationExtractor;
import com.playground.chat.domain.service.HistoryTruncator;
import com.playground.chat.domain.service.PromptTemplate;
import com.playground.chat.domain.service.TokenCounter;
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
                chatGenerationPort,
                historyTruncator,
                tokenCounter,
                promptTemplate,
                autoTitleService,
                new ActiveTurnRegistry(),
                toolDispatcherPort,
                (userId, limit) -> java.util.List.of(),
                mock(com.playground.chat.application.repository.AttachmentRepository.class),
                new ObjectMapper(),
                ChatProperties.defaults(),
                Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC),
                // Empty catalog for the pre-stream guard tests (no tool path).
                java.util.List::of);

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
    void searchToolTurn_accumulatesCitations_andPersistsCitedSubset() {
        // agentic-search spec D2: a search_documents tool result feeds the
        // turn-global citation accumulator → existing renumber/persist path.
        var desc = new com.playground.chat.domain.tool.ToolDescriptor(
                "search_documents", "문서 검색", "search docs", null,
                java.net.URI.create("http://t/"),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30));

        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "New chat", now, now)));
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of());
        when(messageRepository.countUserMessages(sessionId)).thenReturn(1);

        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        UUID docC = UUID.randomUUID();

        // The search tool returns 3 results (per-call positions 1..3); the
        // dispatcher hands back the result body. The handler absorbs it into
        // the accumulator (global positions 1..3 here — first search of turn).
        ObjectNode searchBody = new ObjectMapper().createObjectNode();
        ArrayNode results = searchBody.putArray("results");
        addResult(results, "Doc A", "text A", "https://o/docs/" + docA);
        addResult(results, "Doc B", "text B", "https://o/docs/" + docB);
        addResult(results, "Doc C", "text C", "https://o/docs/" + docC);
        searchBody.put("totalFound", 3);
        searchBody.put("summary", "policy — 3건");

        when(toolDispatcherPort.invoke(any(), eq("search_documents"), any(), any(), any()))
                .thenAnswer(inv -> new ToolInvocationResult.Success(
                        inv.getArgument(0), "search_documents", searchBody));

        // Spring AI invokes the tool callback, then streams text citing [1] and
        // [3] (global positions). [2] retrieved but not referenced.
        when(chatGenerationPort.streamWithTools(any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<ToolBinding> bindings = inv.getArgument(2);
                    ToolBinding searchBinding = bindings.stream()
                            .filter(b -> b.descriptor().name().equals("search_documents"))
                            .findFirst().orElseThrow();
                    return Flux.defer(() -> {
                        searchBinding.handler().apply(new ObjectMapper().createObjectNode());
                        return Flux.just("Per [1] the policy is ", "X. See also [3].");
                    });
                });

        ConcurrentStreamLockPort.Handle handle = mock(ConcurrentStreamLockPort.Handle.class);
        when(lockPort.acquire(caller)).thenReturn(handle);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChatTurnService service = serviceWithCatalog(() -> List.of(desc));

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "what's the policy?");
        List<ChatStreamEvent> events = service.stream(req).collectList().block();
        assertThat(events).isNotNull();

        // No retrieval Phase event anymore (pipeline retrieval removed).
        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.Phase
                && "retrieval".equals(((ChatStreamEvent.Phase) e).step()));
        // tool_call + tool_result + tokens + done present.
        assertThat(events).anyMatch(e -> e instanceof ChatStreamEvent.ToolCall);
        assertThat(events).anyMatch(e -> e instanceof ChatStreamEvent.ToolResult);
        assertThat(events.get(events.size() - 1)).isInstanceOf(ChatStreamEvent.Done.class);

        ChatStreamEvent.Done done = (ChatStreamEvent.Done) events.get(events.size() - 1);
        @SuppressWarnings("unchecked")
        List<com.playground.chat.application.dto.CitationDto> citedDtos =
                (List<com.playground.chat.application.dto.CitationDto>) done.citations();
        assertThat(citedDtos).hasSize(2);
        // Renumbered: global [1] and [3] become dense [1] and [2] in
        // first-encounter order; global [2] never appeared in the text.
        assertThat(citedDtos).extracting(com.playground.chat.application.dto.CitationDto::n)
                .containsExactly(1, 2);

        // 2 messages saved (user + assistant). Persisted citations carry the
        // new dense positions to match the rewritten message text.
        verify(messageRepository, times(2)).save(any());
        // Snapshot persistence (SP3b spec D4): each persisted citation carries
        // the corpus-agnostic SourceRef (sourceType/title/content/uri) frozen
        // from the search result so history reload never reads the docs schema.
        // [1]→Doc A, [3]→Doc C.
        verify(messageRepository, times(1)).saveCitations(argThat((List<MessageCitation> list) ->
                list.size() == 2
                && list.stream().anyMatch(c -> c.position() == 1)
                && list.stream().anyMatch(c -> c.position() == 2)
                && list.stream().noneMatch(c -> c.position() == 3)
                && list.stream().anyMatch(c -> c.position() == 1
                        && "document".equals(c.source().sourceType())
                        && "Doc A".equals(c.source().title())
                        && "text A".equals(c.source().content())
                        && ("https://o/docs/" + docA).equals(c.source().uri()))
                && list.stream().anyMatch(c -> c.position() == 2
                        && "document".equals(c.source().sourceType())
                        && "Doc C".equals(c.source().title())
                        && "text C".equals(c.source().content())
                        && ("https://o/docs/" + docC).equals(c.source().uri()))));
        verify(handle, times(1)).release();
    }

    @Test
    void renumberCitations_expandsGroupedBracketToIndividualMarkers() {
        // The LLM grouped sources as [1, 2, 4, 5]; with all 5 positions
        // retrieved the group expands to individual dense markers and every
        // referenced position becomes a citation.
        List<com.playground.chat.domain.model.RetrievedChunk> retrieved = List.of(
                chunk(1), chunk(2), chunk(3), chunk(4), chunk(5));

        ChatTurnService.CitationRenumber out =
                ChatTurnService.renumberCitations("a [1] b [2] c [1, 2, 4, 5].", retrieved);

        // First-encounter dense numbering across all brackets: 1→1, 2→2,
        // 4→3, 5→4. The group expands to individual brackets, no separator.
        assertThat(out.text()).isEqualTo("a [1] b [2] c [1][2][3][4].");
        // cited carries one entry per distinct referenced position (4 here);
        // positions 1,2 from singles + 4,5 newly seen inside the group.
        assertThat(out.cited()).extracting(ChatTurnService.CitedChunk::newN)
                .containsExactly(1, 2, 3, 4);
        assertThat(out.cited()).extracting(c -> c.chunk().position())
                .containsExactly(1, 2, 4, 5);
    }

    @Test
    void renumberCitations_dropsInvalidPositionsInsideGroup() {
        // Only 3 positions retrieved; [1, 9] cites 9 (orphan). The valid 1 is
        // kept (and renumbered), the orphan 9 is dropped from the expansion.
        List<com.playground.chat.domain.model.RetrievedChunk> retrieved = List.of(
                chunk(1), chunk(2), chunk(3));

        ChatTurnService.CitationRenumber out =
                ChatTurnService.renumberCitations("only [1, 9] here.", retrieved);

        assertThat(out.text()).isEqualTo("only [1] here.");
        assertThat(out.cited()).extracting(ChatTurnService.CitedChunk::newN)
                .containsExactly(1);
    }

    @Test
    void renumberCitations_leavesAllInvalidGroupUntouched() {
        // [8, 9] with only 3 retrieved — every position is an orphan, so the
        // bracket is left exactly as written (preserves the bad-output trail).
        List<com.playground.chat.domain.model.RetrievedChunk> retrieved = List.of(
                chunk(1), chunk(2), chunk(3));

        ChatTurnService.CitationRenumber out =
                ChatTurnService.renumberCitations("bogus [8, 9] ref.", retrieved);

        assertThat(out.text()).isEqualTo("bogus [8, 9] ref.");
        assertThat(out.cited()).isEmpty();
    }

    private static com.playground.chat.domain.model.RetrievedChunk chunk(int position) {
        return new com.playground.chat.domain.model.RetrievedChunk(
                position,
                new com.playground.shared.chat.SourceRef(
                        "document", "Doc " + position, "text " + position,
                        "https://o/docs/" + position));
    }

    private static void addResult(ArrayNode results,
            String title, String content, String uri) {
        ObjectNode item = results.addObject();
        item.put("sourceType", "document");
        item.put("title", title);
        item.put("content", content);
        item.put("uri", uri);
    }

    private ChatTurnService serviceWithCatalog(
            java.util.function.Supplier<List<com.playground.chat.domain.tool.ToolDescriptor>> catalog) {
        TokenCounter tokenCounter = new TokenCounter();
        return new ChatTurnService(
                sessionRepository, messageRepository, tokenBucketPort, lockPort,
                chatGenerationPort, new HistoryTruncator(tokenCounter), tokenCounter,
                new PromptTemplate(tokenCounter, new CitationExtractor()),
                autoTitleService, new ActiveTurnRegistry(), toolDispatcherPort,
                (userId, limit) -> java.util.List.of(),
                mock(com.playground.chat.application.repository.AttachmentRepository.class),
                new ObjectMapper(), ChatProperties.defaults(),
                Clock.fixed(Instant.parse("2026-05-18T12:00:00Z"), ZoneOffset.UTC),
                catalog);
    }
}
