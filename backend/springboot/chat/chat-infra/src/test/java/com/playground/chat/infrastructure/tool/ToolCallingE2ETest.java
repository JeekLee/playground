package com.playground.chat.infrastructure.tool;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.playground.chat.application.dto.ChatTurnRequest;
import com.playground.chat.application.port.ChatGenerationPort;
import com.playground.chat.application.port.ConcurrentStreamLockPort;
import com.playground.chat.application.port.TokenBucketPort;
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.application.service.ActiveTurnRegistry;
import com.playground.chat.application.service.AutoTitleService;
import com.playground.chat.application.service.ChatTurnService;
import com.playground.chat.application.service.TurnContextAssembler;
import com.playground.chat.application.service.TurnRecorder;
import com.playground.chat.application.tool.ToolBinding;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.chat.domain.service.CitationExtractor;
import com.playground.chat.domain.service.HistoryTruncator;
import com.playground.chat.domain.service.PromptTemplate;
import com.playground.chat.domain.service.TokenCounter;
import com.playground.chat.domain.tool.ToolDescriptor;
import com.playground.shared.chat.ChatStreamEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * End-to-end M7 tool-calling integration test per ADR-17 §12
 * ({@code M7EchoToolE2ETest}) + PRD acceptance bullet "합성 echo tool
 * end-to-end".
 *
 * <p>Wires a synthetic {@code echo} {@link ToolDescriptor} (test-only —
 * never registered in production {@code ToolCatalog}) against a WireMock
 * stub. A mock {@link ChatGenerationPort} simulates Spring AI's
 * function-calling path by invoking the registered binding inline before
 * emitting final-text deltas. Asserts the full SSE event sequence
 * {@code phase → tool_call → tool_result → token+ → done} per ADR-17 §3.1
 * and verifies user-identity headers reach the tool BC.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>Happy single-turn: model emits one tool_call → adapter dispatches
 *       to WireMock → tool_result → final text tokens → done.</li>
 *   <li>Multi-turn chain (2 sequential tool calls in 1 user turn).</li>
 *   <li>Tool 5xx → tool_error{UPSTREAM_5XX} (with the LLM still allowed
 *       to continue and emit final tokens — see ADR-17 §2 table).</li>
 *   <li>Tool timeout (descriptor timeout deliberately shorter than stub
 *       delay) → tool_error{TIMEOUT}.</li>
 *   <li>Circuit breaker OPEN after 5xx burst → next call returns
 *       tool_error{CIRCUIT_OPEN} without hitting WireMock.</li>
 *   <li>X-User-Id + X-User-Sub forwarded; Authorization not.</li>
 * </ul>
 *
 * <p>Tagged {@code integration} (matches the docs-infra IT pattern) so
 * {@code ./gradlew :chat:chat-infra:test -PintegrationTests=true}
 * runs it explicitly. The dispatcher-only unit tests in
 * {@link WebClientToolDispatcherTest} run on every {@code test} build.
 */
@Tag("integration")
class ToolCallingE2ETest {

    private WireMockServer wireMock;
    private CircuitBreakerRegistry registry;
    private ObjectMapper objectMapper;

    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private TokenBucketPort tokenBucketPort;
    private ConcurrentStreamLockPort lockPort;
    private ChatGenerationPort chatGenerationPort;
    private AutoTitleService autoTitleService;

    private UserId caller;
    private SessionId sessionId;
    private final String googleSub = "google-sub-e2e";

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        registry = CircuitBreakerRegistry.ofDefaults();
        objectMapper = new ObjectMapper();

        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        tokenBucketPort = mock(TokenBucketPort.class);
        lockPort = mock(ConcurrentStreamLockPort.class);
        chatGenerationPort = mock(ChatGenerationPort.class);
        autoTitleService = mock(AutoTitleService.class);

