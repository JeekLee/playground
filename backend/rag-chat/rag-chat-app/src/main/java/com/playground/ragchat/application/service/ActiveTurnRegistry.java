package com.playground.ragchat.application.service;

import com.playground.ragchat.application.dto.ChatStreamEvent;
import com.playground.ragchat.domain.model.id.SessionId;
import com.playground.ragchat.domain.model.id.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * In-memory registry of in-flight chat-turn streams, keyed by
 * {@link SessionId}. Lets {@code GET /api/rag/chat/sessions/{id}/stream}
 * (the resume / mid-stream re-join surface) attach to the same hot
 * replay-shared {@code Flux} that the originating
 * {@code POST /api/rag/chat} returned to the first client — so a user
 * who navigated away mid-stream and comes back sees the buffered
 * {@code retrieval} event, every token emitted so far, and the live
 * tail (or a final {@code done} if the turn finished while they were
 * away).
 *
 * <h2>Scope + lifecycle</h2>
 * The registry is JVM-local. ADR-07 §"Hosting model" pins playground
 * to a single-instance deploy through M5, so the originating
 * instance always serves the resume request. Multi-instance scale-out
 * would add a Redis-backed routing index ({@code sessionId →
 * instanceId}) on top; the {@code Flux} itself stays in-process
 * because it owns the live Spring AI subscription.
 *
 * <p>{@link ChatTurnService} calls {@link #register} immediately after
 * building the shared flux, and {@link #unregister} from the source's
 * {@code doFinally} so the entry disappears as soon as the server-side
 * pipeline terminates (success or error). The replay buffer kept by
 * {@code Flux.replay(N).autoConnect(1)} is GC'd once the registry no
 * longer references it.
 */
@Component
public class ActiveTurnRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveTurnRegistry.class);

    private final ConcurrentMap<SessionId, Entry> turns = new ConcurrentHashMap<>();

    public record Entry(UserId owner, Flux<ChatStreamEvent> stream, Instant startedAt) {}

    public void register(SessionId sessionId, UserId owner, Flux<ChatStreamEvent> stream) {
        turns.put(sessionId, new Entry(owner, stream, Instant.now()));
        log.debug("active turn registered sessionId={} owner={}", sessionId, owner);
    }

    public Optional<Entry> find(SessionId sessionId) {
        return Optional.ofNullable(turns.get(sessionId));
    }

    public void unregister(SessionId sessionId) {
        if (turns.remove(sessionId) != null) {
            log.debug("active turn unregistered sessionId={}", sessionId);
        }
    }
}
