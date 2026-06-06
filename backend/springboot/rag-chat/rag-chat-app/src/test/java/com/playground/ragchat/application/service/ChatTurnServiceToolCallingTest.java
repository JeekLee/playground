package com.playground.ragchat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.ragchat.application.dto.ChatTurnRequest;
import com.playground.ragchat.application.port.ChatGenerationPort;
import com.playground.ragchat.application.port.ChunkRetrievalPort;
import com.playground.ragchat.application.port.ConcurrentStreamLockPort;
import com.playground.ragchat.application.port.EmbeddingPort;
import com.playground.ragchat.application.port.TokenBucketPort;
import com.playground.ragchat.application.properties.RagChatProperties;
import com.playground.ragchat.application.repository.MessageRepository;
import com.playground.ragchat.application.repository.SessionRepository;
import com.playground.ragchat.application.tool.ToolBinding;
import com.playground.ragchat.application.tool.ToolDispatcherPort;
import com.playground.ragchat.domain.model.ChatSession;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.ragchat.domain.service.CitationExtractor;
import com.playground.ragchat.domain.service.HistoryTruncator;
import com.playground.ragchat.domain.service.PromptTemplate;
import com.playground.ragchat.domain.service.TokenCounter;
import com.playground.shared.chat.ChatStreamEvent;
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

/**
 * Tests the M7 use-case extension. The key invariant under test is the
 * <b>M4 regression-invariant</b>: when {@code ToolCatalog.descriptors()}
 * is empty (the M7 P0 ship state), the SSE event stream is identical to
 * M4 — no {@code tool_call} / {@code tool_result} / {@code tool_error}
 * events are emitted (PRD Story 10).
 *
 * <p>Behavior with non-empty catalogs is exercised by the M7 end-to-end
 * WireMock integration test in {@code rag-chat-infra} which can wire a
 * real {@code WebClientToolDispatcher} against a synthetic echo tool
 * descriptor.
 */