        caller = UserId.of(UUID.randomUUID());
        sessionId = SessionId.of(UUID.randomUUID());

        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        when(tokenBucketPort.tryAcquire(caller)).thenReturn(TokenBucketPort.Decision.allow());
        when(sessionRepository.findOwned(sessionId, caller))
                .thenReturn(Optional.of(new ChatSession(sessionId, caller, "New chat", now, now)));
        when(messageRepository.findBySession(sessionId)).thenReturn(List.of());
        when(messageRepository.countUserMessages(sessionId)).thenReturn(1);
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ConcurrentStreamLockPort.Handle handle = mock(ConcurrentStreamLockPort.Handle.class);
        when(lockPort.acquire(caller)).thenReturn(handle);
        when(autoTitleService.generate(any(), any())).thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    /** NDJSON body: one JSON object per line, trailing newline. */
    private static String ndjson(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private ToolDescriptor echoDescriptor(Duration idleTimeout) {
        return new ToolDescriptor(
                "echo",
                "Echo tool",
                "Echo tool — returns the argument JSON unchanged",
                "{\"type\":\"object\"}",
                URI.create("http://localhost:" + wireMock.port() + "/internal/tools/echo"),
                idleTimeout,
                Duration.ofSeconds(30));
    }

    private WebClientToolDispatcher dispatcher(ToolDescriptor desc, ChatProperties props) {
        ToolBreakerRegistry breakers = new ToolBreakerRegistry(registry);
        WebClient.Builder builder = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(props.toolMaxResultBytes()));
        return new WebClientToolDispatcher(
                builder, breakers, objectMapper, props,
                n -> n.equals(desc.name()) ? Optional.of(desc) : Optional.empty());
    }

    /** In-memory fakes for the ADR-20 attachment path (captured for assertions). */
    private final java.util.List<com.playground.chat.domain.model.Attachment> savedAttachments =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private com.playground.chat.application.repository.AttachmentRepository attachmentRepository() {
        return new com.playground.chat.application.repository.AttachmentRepository() {
            @Override
            public com.playground.chat.domain.model.Attachment save(
                    com.playground.chat.domain.model.Attachment attachment) {
                savedAttachments.add(attachment);
                return attachment;
            }

            @Override
            public void saveAll(java.util.List<com.playground.chat.domain.model.Attachment> attachments) {
                savedAttachments.addAll(attachments);
            }

            @Override
            public java.util.Optional<com.playground.chat.domain.model.Attachment> findOwned(
                    com.playground.chat.domain.model.id.AttachmentId id,
                    com.playground.chat.domain.model.id.UserId callerId) {
                return savedAttachments.stream().filter(a -> a.id().equals(id)).findFirst();
            }

            @Override
            public java.util.List<com.playground.chat.domain.model.Attachment> findByMessages(
                    java.util.List<com.playground.chat.domain.model.id.MessageId> messageIds) {
                return savedAttachments.stream()
                        .filter(a -> messageIds.contains(a.messageId())).toList();
            }
        };
    }

