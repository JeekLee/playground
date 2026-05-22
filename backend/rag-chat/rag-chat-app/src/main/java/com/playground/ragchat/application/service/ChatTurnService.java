package com.playground.ragchat.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.ragchat.application.dto.ChatTurnRequest;
import com.playground.ragchat.application.dto.CitationDto;
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
import com.playground.ragchat.application.tool.ToolInvocationResult;
import com.playground.ragchat.application.tool.UserContext;
import com.playground.ragchat.domain.exception.RagChatErrorCode;
import com.playground.ragchat.domain.model.ChatSession;
import com.playground.ragchat.domain.model.Message;
import com.playground.ragchat.domain.model.MessageCitation;
import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.ragchat.domain.model.vo.TokenCount;
import com.playground.ragchat.domain.service.HistoryTruncator;
import com.playground.ragchat.domain.service.PromptTemplate;
import com.playground.ragchat.domain.service.TokenCounter;
import com.playground.ragchat.domain.tool.ToolCatalog;
import com.playground.ragchat.domain.tool.ToolDescriptor;
import com.playground.ragchat.domain.tool.ToolErrorCode;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.shared.error.ExceptionCreator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * The per-turn orchestrator per ADR-14 §1 (the {@code StreamChatTurnUseCase}).
 * Composes auth → rate-limit → session-validate → concurrent-stream lock →
 * history load + truncate → embed → retrieve → emit retrieval SSE event →
 * persist user message → assemble prompt → stream tokens → parse markers →
 * persist assistant message + cited subset → emit done SSE event → release
 * lock.
 *
 * <p>Returns a {@link Flux} of {@link ChatStreamEvent}; the controller maps
 * each event to its SSE shape per ADR-14 §C / spec §5.2.
 *
 * <p>Aborts (subscriber cancel) propagate via Reactor cancellation per
 * ADR-14 §14 — the JDBC + WebClient I/O dispose, and the assistant message
 * is NOT persisted (§13).
 */
@Service
public class ChatTurnService {

    private static final Log log = LogFactory.getLog(ChatTurnService.class);

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final TokenBucketPort tokenBucketPort;
    private final ConcurrentStreamLockPort lockPort;
    private final EmbeddingPort embeddingPort;
    private final ChunkRetrievalPort chunkRetrievalPort;
    private final ChatGenerationPort chatGenerationPort;
    private final HistoryTruncator historyTruncator;
    private final TokenCounter tokenCounter;
    private final PromptTemplate promptTemplate;
    private final AutoTitleService autoTitleService;
    private final ActiveTurnRegistry activeTurnRegistry;
    private final ToolDispatcherPort toolDispatcherPort;
    private final ObjectMapper objectMapper;
    private final RagChatProperties properties;
    private final Clock clock;
    /** Source of registered tool descriptors. Defaults to {@code ToolCatalog::descriptors}. */
    private final java.util.function.Supplier<List<ToolDescriptor>> toolDescriptorSupplier;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatTurnService(
            SessionRepository sessionRepository,
            MessageRepository messageRepository,
            TokenBucketPort tokenBucketPort,
            ConcurrentStreamLockPort lockPort,
            EmbeddingPort embeddingPort,
            ChunkRetrievalPort chunkRetrievalPort,
            ChatGenerationPort chatGenerationPort,
            HistoryTruncator historyTruncator,
            TokenCounter tokenCounter,
            PromptTemplate promptTemplate,
            AutoTitleService autoTitleService,
            ActiveTurnRegistry activeTurnRegistry,
            ToolDispatcherPort toolDispatcherPort,
            ObjectMapper objectMapper,
            RagChatProperties properties,
            Clock clock) {
        this(sessionRepository, messageRepository, tokenBucketPort, lockPort, embeddingPort,
                chunkRetrievalPort, chatGenerationPort, historyTruncator, tokenCounter,
                promptTemplate, autoTitleService, activeTurnRegistry, toolDispatcherPort,
                objectMapper, properties, clock, ToolCatalog::descriptors);
    }