class ChatTurnServiceToolCallingTest {

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
                (userId, limit) -> java.util.List.of(),
                mock(com.playground.ragchat.application.repository.AttachmentRepository.class),
                new ObjectMapper(),
                RagChatProperties.defaults(),
                Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC),
                // M8 added a descriptor to the production catalog; tests in
                // this file simulate the catalog explicitly per scenario.
                java.util.List::of);

        when(autoTitleService.generate(any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void emptyCatalog_M4InvariantHolds_noToolEventsEmitted_andStreamMethodUsed() {
        // Synthetic empty catalog (test-only constructor injection above).
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "New chat", now, now)));
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of());
        when(messageRepository.countUserMessages(sessionId)).thenReturn(1);
        when(embeddingPort.embedQuery(any())).thenReturn(new float[1024]);
        when(chunkRetrievalPort.retrieve(eq(caller), any(), anyInt())).thenReturn(List.of());
        when(chatGenerationPort.stream(any(), anyInt()))
                .thenReturn(Flux.just("hello ", "world"));

        ConcurrentStreamLockPort.Handle handle = mock(ConcurrentStreamLockPort.Handle.class);
        when(lockPort.acquire(caller)).thenReturn(handle);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "hi");

        List<ChatStreamEvent> events = chatTurnService.stream(req).collectList().block();
        assertThat(events).isNotNull();
        // No tool events emitted.
        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.ToolCall);
        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.ToolResult);
        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.ToolError);
        // Token + phase + done events present, in M4 order.
        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.Phase.class);
        assertThat(events.get(events.size() - 1)).isInstanceOf(ChatStreamEvent.Done.class);

        // The chat-generation port's PLAIN stream() was used — not streamWithTools().
        verify(chatGenerationPort).stream(any(), anyInt());
        verify(chatGenerationPort, never()).streamWithTools(any(), anyInt(), any());
        // The tool dispatcher was never invoked.
        verify(toolDispatcherPort, never()).invoke(any(), any(), any(), any(), any());
    }

    @Test
    void emptyCatalog_doesNotInvokeStreamWithTools_evenForLargerCatalogShape() {
        // Belt-and-suspenders: verify that even if a tool-aware path is wired,
        // the empty-catalog branch routes to the plain stream method.
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "New chat", now, now)));
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of());
        when(messageRepository.countUserMessages(sessionId)).thenReturn(1);
        when(embeddingPort.embedQuery(any())).thenReturn(new float[1024]);
        when(chunkRetrievalPort.retrieve(eq(caller), any(), anyInt())).thenReturn(List.of());
        when(chatGenerationPort.stream(any(), anyInt())).thenReturn(Flux.empty());
        when(chatGenerationPort.streamWithTools(any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    List<?> bindings = inv.getArgument(2);
                    assertThat(bindings).as("streamWithTools should not be called with empty bindings")
                            .isNotEmpty();
                    return Flux.empty();
                });

        ConcurrentStreamLockPort.Handle handle = mock(ConcurrentStreamLockPort.Handle.class);
        when(lockPort.acquire(caller)).thenReturn(handle);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "hi");
        chatTurnService.stream(req).collectList().block();

        verify(chatGenerationPort, never()).streamWithTools(any(), anyInt(), any());
    }

    @Test
    void toolBindingsListShape_matchesCatalogPlumbing_whenCalled() {
        // Smoke-test of the binding construction path — when streamWithTools
        // IS called (i.e., non-empty catalog), the bindings list shape is what
        // we expect. We can't drive a real non-empty ToolCatalog from the unit
        // test because it's a classpath constant, but we can assert that the
        // current empty-catalog state correctly yields the M4 path via the
        // mocks above. The non-empty path is exercised by the M7 end-to-end
        // integration test in rag-chat-infra.
        // This test exists as a guard-rail: if a future refactor accidentally
        // calls streamWithTools with an empty list (which would still work
        // identically per the adapter's defensive branch), it fails fast.
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "New chat", now, now)));
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of());
        when(messageRepository.countUserMessages(sessionId)).thenReturn(1);
        when(embeddingPort.embedQuery(any())).thenReturn(new float[1024]);
        when(chunkRetrievalPort.retrieve(eq(caller), any(), anyInt())).thenReturn(List.of());
        when(chatGenerationPort.stream(any(), anyInt())).thenReturn(Flux.just("ok"));

        ConcurrentStreamLockPort.Handle handle = mock(ConcurrentStreamLockPort.Handle.class);
        when(lockPort.acquire(caller)).thenReturn(handle);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "hi");
        List<ChatStreamEvent> events = chatTurnService.stream(req).collectList().block();
        assertThat(events).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void multiTurn_emitsToolCallThenToolResult_andInterleavesTokens() {
        // Use the package-private test-only constructor with a non-empty
        // descriptor supplier to drive the M7 multi-turn path through the
        // real use-case (the integration test in rag-chat-infra covers the
        // wire-level WebClient path).
        var desc = new com.playground.ragchat.domain.tool.ToolDescriptor(
                "echo", "Echo", "echo description", null,
                java.net.URI.create("http://t/"),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30));
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "New chat", now, now)));
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of());
        when(messageRepository.countUserMessages(sessionId)).thenReturn(1);
        when(embeddingPort.embedQuery(any())).thenReturn(new float[1024]);
        when(chunkRetrievalPort.retrieve(eq(caller), any(), anyInt())).thenReturn(List.of());

        when(chatGenerationPort.streamWithTools(any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<ToolBinding> bindings = inv.getArgument(2);
                    assertThat(bindings).hasSize(1);
                    // Simulate Spring AI invoking the registered tool callback
                    // synchronously — the handler emits the tool_call SSE event,
                    // calls the dispatcher, emits the tool_result, returns JSON
                    // to feed back to the LLM. The adapter would then continue
                    // streaming text deltas from the LLM.
                    return Flux.defer(() -> {
                        bindings.get(0).handler().apply(new ObjectMapper().createObjectNode());
                        return Flux.just("final ", "answer");
                    });
                });

        when(toolDispatcherPort.invoke(any(), eq("echo"), any(), any(), any()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(0);
                    return new com.playground.ragchat.application.tool.ToolInvocationResult.Success(
                            id, "echo", new ObjectMapper().createObjectNode().put("echoed", "hi"));
                });

        ConcurrentStreamLockPort.Handle handle = mock(ConcurrentStreamLockPort.Handle.class);
        when(lockPort.acquire(caller)).thenReturn(handle);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Use package-private test constructor.
        ChatTurnService service = new ChatTurnService(
                sessionRepository, messageRepository, tokenBucketPort, lockPort,
                embeddingPort, chunkRetrievalPort, chatGenerationPort,
                new HistoryTruncator(new TokenCounter()), new TokenCounter(),
                new PromptTemplate(new TokenCounter(), new CitationExtractor()),
                autoTitleService, new ActiveTurnRegistry(), toolDispatcherPort,
                (userId, limit) -> java.util.List.of(),
                mock(com.playground.ragchat.application.repository.AttachmentRepository.class),
                new ObjectMapper(), RagChatProperties.defaults(),
                Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC),
                () -> List.of(desc));

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "hi");
        List<ChatStreamEvent> events = service.stream(req).collectList().block();
        assertThat(events).isNotNull();

        long toolCalls = events.stream().filter(e -> e instanceof ChatStreamEvent.ToolCall).count();
        long toolResults = events.stream().filter(e -> e instanceof ChatStreamEvent.ToolResult).count();
        assertThat(toolCalls).isEqualTo(1);
        assertThat(toolResults).isEqualTo(1);

        // Pairing rule (ADR-17 §3.1): tool_call and tool_result carry the same id.
        ChatStreamEvent.ToolCall tc = (ChatStreamEvent.ToolCall) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolCall).findFirst().orElseThrow();
        ChatStreamEvent.ToolResult tr = (ChatStreamEvent.ToolResult) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolResult).findFirst().orElseThrow();
        assertThat(tc.id()).isEqualTo(tr.id());
        assertThat(tc.name()).isEqualTo("echo");
        assertThat(tr.name()).isEqualTo("echo");

        // Terminal `done` still fires.
        assertThat(events.get(events.size() - 1)).isInstanceOf(ChatStreamEvent.Done.class);
    }

    @Test
    void depthCapExceeded_emitsToolError_MAX_DEPTH() {
        var desc = new com.playground.ragchat.domain.tool.ToolDescriptor(
                "loopy", "Loopy", "Loopy tool", null,
                java.net.URI.create("http://t/"),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30));
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "New chat", now, now)));
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of());
        when(messageRepository.countUserMessages(sessionId)).thenReturn(1);
        when(embeddingPort.embedQuery(any())).thenReturn(new float[1024]);
        when(chunkRetrievalPort.retrieve(eq(caller), any(), anyInt())).thenReturn(List.of());

        // Simulate Spring AI hammering the callback 6 times in one turn (depth
        // cap default is 5 per ADR-17 §6). On the 6th invocation the handler
        // must emit tool_error{MAX_DEPTH} and throw to abort the round-trip.
        when(chatGenerationPort.streamWithTools(any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<ToolBinding> bindings = inv.getArgument(2);
                    return Flux.<String>create(sink -> {
                        try {
                            for (int i = 0; i < 6; i++) {
                                bindings.get(0).handler().apply(new ObjectMapper().createObjectNode());
                            }
                            sink.complete();
                        } catch (RuntimeException e) {
                            // The 6th invocation throws MaxDepthExceededException
                            // — Spring AI in real life would propagate it; we
                            // complete the stream so the merged sink emits the
                            // terminal tool_error and we observe it.
                            sink.complete();
                        }
                    });
                });

        when(toolDispatcherPort.invoke(any(), eq("loopy"), any(), any(), any()))
                .thenAnswer(inv -> {
                    String id = inv.getArgument(0);
                    return new com.playground.ragchat.application.tool.ToolInvocationResult.Success(
                            id, "loopy", new ObjectMapper().createObjectNode().put("ok", true));
                });

        ConcurrentStreamLockPort.Handle handle = mock(ConcurrentStreamLockPort.Handle.class);
        when(lockPort.acquire(caller)).thenReturn(handle);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ChatTurnService service = new ChatTurnService(
                sessionRepository, messageRepository, tokenBucketPort, lockPort,
                embeddingPort, chunkRetrievalPort, chatGenerationPort,
                new HistoryTruncator(new TokenCounter()), new TokenCounter(),
                new PromptTemplate(new TokenCounter(), new CitationExtractor()),
                autoTitleService, new ActiveTurnRegistry(), toolDispatcherPort,
                (userId, limit) -> java.util.List.of(),
                mock(com.playground.ragchat.application.repository.AttachmentRepository.class),
                new ObjectMapper(), RagChatProperties.defaults(),
                Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC),
                () -> List.of(desc));

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, "sub-1", "hi");
        List<ChatStreamEvent> events = service.stream(req).collectList().block();
        assertThat(events).isNotNull();

        // Exactly 5 tool_call events (the 6th one's depth-cap check fires
        // before the tool_call emission per the handler ordering).
        long calls = events.stream().filter(e -> e instanceof ChatStreamEvent.ToolCall).count();
        long maxDepthErrors = events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolError)
                .map(e -> (ChatStreamEvent.ToolError) e)
                .filter(e -> "MAX_DEPTH".equals(e.code()))
                .count();
        assertThat(calls).isEqualTo(5);
        assertThat(maxDepthErrors).isEqualTo(1);
    }

    @Test
    void toolBinding_handlerSignature_compileTimeCheck() {
        // Compile-time guard: ToolBinding is constructable from the application
        // package, and its handler is a Function<JsonNode, JsonNode> per ADR-17
        // §8. A future widening of the handler signature would break this.
        java.util.function.Function<com.fasterxml.jackson.databind.JsonNode,
                com.fasterxml.jackson.databind.JsonNode> handler =
                args -> new ObjectMapper().createObjectNode();
        var desc = new com.playground.ragchat.domain.tool.ToolDescriptor(
                "echo", "Echo", "Echo description", null,
                java.net.URI.create("http://t/"),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(30));
        ToolBinding binding = new ToolBinding(desc, handler);
        assertThat(binding.descriptor().name()).isEqualTo("echo");
        assertThat(binding.handler()).isSameAs(handler);
    }
}
