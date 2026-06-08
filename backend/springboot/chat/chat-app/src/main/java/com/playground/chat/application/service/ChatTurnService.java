package com.playground.chat.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.application.dto.ChatTurnRequest;
import com.playground.chat.application.dto.CitationDto;
import com.playground.chat.application.port.ChatGenerationPort;
import com.playground.chat.application.port.ConcurrentStreamLockPort;
import com.playground.chat.application.port.TokenBucketPort;
import com.playground.chat.application.port.UserDocumentManifestPort;
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.application.repository.MessageRepository;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.application.tool.ToolArtifact;
import com.playground.chat.application.tool.ToolBinding;
import com.playground.chat.application.tool.ToolDispatcherPort;
import com.playground.chat.application.tool.ToolInvocationResult;
import com.playground.chat.application.tool.UserContext;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.Message;
import com.playground.chat.domain.model.MessageCitation;
import com.playground.chat.domain.model.RetrievedChunk;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.id.AttachmentId;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.SessionId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.chat.domain.model.vo.TokenCount;
import com.playground.chat.domain.service.HistoryTruncator;
import com.playground.chat.domain.service.PromptTemplate;
import com.playground.chat.domain.service.TokenCounter;
import com.playground.chat.domain.tool.ToolCatalog;
import com.playground.chat.domain.tool.ToolDescriptor;
import com.playground.chat.domain.tool.ToolErrorCode;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.shared.error.ExceptionCreator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
 * history load + truncate → persist user message → assemble prompt →
 * stream tokens (search_documents tool searches on demand, accumulating
 * turn-global citations) → parse markers → persist assistant message +
 * cited subset → emit done SSE event → release lock.
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
    private final ChatGenerationPort chatGenerationPort;
    private final HistoryTruncator historyTruncator;
    private final TokenCounter tokenCounter;
    private final PromptTemplate promptTemplate;
    private final AutoTitleService autoTitleService;
    private final ActiveTurnRegistry activeTurnRegistry;
    private final ToolDispatcherPort toolDispatcherPort;
    private final UserDocumentManifestPort userDocumentManifestPort;
    private final AttachmentRepository attachmentRepository;
    private final ObjectMapper objectMapper;
    private final ChatProperties properties;
    private final Clock clock;

    /** Download URL prefix for message attachments (ADR-20 §D4). FE-facing, gateway-routed. */
    static final String ATTACHMENT_DOWNLOAD_PREFIX = "/api/chat/attachments/";

    /**
     * Cap on the document manifest injected into the prompt's [YOUR DOCUMENTS]
     * section. Bounds prompt tokens; personal-scale corpora sit well under it.
     */
    static final int DOCUMENT_MANIFEST_LIMIT = 30;

    /**
     * Inactivity timeout on the streaming phase. Governs genuine LLM token
     * silence; suspended while a tool is in-flight (the dispatcher's idle 60s /
     * total 600s budget is the liveness authority then).
     */
    private static final Duration TOKEN_INACTIVITY_TIMEOUT = Duration.ofSeconds(150);
    /**
     * Keepalive tick cadence while a tool runs. Strictly less than
     * {@link #TOKEN_INACTIVITY_TIMEOUT} so a tick always lands inside the
     * inactivity window, holding it open (effectively suspending the guard).
     */
    private static final Duration TOOL_KEEPALIVE_INTERVAL = Duration.ofSeconds(30);

    /** Source of registered tool descriptors. Defaults to {@code ToolCatalog::descriptors}. */
    private final java.util.function.Supplier<List<ToolDescriptor>> toolDescriptorSupplier;

    @org.springframework.beans.factory.annotation.Autowired
    public ChatTurnService(
            SessionRepository sessionRepository,
            MessageRepository messageRepository,
            TokenBucketPort tokenBucketPort,
            ConcurrentStreamLockPort lockPort,
            ChatGenerationPort chatGenerationPort,
            HistoryTruncator historyTruncator,
            TokenCounter tokenCounter,
            PromptTemplate promptTemplate,
            AutoTitleService autoTitleService,
            ActiveTurnRegistry activeTurnRegistry,
            ToolDispatcherPort toolDispatcherPort,
            UserDocumentManifestPort userDocumentManifestPort,
            AttachmentRepository attachmentRepository,
            ObjectMapper objectMapper,
            ChatProperties properties,
            Clock clock) {
        this(sessionRepository, messageRepository, tokenBucketPort, lockPort,
                chatGenerationPort, historyTruncator, tokenCounter,
                promptTemplate, autoTitleService, activeTurnRegistry, toolDispatcherPort,
                userDocumentManifestPort, attachmentRepository,
                objectMapper, properties, clock, ToolCatalog::descriptors);
    }

    /**
     * Test-friendly constructor — pluggable tool-descriptor supplier.
     * Public so end-to-end tests in {@code chat-infra} (different
     * package) can wire a synthetic catalog without reaching for
     * reflection. Production wiring uses the primary constructor which
     * binds the supplier to {@code ToolCatalog::descriptors}.
     */
    public ChatTurnService(
            SessionRepository sessionRepository,
            MessageRepository messageRepository,
            TokenBucketPort tokenBucketPort,
            ConcurrentStreamLockPort lockPort,
            ChatGenerationPort chatGenerationPort,
            HistoryTruncator historyTruncator,
            TokenCounter tokenCounter,
            PromptTemplate promptTemplate,
            AutoTitleService autoTitleService,
            ActiveTurnRegistry activeTurnRegistry,
            ToolDispatcherPort toolDispatcherPort,
            UserDocumentManifestPort userDocumentManifestPort,
            AttachmentRepository attachmentRepository,
            ObjectMapper objectMapper,
            ChatProperties properties,
            Clock clock,
            java.util.function.Supplier<List<ToolDescriptor>> toolDescriptorSupplier) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.tokenBucketPort = tokenBucketPort;
        this.lockPort = lockPort;
        this.chatGenerationPort = chatGenerationPort;
        this.historyTruncator = historyTruncator;
        this.tokenCounter = tokenCounter;
        this.promptTemplate = promptTemplate;
        this.autoTitleService = autoTitleService;
        this.activeTurnRegistry = activeTurnRegistry;
        this.toolDispatcherPort = toolDispatcherPort;
        this.userDocumentManifestPort = userDocumentManifestPort;
        this.attachmentRepository = attachmentRepository;
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
            throw ExceptionCreator.of(ChatErrorCode.MESSAGE_BLANK).build();
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ChatProperties.MAX_USER_MESSAGE_BYTES) {
            throw ExceptionCreator.of(ChatErrorCode.MESSAGE_TOO_LARGE).build();
        }
    }

    /** Phase 1: pre-stream side-effects that may throw HTTP errors. */
    private Preparation guardAndPrepare(ChatTurnRequest request) {
        // 1. Rate limit.
        TokenBucketPort.Decision decision = tokenBucketPort.tryAcquire(request.caller());
        if (!decision.allowed()) {
            throw ExceptionCreator
                    .of(ChatErrorCode.RATE_LIMITED, decision.retryAfterSeconds())
                    .build();
        }

        // 2. Session validation — must exist and belong to caller.
        ChatSession session = sessionRepository.findOwned(request.sessionId(), request.caller())
                .orElseThrow(() -> ExceptionCreator.of(ChatErrorCode.SESSION_NOT_FOUND).build());

        // 3. History load + truncate.
        List<Message> rawHistory = messageRepository.findBySession(session.id());
        TokenCount currentMessageTokens = tokenCounter.count(request.message());
        List<Message> truncated = historyTruncator.truncate(
                rawHistory, properties.maxHistoryTokens(), currentMessageTokens);

        // 4. Persist the user message before opening the stream so a mid-stream
        // refresh shows the user turn even if the assistant never lands.
        // (agentic-search spec D2: the always-on embed+pgvector retrieval was
        // removed — the LLM searches via the search_documents tool only when
        // the question concerns uploaded-document content.)
        Message userMessage = Message.newUserTurn(
                session.id(), request.caller(), request.message(), clock.instant());
        Message savedUser = messageRepository.save(userMessage);

        // 5. Determine whether this is the first user turn for the auto-title trigger.
        int userMessageCount = messageRepository.countUserMessages(session.id());
        boolean firstTurn = userMessageCount == 1;

        // Caller's document manifest for the [YOUR DOCUMENTS] prompt section, so
        // the model can resolve an ordinal/title reference to a briefDocId.
        // Degrades gracefully — a failed lookup just omits the section.
        List<UserDocumentRef> documents;
        try {
            documents = userDocumentManifestPort.recentForUser(request.caller(), DOCUMENT_MANIFEST_LIMIT);
        } catch (RuntimeException e) {
            log.warn("document_manifest_lookup_failed userId=" + request.caller()
                    + " error=" + e.getMessage());
            documents = List.of();
        }

        log.info("turn_start sessionId=" + session.id()
                + " userId=" + request.caller()
                + " userSub=" + maskSub(request.userSub())
                + " historyTurns=" + truncated.size()
                + " docManifest=" + documents.size());

        return new Preparation(session, truncated, savedUser, firstTurn, documents);
    }

    /** Phase 2: lock + stream. The lock is released in doFinally. */
    private Flux<ChatStreamEvent> streamWithLock(ChatTurnRequest request, Preparation prep) {
        ConcurrentStreamLockPort.Handle handle = lockPort.acquire(request.caller());
        if (handle == null) {
            // ADR-14 §D — latest-wins should have freed the previous holder; a
            // null here means even the abort path failed. Surface as 503.
            throw ExceptionCreator.of(ChatErrorCode.GATEWAY_DOWN).build();
        }

        final StringBuilder accumulated = new StringBuilder(4096);
        final java.util.concurrent.atomic.AtomicBoolean errored =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        // ADR-20 §D3 (messageId↔attachment timing): the assistant messageId is
        // allocated UP-FRONT so a tool callback can store the artifact bytes to
        // MinIO under chat/{sessionId}/{messageId}/{attachmentId}-{filename} and
        // build the Attachment row mid-stream, before the assistant text is
        // finalized. The staged attachments are persisted in
        // persistAssistantAndDone alongside the assistant message, which reuses
        // this same id — so every attachment links to the assistant message of
        // the turn (the invariant).
        final MessageId assistantMessageId = MessageId.generate();
        final List<Attachment> stagedAttachments =
                java.util.Collections.synchronizedList(new ArrayList<>());

        // ADR-17 §1 — branch on whether any tools are registered. The document
        // manifest is only useful for resolving a tool argument (briefDocId), so
        // the [YOUR DOCUMENTS] section is injected ONLY when tools are present.
        // Empty catalog → M4-invariant prompt shape (PRD Story 10), byte-identical.
        List<ToolDescriptor> descriptors = toolDescriptorSupplier.get();
        List<UserDocumentRef> promptDocuments = descriptors.isEmpty() ? List.of() : prep.documents();

        String prompt = promptTemplate.assemble(
                prep.truncatedHistory(), request.message(), promptDocuments);

        // Turn-scoped citation accumulator (agentic-search spec D2). Each
        // search_documents tool result is renumbered into a turn-global
        // [N] space and accumulated here; at turn end acc.retrieved() feeds
        // the existing renumber/persist path. A turn with zero searches
        // yields an empty list → empty citations (the existing path handles).
        TurnCitationAccumulator acc = new TurnCitationAccumulator();

        // Sink the tool callbacks push events into. Multicast so the orchestrator
        // can interleave the events with the token flux as they arrive.
        Sinks.Many<ChatStreamEvent> toolEventSink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicInteger depth = new AtomicInteger(0);
        // Count of tools actively dispatching+handling. While > 0 the
        // token-inactivity guard is suspended (the dispatcher governs liveness).
        AtomicInteger inFlightTools = new AtomicInteger(0);
        UserContext userCtx = new UserContext(request.caller(), request.userSub());

        List<ToolBinding> bindings = descriptors.isEmpty()
                ? List.of()
                : buildBindings(descriptors, toolEventSink, depth, inFlightTools, userCtx,
                        prep.session().id(), assistantMessageId, stagedAttachments, acc);

        Flux<String> rawDeltas = bindings.isEmpty()
                ? chatGenerationPort.stream(prompt, properties.maxCompletionTokens())
                : chatGenerationPort.streamWithTools(
                        prompt, properties.maxCompletionTokens(), bindings);

        // The token flux signals upstream completion of the LLM (and all its
        // internal tool-calling round-trips done by Spring AI). When it
        // terminates, close the tool-event sink so the merge completes.
        // The inactivity timeout + error mapping live on the merged flux below
        // (withInactivityGuard + onErrorResume) — NOT here — so the timeout can
        // be suspended while a tool is in-flight.
        Flux<ChatStreamEvent> tokens = rawDeltas
                .doOnNext(accumulated::append)
                .map(delta -> (ChatStreamEvent) new ChatStreamEvent.Token(delta))
                .doFinally(sig -> toolEventSink.tryEmitComplete());

        // Merge the tool events (emitted from inside Spring AI's function-calling
        // callbacks) with the token deltas. The merge preserves the order each
        // upstream emits in: tool events arrive when the callback fires; token
        // events arrive as Spring AI streams the model's text.
        Flux<ChatStreamEvent> mergedTokensAndToolEvents = bindings.isEmpty()
                ? tokens
                : Flux.merge(toolEventSink.asFlux(), tokens);

        // Inactivity guard: 150s governs genuine LLM token silence, but is
        // SUSPENDED while a tool is in-flight (a keepalive tick injected every
        // 30s holds the window open) — then the dispatcher (idle 60s / total
        // 600s) is the liveness authority. The onErrorResume catches BOTH the
        // inactivity TimeoutException and any upstream LLM/circuit error and
        // funnels them into the same Error-event handling.
        Flux<ChatStreamEvent> guarded =
                withInactivityGuard(mergedTokensAndToolEvents, inFlightTools)
                .onErrorResume(err -> {
                    log.warn("stream_error sessionId=" + prep.session().id()
                            + " userId=" + request.caller()
                            + " reason=" + err.toString());
                    errored.set(true);
                    if (isCircuitOpen(err)) {
                        return Flux.just(ChatStreamEvent.Error.gatewayDown());
                    }
                    return Flux.just(ChatStreamEvent.Error.internal(err.getMessage()));
                });

        Mono<ChatStreamEvent> done = Mono.defer(() -> {
            if (errored.get()) {
                return Mono.empty();
            }
            return persistAssistantAndDone(
                    prep, acc.retrieved(), accumulated.toString(), request,
                    assistantMessageId, stagedAttachments);
        });

        // Source pipeline — must run to completion on the server even when the
        // SSE client disconnects mid-stream, so the assistant message + citations
        // land in `chat.messages` / `chat.message_citations`. Lock release and
        // auto-title fire from the source's terminal signal (not from the
        // client-facing flux).
        Flux<ChatStreamEvent> source = Flux.concat(guarded, done)
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

        // Register so `GET /api/chat/sessions/{id}/stream` can attach late
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

    /**
     * Inactivity guard for the streaming phase. Errors with TimeoutException when
     * no signal arrives within {@link #TOKEN_INACTIVITY_TIMEOUT} — but ONLY while
     * no tool is in-flight. While a tool runs ({@code inFlightTools > 0}) the
     * dispatcher (idle 60s / total 600s) is the liveness authority, so a keepalive
     * tick is injected every {@link #TOOL_KEEPALIVE_INTERVAL} to hold the window
     * open (effectively suspending the guard). The keepalive sentinel is filtered
     * out before the result. The keepalive stops when the source terminates (no
     * leaked interval): {@code ticks} is bounded by {@code takeUntilOther(sourceDone)}
     * and {@code sourceDone} fires in the source's {@code doFinally}, so once the
     * source completes the merge completes too.
     */
    static Flux<ChatStreamEvent> withInactivityGuard(
            Flux<ChatStreamEvent> source, AtomicInteger inFlightTools) {
        final Object keepAlive = new Object();
        Sinks.Empty<Void> sourceDone = Sinks.empty();
        Flux<Object> ticks = Flux.interval(TOOL_KEEPALIVE_INTERVAL)
                .filter(t -> inFlightTools.get() > 0)
                .map(t -> keepAlive)
                .takeUntilOther(sourceDone.asMono());
        return Flux.merge(
                    source.cast(Object.class).doFinally(sig -> sourceDone.tryEmitEmpty()),
                    ticks)
                .timeout(TOKEN_INACTIVITY_TIMEOUT)
                .filter(o -> o != keepAlive)
                .cast(ChatStreamEvent.class);
    }

    private Mono<ChatStreamEvent> persistAssistantAndDone(
            Preparation prep, List<RetrievedChunk> retrieved, String accumulatedText,
            ChatTurnRequest request,
            MessageId assistantMessageId, List<Attachment> stagedAttachments) {
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
            CitationRenumber renumber = renumberCitations(accumulatedText, retrieved);

            // Approximate token counts — we don't get them from the streaming
            // ChatResponse uniformly; the assistant counter is the byte/token
            // count of the accumulated text via JTokkit.
            int tokensIn = tokenCounter.count(prep.lastUserMessageContent(request)).value();
            int tokensOut = tokenCounter.count(renumber.text).value();
            int retrievalK = retrieved.size();

            // Reuse the UP-FRONT-allocated assistant messageId (ADR-20 §D3) so
            // any attachments staged mid-stream already point at this row.
            Message assistant = Message.newAssistantTurn(
                    assistantMessageId, prep.session().id(), request.caller(), renumber.text,
                    tokensIn, tokensOut, retrievalK, clock.instant());
            Message persisted = messageRepository.save(assistant);

            List<MessageCitation> toPersist = new java.util.ArrayList<>();
            List<CitationDto> wireCitations = new java.util.ArrayList<>();
            for (CitedChunk c : renumber.cited) {
                // Snapshot persistence (agentic-search spec D2): freeze
                // title/excerpt/visibility on the citation row so history reload
                // reads them back without joining the docs schema. The live
                // Done-event DTO and the persisted snapshot carry IDENTICAL
                // values (same RetrievedChunk source, no re-truncation) — the
                // excerpt is already ≤600 chars from docs-api search.
                String visibility = c.chunk.visibility().wireValue();
                toPersist.add(new MessageCitation(
                        persisted.id(), c.newN, c.chunk.documentId(), c.chunk.chunkIndex(),
                        c.chunk.title(), c.chunk.text(), visibility));
                wireCitations.add(new CitationDto(
                        c.newN,
                        c.chunk.documentId().value().toString(),
                        c.chunk.chunkIndex(),
                        c.chunk.title(),
                        c.chunk.text(),
                        visibility));
            }
            if (!toPersist.isEmpty()) {
                messageRepository.saveCitations(toPersist);
            }

            // ADR-20 §D3 — persist the staged attachments (already in MinIO,
            // already linked to assistantMessageId). The snapshot copy avoids a
            // concurrent-modification read while the (now-terminated) tool
            // callbacks could in theory still be appending.
            List<Attachment> attachments;
            synchronized (stagedAttachments) {
                attachments = new ArrayList<>(stagedAttachments);
            }
            if (!attachments.isEmpty()) {
                attachmentRepository.saveAll(attachments);
            }

            log.info("stream_end sessionId=" + prep.session().id()
                    + " userId=" + request.caller()
                    + " userSub=" + maskSub(request.userSub())
                    + " messageId=" + persisted.id()
                    + " tokensIn=" + tokensIn
                    + " tokensOut=" + tokensOut
                    + " cited=" + toPersist.size()
                    + " attachments=" + attachments.size());

            // The tool_result SSE event already carries fileUrl for the streaming
            // card; history loads via loadMessages carry the attachment DTO.
            // Never put attachment data inside the citations field — the frontend
            // calls citations.map() and crashes when it receives an object.
            ChatStreamEvent.Done done = new ChatStreamEvent.Done(
                    persisted.id().value().toString(), tokensIn, tokensOut, wireCitations);
            return (ChatStreamEvent) done;
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
            AtomicInteger inFlightTools,
            UserContext userCtx,
            SessionId sessionId,
            MessageId assistantMessageId,
            List<Attachment> stagedAttachments,
            TurnCitationAccumulator acc) {
        List<ToolBinding> bindings = new ArrayList<>(descriptors.size());
        for (ToolDescriptor d : descriptors) {
            ToolDescriptor desc = d;
            bindings.add(new ToolBinding(desc, args -> handleToolInvocation(
                    desc, args, sink, depth, inFlightTools, userCtx, sessionId, assistantMessageId,
                    stagedAttachments, acc)));
        }
        return bindings;
    }

    private JsonNode handleToolInvocation(
            ToolDescriptor desc,
            JsonNode args,
            Sinks.Many<ChatStreamEvent> sink,
            AtomicInteger depth,
            AtomicInteger inFlightTools,
            UserContext userCtx,
            SessionId sessionId,
            MessageId assistantMessageId,
            List<Attachment> stagedAttachments,
            TurnCitationAccumulator acc) {
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
        // tool_call BEFORE dispatching (ADR-17 §3.1 step 3a). tool_call /
        // tool_result / tool_error all emit from THIS (dispatch) thread — a
        // single serialization point — so tryEmitNext is safe here.
        sink.tryEmitNext(new ChatStreamEvent.ToolCall(id, desc.name(), desc.displayName(), args));

        // Progress relays from the dispatcher's NDJSON `progress` lines, which
        // the WebClient decodes on a netty event-loop thread — a DIFFERENT
        // thread than the dispatch thread that emits tool_call/result/error
        // into this sink. With multicast().onBackpressureBuffer() the
        // tryEmitNext is non-serialized and a concurrent emission silently
        // drops as FAIL_NON_SERIALIZED, so we use emitNext + busyLooping to
        // retry briefly instead of dropping a progress event (Task 3 review
        // Critical). The spin lands ON the I/O event-loop thread, so keep it
        // short (50ms): contention is near-zero anyway — the dispatch thread
        // is parked in blockLast while progress flows — and progress is
        // droppable + resume-recoverable. emitNext may throw on exhaustion,
        // but the dispatcher's emitProgress isolates RuntimeException from
        // the listener, so the stream stays safe.
        // The in-flight window spans the dispatch AND its result handling: while
        // it is open the outer token-inactivity guard is suspended (the
        // dispatcher's idle 60s / total 600s budget is the liveness authority).
        // The finally decrements on EVERY exit — Success return, CIRCUIT_OPEN /
        // terminal throw, non-terminal synthetic-error return, or any exception
        // from invoke itself — exactly once.
        inFlightTools.incrementAndGet();
        try {
        ToolInvocationResult result = toolDispatcherPort.invoke(
                id, desc.name(), args, userCtx,
                p -> sink.emitNext(
                        new ChatStreamEvent.ToolProgress(p.id(), p.name(), p.stage(), p.label(),
                                p.stageIndex(), p.stageCount(), p.attempt()),
                        Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(50))));
        if (result instanceof ToolInvocationResult.Success s) {
            // agentic-search spec D2 — for search_documents, renumber the
            // per-call [N] positions into the turn-global space and accumulate
            // the chunks. The renumbered body is what BOTH the SSE tool_result
            // and the LLM-bound feedback see, so the [N] markers the model
            // cites line up with the citation cards. Other tools bypass absorb.
            JsonNode llmVisibleBody = s.body();
            if ("search_documents".equals(desc.name()) && s.body() != null) {
                llmVisibleBody = acc.absorb(userCtx.userId(), s.body());
            }
            // ADR-20 §D3 — if the tool emitted an artifact, store it to MinIO
            // and stage an Attachment linked to the (pre-allocated) assistant
            // message. Only the LLM-visible `body` (result) reaches the LLM +
            // the tool_result SSE result field; the bytes never enter context.
            Attachment attachment = null;
            if (s.artifact() != null) {
                String briefTitle = llmVisibleBody != null && llmVisibleBody.has("briefTitle")
                        ? llmVisibleBody.get("briefTitle").asText(null)
                        : null;
                attachment = storeArtifact(
                        s.artifact(), desc.name(), briefTitle,
                        sessionId, assistantMessageId, stagedAttachments);
            }
            // tool_result SSE result carries the LLM result PLUS (per ADR-20 §D4)
            // an `attachment` object + top-level `fileUrl` for the FE. The
            // LLM-bound feedback below is the bare `body` only.
            sink.tryEmitNext(new ChatStreamEvent.ToolResult(
                    s.id(), s.name(), enrichResultForSse(llmVisibleBody, attachment)));
            // Truncate the body to the configured cap if needed for the
            // LLM-bound feedback. The dispatcher already truncates at its
            // own boundary (ADR-17 §4) but we re-cap here defensively
            // before sending back to Spring AI.
            return truncateForLlm(llmVisibleBody);
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
        } finally {
            inFlightTools.decrementAndGet();
        }
    }

    /**
     * Stage an {@link Attachment} from the tool artifact per ADR-20 §D3 revised.
     *
     * <p>agent-tools owns the MinIO write path and already stored the file before
     * returning its response. This method just records the pointer — allocates an
     * {@link AttachmentId}, creates the domain object with the pre-existing
     * {@code storageKey}, and adds it to {@code stagedAttachments} for batch
     * persist in {@code persistAssistantAndDone}.
     */
    private Attachment storeArtifact(
            ToolArtifact artifact,
            String toolName,
            String briefTitle,
            SessionId sessionId,
            MessageId assistantMessageId,
            List<Attachment> stagedAttachments) {
        try {
            AttachmentId attachmentId = AttachmentId.generate();
            Attachment attachment = Attachment.toolArtifact(
                    attachmentId,
                    assistantMessageId,
                    artifact.filename(),
                    artifact.contentTypeOrDefault(),
                    artifact.sizeBytes(),
                    artifact.storageKey(),
                    toolName,
                    briefTitle,
                    clock.instant());
            stagedAttachments.add(attachment);
            log.info("tool_artifact_staged tool=" + toolName
                    + " attachmentId=" + attachmentId
                    + " messageId=" + assistantMessageId
                    + " sizeBytes=" + artifact.sizeBytes()
                    + " storageKey=" + artifact.storageKey());
            return attachment;
        } catch (RuntimeException e) {
            // Unexpected — staging should be infallible (no I/O). Log and degrade
            // gracefully so the turn still completes without the attachment.
            log.warn("tool_artifact_stage_failed tool=" + toolName
                    + " messageId=" + assistantMessageId + " reason=" + e.toString());
            return null;
        }
    }

    /**
     * Build the {@code tool_result} SSE result payload (ADR-20 §D4): the
     * LLM-visible result body, plus — when an artifact was captured — an
     * {@code attachment} object and a top-level {@code fileUrl} the FE's
     * MassingResultCard reads. When there's no artifact, the body is returned
     * unchanged (M7 wire shape, byte-identical for plain tools).
     */
    private Object enrichResultForSse(JsonNode body, Attachment attachment) {
        if (attachment == null) {
            return body;
        }
        ObjectNode enriched;
        if (body != null && body.isObject()) {
            enriched = ((ObjectNode) body).deepCopy();
        } else {
            // Non-object result (rare for a file-producing tool) — nest it under
            // `result` so the attachment fields have somewhere to live.
            enriched = objectMapper.createObjectNode();
            enriched.set("result", body == null ? objectMapper.nullNode() : body.deepCopy());
        }
        String downloadUrl = ATTACHMENT_DOWNLOAD_PREFIX + attachment.id();
        ObjectNode attachmentNode = objectMapper.createObjectNode();
        attachmentNode.put("id", attachment.id().toString());
        attachmentNode.put("filename", attachment.filename());
        attachmentNode.put("contentType", attachment.contentType());
        attachmentNode.put("sizeBytes", attachment.sizeBytes());
        attachmentNode.put("downloadUrl", downloadUrl);
        enriched.set("attachment", attachmentNode);
        enriched.put("fileUrl", downloadUrl);
        if (attachment.briefTitle() != null) {
            enriched.put("briefTitle", attachment.briefTitle());
        }
        return enriched;
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
            List<Message> truncatedHistory,
            Message savedUser,
            boolean firstTurn,
            List<UserDocumentRef> documents) {

        String lastUserMessageContent(ChatTurnRequest request) {
            return request.message();
        }
    }
}