    /**
     * Test-friendly constructor — pluggable tool-descriptor supplier.
     * Public so end-to-end tests in {@code rag-chat-infra} (different
     * package) can wire a synthetic catalog without reaching for
     * reflection. Production wiring uses the primary constructor which
     * binds the supplier to {@code ToolCatalog::descriptors}.
     */
    public ChatTurnService(
            SessionRepository sessionRepository,
            MessageRepository messageRepository,
            TokenBucketPort tokenBucketPort,
            ConcurrentStreamLockPort lockPort,
            EmbeddingPort embeddingPort,
            ChunkRetrievalPort chunkRetrievalPort,
            ChatGenerationPort chatGenerationPort,
            HistoryTruncator historyTruncator,
            TokenCounter tokenCounter,
            PromptTemplate promptTemplate,
            AutoTitleService autoTitleService,
            ActiveTurnRegistry activeTurnRegistry,
            ToolDispatcherPort toolDispatcherPort,
            ObjectMapper objectMapper,
            RagChatProperties properties,
            Clock clock,
            java.util.function.Supplier<List<ToolDescriptor>> toolDescriptorSupplier) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.tokenBucketPort = tokenBucketPort;
        this.lockPort = lockPort;
        this.embeddingPort = embeddingPort;
        this.chunkRetrievalPort = chunkRetrievalPort;
        this.chatGenerationPort = chatGenerationPort;
        this.historyTruncator = historyTruncator;
        this.tokenCounter = tokenCounter;
        this.promptTemplate = promptTemplate;
        this.autoTitleService = autoTitleService;
        this.activeTurnRegistry = activeTurnRegistry;
        this.toolDispatcherPort = toolDispatcherPort;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.toolDescriptorSupplier = toolDescriptorSupplier;
    }

    public Flux<ChatStreamEvent> stream(ChatTurnRequest request) {
        Objects.requireNonNull(request, "request");

        // Pre-stream validations surface as HTTP errors (ADR-14 §C "Pre-stream"
        // row); thrown exceptions are translated by the controller's reactive
        // advice before any SSE handshake commits.
        validateMessageSize(request.message());

        return Mono.fromCallable(() -> guardAndPrepare(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(prep -> streamWithLock(request, prep));
    }

    private void validateMessageSize(String message) {
        if (message == null || message.isBlank()) {
            throw ExceptionCreator.of(RagChatErrorCode.MESSAGE_BLANK).build();
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > RagChatProperties.MAX_USER_MESSAGE_BYTES) {
            throw ExceptionCreator.of(RagChatErrorCode.MESSAGE_TOO_LARGE).build();
        }
    }

    /** Phase 1: pre-stream side-effects that may throw HTTP errors. */
    private Preparation guardAndPrepare(ChatTurnRequest request) {
        // 1. Rate limit.
        TokenBucketPort.Decision decision = tokenBucketPort.tryAcquire(request.caller());
        if (!decision.allowed()) {
            throw ExceptionCreator
                    .of(RagChatErrorCode.RATE_LIMITED, decision.retryAfterSeconds())
                    .build();
        }

        // 2. Session validation — must exist and belong to caller.
        ChatSession session = sessionRepository.findOwned(request.sessionId(), request.caller())
                .orElseThrow(() -> ExceptionCreator.of(RagChatErrorCode.SESSION_NOT_FOUND).build());

        // 3. History load + truncate.
        List<Message> rawHistory = messageRepository.findBySession(session.id());
        TokenCount currentMessageTokens = tokenCounter.count(request.message());
        List<Message> truncated = historyTruncator.truncate(
                rawHistory, properties.maxHistoryTokens(), currentMessageTokens);

        // 4. Embed query.
        float[] embedding;
        try {
            embedding = embeddingPort.embedQuery(request.message());
        } catch (RuntimeException e) {
            throw ExceptionCreator.of(RagChatErrorCode.EMBEDDING_FAILED, e.getMessage()).build();
        }

        // 5. Retrieve K chunks.
        List<RetrievedChunk> retrieved;
        try {
            retrieved = chunkRetrievalPort.retrieve(request.caller(), embedding, properties.retrievalK());
        } catch (RuntimeException e) {
            throw ExceptionCreator.of(RagChatErrorCode.RETRIEVAL_FAILED, e.getMessage()).build();
        }

        // 6. Persist the user message before opening the stream so a mid-stream
        // refresh shows the user turn even if the assistant never lands.
        Message userMessage = Message.newUserTurn(
                session.id(), request.caller(), request.message(), clock.instant());
        Message savedUser = messageRepository.save(userMessage);

        // 7. Determine whether this is the first user turn for the auto-title trigger.
        int userMessageCount = messageRepository.countUserMessages(session.id());
        boolean firstTurn = userMessageCount == 1;

        log.info("turn_start sessionId=" + session.id()
                + " userId=" + request.caller()
                + " userSub=" + maskSub(request.userSub())
                + " retrievedK=" + retrieved.size()
                + " historyTurns=" + truncated.size());

        return new Preparation(session, retrieved, truncated, savedUser, firstTurn);
    }

    /** Phase 2: lock + stream. The lock is released in doFinally. */
    private Flux<ChatStreamEvent> streamWithLock(ChatTurnRequest request, Preparation prep) {
        ConcurrentStreamLockPort.Handle handle = lockPort.acquire(request.caller());
        if (handle == null) {
            // ADR-14 §D — latest-wins should have freed the previous holder; a
            // null here means even the abort path failed. Surface as 503.
            throw ExceptionCreator.of(RagChatErrorCode.GATEWAY_DOWN).build();
        }

        final StringBuilder accumulated = new StringBuilder(4096);
        final java.util.concurrent.atomic.AtomicBoolean errored =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        String prompt = promptTemplate.assemble(
                prep.retrieved(), prep.truncatedHistory(), request.message(),
                properties.perChunkTokenBudget());

        // Spec §5.2 (revised in PR B): the retrieval phase now ships just
        // a count, not the candidate chunk list — the citation cards land
        // exclusively at terminal `done` once the marker-extraction step
        // narrows the set to the actually-cited subset. UI uses this
        // event for the "N개 청크 확인 중…" progress label.
        Flux<ChatStreamEvent> retrievalEvent = Flux.just(
                (ChatStreamEvent) new ChatStreamEvent.Phase(
                        "retrieval",
                        "참고 문서 확인 중",
                        java.util.Map.of("count", prep.retrieved().size())));

        // ADR-17 §1 — branch on whether any tools are registered. Empty catalog
        // means the M4 invariant path: no tool callbacks passed to Spring AI,
        // no `tool_*` SSE events emitted, behavior identical to M4 ship (PRD
        // Story 10).
        List<ToolDescriptor> descriptors = toolDescriptorSupplier.get();
        // Sink the tool callbacks push events into. Multicast so the orchestrator
        // can interleave the events with the token flux as they arrive.
        Sinks.Many<ChatStreamEvent> toolEventSink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicInteger depth = new AtomicInteger(0);
        UserContext userCtx = new UserContext(request.caller(), request.userSub());

        List<ToolBinding> bindings = descriptors.isEmpty()
                ? List.of()
                : buildBindings(descriptors, toolEventSink, depth, userCtx);

        Flux<String> rawDeltas = bindings.isEmpty()
                ? chatGenerationPort.stream(prompt, properties.maxCompletionTokens())
                : chatGenerationPort.streamWithTools(
                        prompt, properties.maxCompletionTokens(), bindings);

        // The token flux signals upstream completion of the LLM (and all its
        // internal tool-calling round-trips done by Spring AI). When it
        // terminates, close the tool-event sink so the merge completes.
        Flux<ChatStreamEvent> tokens = rawDeltas
                .timeout(java.time.Duration.ofSeconds(35))
                .doOnNext(accumulated::append)
                .map(delta -> (ChatStreamEvent) new ChatStreamEvent.Token(delta))
                .onErrorResume(err -> {
                    log.warn("stream_error sessionId=" + prep.session().id()
                            + " userId=" + request.caller()
                            + " reason=" + err.toString());
                    errored.set(true);
                    if (isCircuitOpen(err)) {
                        return Flux.just(ChatStreamEvent.Error.gatewayDown());
                    }
                    return Flux.just(ChatStreamEvent.Error.internal(err.getMessage()));
                })
                .doFinally(sig -> toolEventSink.tryEmitComplete());

        // Merge the tool events (emitted from inside Spring AI's function-calling
        // callbacks) with the token deltas. The merge preserves the order each
        // upstream emits in: tool events arrive when the callback fires; token
        // events arrive as Spring AI streams the model's text.
        Flux<ChatStreamEvent> mergedTokensAndToolEvents = bindings.isEmpty()
                ? tokens
                : Flux.merge(toolEventSink.asFlux(), tokens);

        Mono<ChatStreamEvent> done = Mono.defer(() -> {
            if (errored.get()) {
                return Mono.empty();
            }
            return persistAssistantAndDone(prep, accumulated.toString(), request);
        });

        // Source pipeline — must run to completion on the server even when the
        // SSE client disconnects mid-stream, so the assistant message + citations
        // land in `chat.messages` / `chat.message_citations`. Lock release and
        // auto-title fire from the source's terminal signal (not from the
        // client-facing flux).
        Flux<ChatStreamEvent> source = Flux.concat(retrievalEvent, mergedTokensAndToolEvents, done)
                .doFinally(signal -> {
                    activeTurnRegistry.unregister(prep.session().id());
                    try {
                        handle.release();
                    } catch (RuntimeException e) {
                        log.warn("lock release failed sessionId=" + prep.session().id()
                                + " reason=" + e.toString());
                    }
                    if (prep.firstTurn() && signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
                        autoTitleService.generate(prep.session().id(), request.message())
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();
                    }
                });

        // Decouple client lifecycle from source: replay+autoConnect(1) makes the
        // pipeline hot, the background "keep-alive" subscribe drives it through
        // to completion regardless of whether the SSE client stays connected,
        // and late re-subscribers replay the buffered events from the start.
        // `MAX_REPLAY_EVENTS` >> max tokens-per-turn (spec §B `maxCompletionTokens`)
        // so the buffer never overflows.
        Flux<ChatStreamEvent> shared = source
                .replay(MAX_REPLAY_EVENTS)
                .autoConnect(1);

        // Register so `GET /api/rag/chat/sessions/{id}/stream` can attach late
        // subscribers (mid-stream re-join) before the keep-alive subscribe
        // wires the source up. Unregister happens in source.doFinally above so
        // the entry disappears as soon as the pipeline terminates.
        activeTurnRegistry.register(prep.session().id(), request.caller(), shared);

        shared.subscribe(
                event -> { /* drain — side effects accumulate + persist via the source */ },
                err -> log.warn("chat stream upstream error sessionId=" + prep.session().id()
                        + " userId=" + request.caller() + " reason=" + err, err),
                () -> { /* upstream completed; doFinally already ran */ });

        return shared
                .doOnCancel(() -> log.info("stream_abort_client sessionId=" + prep.session().id()
                        + " userId=" + request.caller()
                        + " bytesEmitted=" + accumulated.length()
                        + " (server pipeline keeps going — assistant message will persist)"));
    }

    private static final int MAX_REPLAY_EVENTS = 4096;

    private Mono<ChatStreamEvent> persistAssistantAndDone(
            Preparation prep, String accumulatedText, ChatTurnRequest request) {
        return Mono.fromCallable(() -> {
            if (accumulatedText == null || accumulatedText.isEmpty()) {
                // The LLM produced no tokens — emit done with empty content so
                // the client can close the stream cleanly. We still persist an
                // empty assistant message so the message count remains paired.
            }

            // Renumber the [N] markers so the cited subset is rendered as
            // a dense [1][2]… sequence regardless of which positions in
            // the original retrieval window the LLM actually used. The
            // assistant text shipped to the client (and persisted) gets
            // its markers rewritten to the new sequence; the per-row
            // citation cards line up 1:1.
            CitationRenumber renumber = renumberCitations(accumulatedText, prep.retrieved());

            // Approximate token counts — we don't get them from the streaming
            // ChatResponse uniformly; the assistant counter is the byte/token
            // count of the accumulated text via JTokkit.
            int tokensIn = tokenCounter.count(prep.lastUserMessageContent(request)).value();
            int tokensOut = tokenCounter.count(renumber.text).value();
            int retrievalK = prep.retrieved().size();

            Message assistant = Message.newAssistantTurn(
                    prep.session().id(), request.caller(), renumber.text,
                    tokensIn, tokensOut, retrievalK, clock.instant());
            Message persisted = messageRepository.save(assistant);

            List<MessageCitation> toPersist = new java.util.ArrayList<>();
            List<CitationDto> wireCitations = new java.util.ArrayList<>();
            for (CitedChunk c : renumber.cited) {
                toPersist.add(new MessageCitation(
                        persisted.id(), c.newN, c.chunk.documentId(), c.chunk.chunkIndex()));
                wireCitations.add(new CitationDto(
                        c.newN,
                        c.chunk.documentId().value().toString(),
                        c.chunk.chunkIndex(),
                        c.chunk.title(),
                        shortExcerpt(c.chunk.text()),
                        c.chunk.visibility().wireValue()));
            }
            if (!toPersist.isEmpty()) {
                messageRepository.saveCitations(toPersist);
            }

            log.info("stream_end sessionId=" + prep.session().id()
                    + " userId=" + request.caller()
                    + " userSub=" + maskSub(request.userSub())
                    + " messageId=" + persisted.id()
                    + " tokensIn=" + tokensIn
                    + " tokensOut=" + tokensOut
                    + " cited=" + toPersist.size());

            return (ChatStreamEvent) new ChatStreamEvent.Done(
                    persisted.id().value().toString(), tokensIn, tokensOut, wireCitations);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Walk the accumulated text in order, find each {@code [N]} marker
     * whose {@code N} corresponds to a retrieved chunk, assign new
     * dense sequence numbers in first-encounter order, and rewrite the
     * text so the rendered cards read [1][2][3] regardless of which
     * original retrieval positions the LLM picked. Markers that don't
     * match any retrieved chunk (hallucinations) are left untouched so
     * the trail of bad LLM output is still visible.
     */
    private static CitationRenumber renumberCitations(String text, List<RetrievedChunk> retrieved) {
        if (text == null || text.isEmpty()) {
            return new CitationRenumber("", List.of());
        }
        java.util.Map<Integer, RetrievedChunk> byPosition = new java.util.HashMap<>();
        for (RetrievedChunk c : retrieved) {
            byPosition.put(c.position(), c);
        }
        java.util.LinkedHashMap<Integer, Integer> remap = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(text);
        while (m.find()) {
            int orig = Integer.parseInt(m.group(1));
            if (byPosition.containsKey(orig) && !remap.containsKey(orig)) {
                remap.put(orig, remap.size() + 1);
            }
        }
        String rewritten = m.reset().replaceAll(match -> {
            int orig = Integer.parseInt(match.group(1));
            Integer mapped = remap.get(orig);
            return mapped == null ? java.util.regex.Matcher.quoteReplacement(match.group(0)) : "[" + mapped + "]";
        });
        List<CitedChunk> cited = new java.util.ArrayList<>(remap.size());
        for (java.util.Map.Entry<Integer, Integer> e : remap.entrySet()) {
            cited.add(new CitedChunk(e.getValue(), byPosition.get(e.getKey())));
        }
        return new CitationRenumber(rewritten, cited);
    }

    /** Re-numbered text + the cited chunk list in dense [1..N] order. */
    private record CitationRenumber(String text, List<CitedChunk> cited) {}

    /** A retrieved chunk paired with its new dense citation number. */
    private record CitedChunk(int newN, RetrievedChunk chunk) {}

    /** Wire-shape excerpt — first ~160 chars, matches the old retrieval payload. */
    private static String shortExcerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    /**
     * Build one {@link ToolBinding} per registered descriptor. The binding's
     * handler is the boundary where (per ADR-17 §3.1) we:
     * <ol>
     *   <li>Check depth cap — if exceeded, emit terminal {@code tool_error}
     *       with {@code MAX_DEPTH} and throw so Spring AI aborts the
     *       round-trip.</li>
     *   <li>Generate a correlation id (server-side ULID-prefixed when Spring
     *       AI does not surface its own — M7 P0 generates per call).</li>
     *   <li>Emit {@code tool_call} to the sink BEFORE calling the
     *       dispatcher.</li>
     *   <li>Invoke {@link ToolDispatcherPort}.</li>
     *   <li>Emit {@code tool_result} (success) or {@code tool_error}
     *       (failure) to the sink, AFTER the dispatcher returns but BEFORE
     *       returning the JSON to Spring AI.</li>
     *   <li>For terminal failure codes ({@code CIRCUIT_OPEN} / {@code MAX_DEPTH})
     *       throw so Spring AI does not continue the round-trip; the
     *       use-case observes the upstream error and the sink already
     *       carries the terminal event.</li>
     * </ol>
     */
    private List<ToolBinding> buildBindings(
            List<ToolDescriptor> descriptors,
            Sinks.Many<ChatStreamEvent> sink,
            AtomicInteger depth,
            UserContext userCtx) {
        List<ToolBinding> bindings = new ArrayList<>(descriptors.size());
        for (ToolDescriptor d : descriptors) {
            ToolDescriptor desc = d;
            bindings.add(new ToolBinding(desc, args -> handleToolInvocation(desc, args, sink, depth, userCtx)));
        }
        return bindings;
    }

    private JsonNode handleToolInvocation(
            ToolDescriptor desc,
            JsonNode args,
            Sinks.Many<ChatStreamEvent> sink,
            AtomicInteger depth,
            UserContext userCtx) {
        String id = "call_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        int newDepth = depth.incrementAndGet();
        int maxDepth = properties.toolMaxDepth();
        if (newDepth > maxDepth) {
            log.warn("tool_max_depth_exceeded tool=" + desc.name() + " depth=" + newDepth + " cap=" + maxDepth);
            sink.tryEmitNext(new ChatStreamEvent.ToolError(
                    id, desc.name(), ToolErrorCode.MAX_DEPTH.name(),
                    "Tool-call depth cap of " + maxDepth + " exceeded"));
            sink.tryEmitComplete();
            throw new MaxDepthExceededException(desc.name(), maxDepth);
        }
        // tool_call BEFORE dispatching (ADR-17 §3.1 step 3a).
        sink.tryEmitNext(new ChatStreamEvent.ToolCall(id, desc.name(), args));

        ToolInvocationResult result = toolDispatcherPort.invoke(id, desc.name(), args, userCtx);
        if (result instanceof ToolInvocationResult.Success s) {
            sink.tryEmitNext(new ChatStreamEvent.ToolResult(s.id(), s.name(), s.body()));
            // Truncate the body to the configured cap if needed for the
            // LLM-bound feedback. The dispatcher already truncates at its
            // own boundary (ADR-17 §4) but we re-cap here defensively
            // before sending back to Spring AI.
            return truncateForLlm(s.body());
        }
        ToolInvocationResult.Failure f = (ToolInvocationResult.Failure) result;
        sink.tryEmitNext(new ChatStreamEvent.ToolError(f.id(), f.name(), f.code().name(), f.message()));
        if (f.code() == ToolErrorCode.CIRCUIT_OPEN) {
            // Terminal — abort the round-trip so the LLM does not get a chance
            // to retry (ADR-17 §3.1 + Story 6 operator cost-protection).
            sink.tryEmitComplete();
            throw new ToolCallTerminalException(f.code(), f.message());
        }
        // Non-terminal — feed the error back to the LLM as a synthetic tool
        // result so the LLM can either apologize, retry with corrected args,
        // or fall back to a natural-language response (ADR-17 §2 table).
        ObjectNode err = objectMapper.createObjectNode();
        err.put("error", true);
        err.put("code", f.code().name());
        err.put("message", f.message() == null ? "" : f.message());
        return err;
    }

    /**
     * Best-effort byte-level truncation of the JSON to feed back to the LLM
     * within the configured cap (ADR-17 §4). The dispatcher already truncates;
     * this is a belt-and-suspenders guard for any future direct-call path.
     */
    private JsonNode truncateForLlm(JsonNode body) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(body);
            int cap = properties.toolMaxResultBytes();
            if (serialized.length <= cap) {
                return body;
            }
            int excerptCap = Math.max(0, cap - 64);
            byte[] excerptBytes = new byte[excerptCap];
            System.arraycopy(serialized, 0, excerptBytes, 0, excerptCap);
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("truncated", true);
            envelope.put("originalBytes", serialized.length);
            envelope.put("excerpt", new String(excerptBytes, StandardCharsets.UTF_8));
            return envelope;
        } catch (Exception e) {
            return body;
        }
    }

    /** Thrown when the per-turn depth cap is exceeded — aborts the round-trip. */
    static final class MaxDepthExceededException extends RuntimeException {
        MaxDepthExceededException(String tool, int cap) {
            super("Tool-call depth cap " + cap + " exceeded on tool " + tool);
        }
    }

    /** Thrown when a tool error is terminal (CIRCUIT_OPEN / MAX_DEPTH). */
    static final class ToolCallTerminalException extends RuntimeException {
        final ToolErrorCode code;
        ToolCallTerminalException(ToolErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static boolean isCircuitOpen(Throwable err) {
        Throwable t = err;
        while (t != null) {
            String name = t.getClass().getName();
            if (name.contains("CallNotPermittedException")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String maskSub(String sub) {
        if (sub == null || sub.length() <= 4) {
            return "***";
        }
        return sub.substring(0, 4) + "***";
    }

    /** Snapshot of pre-stream side-effects bound to one turn. */
    private record Preparation(
            ChatSession session,
            List<RetrievedChunk> retrieved,
            List<Message> truncatedHistory,
            Message savedUser,
            boolean firstTurn) {

        String lastUserMessageContent(ChatTurnRequest request) {
            return request.message();
        }
    }
}