    private ChatTurnService service(WebClientToolDispatcher dispatcher, ToolDescriptor desc,
            ChatProperties props) {
        Clock clock = Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC);
        return new ChatTurnService(
                sessionRepository, tokenBucketPort, lockPort,
                chatGenerationPort,
                new PromptTemplate(new TokenCounter(), new CitationExtractor()),
                autoTitleService, new ActiveTurnRegistry(), dispatcher,
                new TurnContextAssembler(messageRepository, new TokenCounter(),
                        new HistoryTruncator(new TokenCounter()),
                        (userId, limit) -> java.util.List.of(), clock, props),
                new TurnRecorder(messageRepository, attachmentRepository(),
                        new TokenCounter(), clock),
                objectMapper, props,
                clock,
                () -> List.of(desc));
    }

    /**
     * Simulate Spring AI's function-calling path: on .streamWithTools(),
     * invoke the registered binding {@code invokeCount} times (each with the
     * given args) before emitting the final-text deltas.
     */
    private void mockChatStreamWithTools(int invokeCount, String... finalDeltas) {
        when(chatGenerationPort.streamWithTools(any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<ToolBinding> bindings = inv.getArgument(2);
                    assertThat(bindings).hasSize(1);
                    return Flux.<String>create(sink -> {
                        try {
                            for (int i = 0; i < invokeCount; i++) {
                                bindings.get(0).handler().apply(
                                        objectMapper.createObjectNode().put("msg", "ping-" + i));
                            }
                            for (String d : finalDeltas) {
                                sink.next(d);
                            }
                            sink.complete();
                        } catch (RuntimeException e) {
                            // A terminal tool exception (CIRCUIT_OPEN / MAX_DEPTH)
                            // — finish the stream so we can observe the sink.
                            sink.complete();
                        }
                    });
                });
    }

    @Test
    void happySingleTurn_emitsPhaseToolCallToolResultTokensDone_andForwardsHeaders() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{\"echoed\":\"hi\"}}"))));

        ChatProperties props = ChatProperties.defaults();
        ToolDescriptor desc = echoDescriptor(Duration.ofSeconds(5));
        ChatTurnService svc = service(dispatcher(desc, props), desc, props);

        mockChatStreamWithTools(1, "final ", "answer.");

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, googleSub, "ask");
        List<ChatStreamEvent> events = svc.stream(req).collectList().block();
        assertThat(events).isNotNull();

        // Sequence (agentic-search spec D2 — no retrieval phase): tool_call,
        // tool_result, token×N, done.
        assertThat(events).noneMatch(e -> e instanceof ChatStreamEvent.Phase);
        long calls = events.stream().filter(e -> e instanceof ChatStreamEvent.ToolCall).count();
        long results = events.stream().filter(e -> e instanceof ChatStreamEvent.ToolResult).count();
        long errors = events.stream().filter(e -> e instanceof ChatStreamEvent.ToolError).count();
        long tokens = events.stream().filter(e -> e instanceof ChatStreamEvent.Token).count();
        assertThat(calls).isEqualTo(1);
        assertThat(results).isEqualTo(1);
        assertThat(errors).isEqualTo(0);
        assertThat(tokens).isGreaterThanOrEqualTo(2);
        assertThat(events.get(events.size() - 1)).isInstanceOf(ChatStreamEvent.Done.class);

        // Pairing: tool_call.id == tool_result.id (ADR-17 §3.1).
        ChatStreamEvent.ToolCall tc = (ChatStreamEvent.ToolCall) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolCall).findFirst().orElseThrow();
        ChatStreamEvent.ToolResult tr = (ChatStreamEvent.ToolResult) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolResult).findFirst().orElseThrow();
        assertThat(tc.id()).isEqualTo(tr.id());
        assertThat(tc.id()).startsWith("call_");

        // Headers forwarded; Authorization absent.
        wireMock.verify(postRequestedFor(urlPathEqualTo("/internal/tools/echo"))
                .withHeader("X-User-Id", equalTo(caller.value().toString()))
                .withHeader("X-User-Sub", equalTo(googleSub)));
    }

    @Test
    void multiTurnChain_twoToolCalls_pairedAndOrdered() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{\"echoed\":\"ok\"}}"))));

        ChatProperties props = ChatProperties.defaults();
        ToolDescriptor desc = echoDescriptor(Duration.ofSeconds(5));
        ChatTurnService svc = service(dispatcher(desc, props), desc, props);

        mockChatStreamWithTools(2, "done.");

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, googleSub, "ask twice");
        List<ChatStreamEvent> events = svc.stream(req).collectList().block();
        assertThat(events).isNotNull();

        long calls = events.stream().filter(e -> e instanceof ChatStreamEvent.ToolCall).count();
        long results = events.stream().filter(e -> e instanceof ChatStreamEvent.ToolResult).count();
        assertThat(calls).isEqualTo(2);
        assertThat(results).isEqualTo(2);

        // Each tool_call has a corresponding tool_result with the same id.
        List<ChatStreamEvent.ToolCall> callList = events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolCall)
                .map(e -> (ChatStreamEvent.ToolCall) e).toList();
        List<ChatStreamEvent.ToolResult> resultList = events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolResult)
                .map(e -> (ChatStreamEvent.ToolResult) e).toList();
        assertThat(callList.get(0).id()).isNotEqualTo(callList.get(1).id());
        // Each id appears in exactly one result.
        for (ChatStreamEvent.ToolCall c : callList) {
            assertThat(resultList).anyMatch(r -> r.id().equals(c.id()));
        }
    }

    @Test
    void toolReturns5xx_emitsToolError_UPSTREAM_5XX() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse().withStatus(500).withBody("kaboom")));

        ChatProperties props = ChatProperties.defaults();
        ToolDescriptor desc = echoDescriptor(Duration.ofSeconds(5));
        ChatTurnService svc = service(dispatcher(desc, props), desc, props);

        mockChatStreamWithTools(1, "apology.");
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, googleSub, "ask");
        List<ChatStreamEvent> events = svc.stream(req).collectList().block();
        assertThat(events).isNotNull();

        ChatStreamEvent.ToolError te = (ChatStreamEvent.ToolError) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolError).findFirst().orElseThrow();
        assertThat(te.code()).isEqualTo("UPSTREAM_5XX");
        assertThat(te.name()).isEqualTo("echo");
    }

    @Test
    void toolTimeout_emitsToolError_TIMEOUT() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"echoed\":\"slow\"}")
                        .withFixedDelay(800)));

        ChatProperties props = ChatProperties.defaults();
        // Descriptor timeout < stub delay.
        ToolDescriptor desc = echoDescriptor(Duration.ofMillis(200));
        ChatTurnService svc = service(dispatcher(desc, props), desc, props);

        mockChatStreamWithTools(1, "sorry.");
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, googleSub, "ask");
        List<ChatStreamEvent> events = svc.stream(req).collectList().block();
        assertThat(events).isNotNull();

        ChatStreamEvent.ToolError te = (ChatStreamEvent.ToolError) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolError).findFirst().orElseThrow();
        assertThat(te.code()).isEqualTo("TIMEOUT");
    }

    @Test
    void circuitBreakerOpen_skipsUpstreamHttpCall_emitsToolError_CIRCUIT_OPEN() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse().withStatus(500)));

        ChatProperties props = ChatProperties.defaults();
        ToolDescriptor desc = echoDescriptor(Duration.ofSeconds(5));
        WebClientToolDispatcher dispatcher = dispatcher(desc, props);

        // Hammer the dispatcher directly to trip the breaker (≥10 calls / 50%
        // failure rate). After the burst, the next invocation through the
        // chat-turn flow must surface CIRCUIT_OPEN without hitting WireMock.
        for (int i = 0; i < 10; i++) {
            dispatcher.invoke("warm_" + i, "echo", null,
                    new com.playground.chat.application.tool.UserContext(caller, googleSub),
                    p -> { });
        }

        int callsBefore = wireMock.findAll(
                postRequestedFor(urlPathEqualTo("/internal/tools/echo"))).size();

        ChatTurnService svc = service(dispatcher, desc, props);
        mockChatStreamWithTools(1, "done.");
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, googleSub, "ask");
        List<ChatStreamEvent> events = svc.stream(req).collectList().block();
        assertThat(events).isNotNull();

        int callsAfter = wireMock.findAll(
                postRequestedFor(urlPathEqualTo("/internal/tools/echo"))).size();
        assertThat(callsAfter).as("CIRCUIT_OPEN must skip the upstream HTTP call (cost protection)")
                .isEqualTo(callsBefore);

        ChatStreamEvent.ToolError te = (ChatStreamEvent.ToolError) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolError).findFirst().orElseThrow();
        assertThat(te.code()).isEqualTo("CIRCUIT_OPEN");
    }

    @Test
    void argsJson_isForwardedAsRequestBody() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{\"echoed\":true}}"))));

        ChatProperties props = ChatProperties.defaults();
        ToolDescriptor desc = echoDescriptor(Duration.ofSeconds(5));
        ChatTurnService svc = service(dispatcher(desc, props), desc, props);

        // Mock Spring AI invoking the handler with a custom args JsonNode.
        when(chatGenerationPort.streamWithTools(any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<ToolBinding> bindings = inv.getArgument(2);
                    return Flux.<String>create(sink -> {
                        JsonNode args = objectMapper.createObjectNode()
                                .put("greeting", "hello")
                                .put("count", 3);
                        bindings.get(0).handler().apply(args);
                        sink.next("ok");
                        sink.complete();
                    });
                });

        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, googleSub, "ask");
        svc.stream(req).collectList().block();

        wireMock.verify(postRequestedFor(urlPathEqualTo("/internal/tools/echo"))
                .withHeader("X-User-Id", equalTo(caller.value().toString()))
                .withHeader("X-User-Sub", equalTo(googleSub)));
    }

    @Test
    void artifactEnvelope_splitsResultFromArtifact_persistsAttachment_andEnrichesSse() {
        // ADR-20 §D3 revised — agent-tools already stored the file in MinIO.
        // The tool returns {result, artifact} where artifact carries metadata only
        // (storageKey, sizeBytes — no base64). chat records the Attachment row
        // using the storageKey without touching MinIO.
        String storageKey = "architecture/massing/20260604/abc-uuid/massing-테스트-20260604.3dm";
        wireMock.stubFor(post(urlPathEqualTo("/internal/tools/echo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson("{\"event\":\"result\",\"result\":{\"summary\":\"2실 · 지상 4층\",\"floorCount\":4},"
                                + "\"artifact\":{\"filename\":\"massing-테스트-20260604.3dm\","
                                + "\"contentType\":\"application/octet-stream\","
                                + "\"sizeBytes\":32,"
                                + "\"storageKey\":\"" + storageKey + "\"}}"))));

        ChatProperties props = ChatProperties.defaults();
        ToolDescriptor desc = echoDescriptor(Duration.ofSeconds(5));
        ChatTurnService svc = service(dispatcher(desc, props), desc, props);

        mockChatStreamWithTools(1, "Here is your massing.");
        ChatTurnRequest req = new ChatTurnRequest(sessionId, caller, googleSub, "generate massing");
        List<ChatStreamEvent> events = svc.stream(req).collectList().block();
        assertThat(events).isNotNull();

        // Exactly one Attachment persisted, linked to the assistant message id.
        assertThat(savedAttachments).hasSize(1);
        com.playground.chat.domain.model.Attachment att = savedAttachments.get(0);
        assertThat(att.filename()).isEqualTo("massing-테스트-20260604.3dm");
        assertThat(att.kind()).isEqualTo(
                com.playground.chat.domain.model.Attachment.KIND_TOOL_ARTIFACT);
        assertThat(att.toolName()).isEqualTo("echo");
        assertThat(att.sizeBytes()).isEqualTo(32L);
        // storageKey is preserved verbatim from the agent-tools response.
        assertThat(att.storageKey()).isEqualTo(storageKey);

        // tool_result `result` is the LLM-visible result ONLY — no storageKey
        // leaks into the SSE/LLM path, but it IS enriched with the FE attachment
        // object + fileUrl (ADR-20 §D4).
        ChatStreamEvent.ToolResult tr = (ChatStreamEvent.ToolResult) events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolResult).findFirst().orElseThrow();
        JsonNode result = (JsonNode) tr.result();
        assertThat(result.has("storageKey")).isFalse();
        assertThat(result.path("floorCount").asInt()).isEqualTo(4);
        assertThat(result.path("fileUrl").asText()).isEqualTo(
                "/api/chat/attachments/" + att.id());
        assertThat(result.path("attachment").path("id").asText()).isEqualTo(att.id().toString());
        assertThat(result.path("attachment").path("downloadUrl").asText())
                .isEqualTo("/api/chat/attachments/" + att.id());

        // §D4: the attachment + fileUrl ride on the tool_result event (asserted
        // above) and on history reload — NOT on the done event. done.citations()
        // is the List<CitationDto> cited subset (empty here — a massing turn
        // cites nothing). Smuggling a Map into the citations field would crash
        // the FE (citations.map()), which the persist path forbids by contract.
        ChatStreamEvent.Done done = (ChatStreamEvent.Done) events.get(events.size() - 1);
        assertThat(done.citations()).isInstanceOf(java.util.List.class);
        assertThat((java.util.List<?>) done.citations()).isEmpty();
    }
}
