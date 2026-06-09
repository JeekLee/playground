package com.playground.chat.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.chat.application.dto.ChatTurnRequest;
import com.playground.chat.application.port.ChatGenerationPort;
import com.playground.chat.application.port.ConcurrentStreamLockPort;
import com.playground.chat.application.port.TokenBucketPort;
import com.playground.chat.application.port.ToolRegistry;
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.repository.SessionRepository;
import com.playground.chat.application.tool.ToolBinding;
import com.playground.chat.application.tool.ToolDispatcherPort;
import com.playground.chat.application.tool.ToolLoop;
import com.playground.chat.application.tool.UserContext;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.ChatSession;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.UserModelRef;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.service.PromptTemplate;
import com.playground.chat.domain.tool.ToolDescriptor;
import com.playground.shared.chat.ChatStreamEvent;
import com.playground.shared.error.ExceptionCreator;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ChatTurnService {

    private static final Log log = LogFactory.getLog(ChatTurnService.class);

    private final SessionRepository sessionRepository;
    private final TokenBucketPort tokenBucketPort;
    private final ConcurrentStreamLockPort lockPort;
    private final ChatGenerationPort chatGenerationPort;
    private final PromptTemplate promptTemplate;
    private final AutoTitleService autoTitleService;
    private final ActiveTurnRegistry activeTurnRegistry;
    private final ToolDispatcherPort toolDispatcherPort;
    private final TurnContextAssembler turnContextAssembler;
    private final TurnRecorder turnRecorder;
    private final ObjectMapper objectMapper;
    private final ChatProperties properties;
    private final Clock clock;
    /**
     * Resolves the {@code refine_massing} {@code baseAttachmentId} the LLM picked
     * from the {@code [YOUR MODELS]} manifest to a storage key before dispatch
     * (M9). Forwarded into the per-turn {@link ToolLoop}.
     */
    private final com.playground.chat.application.repository.AttachmentRepository attachmentRepository;

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

    /**
     * Source of the tools offered to the LLM each turn. Declared last so the
     * Lombok-generated constructor's parameter order puts it last (matching the
     * tests' constructor-arg position). Production binds {@code StaticToolRegistry}.
     */
    private final ToolRegistry toolRegistry;

    public Flux<ChatStreamEvent> stream(ChatTurnRequest request) {
        Objects.requireNonNull(request, "request");
        // Message non-blank/size validation is a ChatTurnRequest invariant
        // (enforced at construction in the controller). The remaining pre-stream
        // guards (rate-limit, session ownership) throw HTTP errors below, before
        // any SSE handshake commits (ADR-14 §C "Pre-stream").
        return Mono.fromCallable(() -> guardAndPrepare(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> streamWithLock(request, ctx));
    }


    /** Phase 1: pre-stream side-effects that may throw HTTP errors. */
    private TurnContext guardAndPrepare(ChatTurnRequest request) {
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

        return turnContextAssembler.assemble(request, session);
    }

    /** Phase 2: lock + stream. The lock is released in doFinally. */
    private Flux<ChatStreamEvent> streamWithLock(ChatTurnRequest request, TurnContext ctx) {
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

        // Identity carrier forwarded to tool dispatch (ADR-17 §9). Created up
        // front so the tool registry can use it to resolve the per-turn catalog.
        UserContext userCtx = new UserContext(request.caller(), request.userSub());

        // ADR-17 §1 — branch on whether any tools are registered. The document
        // manifest is only useful for resolving a tool argument (briefDocId), so
        // the [YOUR DOCUMENTS] section is injected ONLY when tools are present.
        // Empty catalog → M4-invariant prompt shape (PRD Story 10), byte-identical.
        List<ToolDescriptor> descriptors = toolRegistry.descriptorsFor(userCtx);
        List<UserDocumentRef> promptDocuments = descriptors.isEmpty() ? List.of() : ctx.documents();
        List<UserModelRef> promptModels = descriptors.isEmpty() ? List.of() : ctx.models();

        String prompt = promptTemplate.assemble(
                ctx.truncatedHistory(), request.message(), promptDocuments, promptModels);

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

        List<ToolBinding> bindings = descriptors.isEmpty()
                ? List.of()
                : new ToolLoop(toolDispatcherPort, attachmentRepository, objectMapper, properties,
                        clock, toolEventSink, depth, inFlightTools, stagedAttachments, acc,
                        assistantMessageId, userCtx, ctx.session().id()).bindings(descriptors);

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
                    log.warn("stream_error sessionId=" + ctx.session().id()
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
            return turnRecorder.record(
                    ctx, acc.retrieved(), accumulated.toString(), request,
                    assistantMessageId, stagedAttachments);
        });

        // Source pipeline — must run to completion on the server even when the
        // SSE client disconnects mid-stream, so the assistant message + citations
        // land in `chat.messages` / `chat.message_citations`. Lock release and
        // auto-title fire from the source's terminal signal (not from the
        // client-facing flux).
        Flux<ChatStreamEvent> source = Flux.concat(guarded, done)
                .doFinally(signal -> {
                    activeTurnRegistry.unregister(ctx.session().id());
                    try {
                        handle.release();
                    } catch (RuntimeException e) {
                        log.warn("lock release failed sessionId=" + ctx.session().id()
                                + " reason=" + e.toString());
                    }
                    if (ctx.firstTurn() && signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
                        autoTitleService.generate(ctx.session().id(), request.message())
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
        activeTurnRegistry.register(ctx.session().id(), request.caller(), shared);

        shared.subscribe(
                event -> { /* drain — side effects accumulate + persist via the source */ },
                err -> log.warn("chat stream upstream error sessionId=" + ctx.session().id()
                        + " userId=" + request.caller() + " reason=" + err, err),
                () -> { /* upstream completed; doFinally already ran */ });

        return shared
                .doOnCancel(() -> log.info("stream_abort_client sessionId=" + ctx.session().id()
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
}
