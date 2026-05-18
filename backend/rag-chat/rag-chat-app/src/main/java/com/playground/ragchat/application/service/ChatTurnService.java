package com.playground.ragchat.application.service;

import com.playground.ragchat.application.dto.ChatStreamEvent;
import com.playground.ragchat.application.dto.ChatTurnRequest;
import com.playground.ragchat.application.port.ChatGenerationPort;
import com.playground.ragchat.application.port.ChunkRetrievalPort;
import com.playground.ragchat.application.port.ConcurrentStreamLockPort;
import com.playground.ragchat.application.port.EmbeddingPort;
import com.playground.ragchat.application.port.TokenBucketPort;
import com.playground.ragchat.application.properties.RagChatProperties;
import com.playground.ragchat.application.repository.MessageRepository;
import com.playground.ragchat.application.repository.SessionRepository;
import com.playground.ragchat.domain.exception.RagChatErrorCode;
import com.playground.ragchat.domain.model.ChatSession;
import com.playground.ragchat.domain.model.Message;
import com.playground.ragchat.domain.model.MessageCitation;
import com.playground.ragchat.domain.model.RetrievedChunk;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.ragchat.domain.model.vo.TokenCount;
import com.playground.ragchat.domain.service.CitationExtractor;
import com.playground.ragchat.domain.service.HistoryTruncator;
import com.playground.ragchat.domain.service.PromptTemplate;
import com.playground.ragchat.domain.service.TokenCounter;
import com.playground.shared.error.ExceptionCreator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
    private final CitationExtractor citationExtractor;
    private final PromptTemplate promptTemplate;
    private final AutoTitleService autoTitleService;
    private final RagChatProperties properties;
    private final Clock clock;

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
            CitationExtractor citationExtractor,
            PromptTemplate promptTemplate,
            AutoTitleService autoTitleService,
            RagChatProperties properties,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.tokenBucketPort = tokenBucketPort;
        this.lockPort = lockPort;
        this.embeddingPort = embeddingPort;
        this.chunkRetrievalPort = chunkRetrievalPort;
        this.chatGenerationPort = chatGenerationPort;
        this.historyTruncator = historyTruncator;
        this.tokenCounter = tokenCounter;
        this.citationExtractor = citationExtractor;
        this.promptTemplate = promptTemplate;
        this.autoTitleService = autoTitleService;
        this.properties = properties;
        this.clock = clock;
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

        Flux<ChatStreamEvent> retrievalEvent = Flux.just(
                (ChatStreamEvent) new ChatStreamEvent.Retrieval(prep.retrieved()));

        Flux<ChatStreamEvent> tokens = chatGenerationPort
                .stream(prompt, properties.maxCompletionTokens())
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
                });

        Mono<ChatStreamEvent> done = Mono.defer(() -> {
            if (errored.get()) {
                return Mono.empty();
            }
            return persistAssistantAndDone(prep, accumulated.toString(), request);
        });

        return Flux.concat(retrievalEvent, tokens, done)
                .doOnCancel(() -> log.info("stream_abort sessionId=" + prep.session().id()
                        + " userId=" + request.caller()
                        + " bytesEmitted=" + accumulated.length()))
                .doFinally(signal -> {
                    try {
                        handle.release();
                    } catch (RuntimeException e) {
                        log.warn("lock release failed sessionId=" + prep.session().id()
                                + " reason=" + e.toString());
                    }
                    // Fire-and-forget auto-title if first user turn AND we got
                    // here without an upstream cancel (the partial assistant
                    // would still be persistable from a value side, but auto-
                    // title runs on the user message regardless).
                    if (prep.firstTurn() && signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
                        autoTitleService.generate(prep.session().id(), request.message())
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();
                    }
                });
    }

    private Mono<ChatStreamEvent> persistAssistantAndDone(
            Preparation prep, String accumulatedText, ChatTurnRequest request) {
        return Mono.fromCallable(() -> {
            if (accumulatedText == null || accumulatedText.isEmpty()) {
                // The LLM produced no tokens — emit done with empty content so
                // the client can close the stream cleanly. We still persist an
                // empty assistant message so the message count remains paired.
            }

            // Extract [N] markers and filter the retrieved list to just those cited.
            Set<Integer> cited = citationExtractor.extractMarkers(accumulatedText);

            // Approximate token counts — we don't get them from the streaming
            // ChatResponse uniformly; the assistant counter is the byte/token
            // count of the accumulated text via JTokkit.
            int tokensIn = tokenCounter.count(prep.lastUserMessageContent(request)).value();
            int tokensOut = tokenCounter.count(accumulatedText).value();
            int retrievalK = prep.retrieved().size();

            Message assistant = Message.newAssistantTurn(
                    prep.session().id(), request.caller(), accumulatedText,
                    tokensIn, tokensOut, retrievalK, clock.instant());
            Message persisted = messageRepository.save(assistant);

            // Persist only the cited subset per ADR-14 §10.
            List<MessageCitation> toPersist = new java.util.ArrayList<>();
            Set<Integer> seenPositions = new HashSet<>();
            for (RetrievedChunk c : prep.retrieved()) {
                if (cited.contains(c.position()) && seenPositions.add(c.position())) {
                    toPersist.add(new MessageCitation(
                            persisted.id(), c.position(), c.documentId(), c.chunkIndex()));
                }
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

            return (ChatStreamEvent) new ChatStreamEvent.Done(persisted.id(), tokensIn, tokensOut);
        }).subscribeOn(Schedulers.boundedElastic());
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
