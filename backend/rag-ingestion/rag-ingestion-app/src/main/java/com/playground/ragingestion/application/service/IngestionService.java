package com.playground.ragingestion.application.service;

import com.playground.ragingestion.application.dto.DocumentBody;
import com.playground.ragingestion.application.port.BodyFetchPort;
import com.playground.ragingestion.application.port.DistributedLockPort;
import com.playground.ragingestion.application.port.EmbeddingPort;
import com.playground.ragingestion.application.repository.ChunkRepository;
import com.playground.ragingestion.domain.event.DocumentDeletedEvent;
import com.playground.ragingestion.domain.event.DocumentIngested;
import com.playground.ragingestion.domain.event.DocumentUploadedEvent;
import com.playground.ragingestion.domain.event.DocumentVisibilityChangedEvent;
import com.playground.ragingestion.domain.model.DocumentChunk;
import com.playground.ragingestion.domain.model.id.ChunkId;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.BodyChecksum;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import com.playground.ragingestion.domain.model.vo.Embedding;
import com.playground.ragingestion.domain.service.MarkdownChunker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The M3 ingestion orchestrator per ADR-13 §4 and the flow diagram in
 * ADR-13 §"Diagrams". One service handles all three event types because they
 * share the same Redisson lock key namespace per ADR-13 §5 (serialize all
 * per-document events through a single critical section).
 *
 * <p>Three entry points correspond to the three consumed topics:
 * <ul>
 *   <li>{@link #handleUploaded(DocumentUploadedEvent)} — fetch body, derive
 *       checksum, skip if unchanged (idempotency per ADR-13 §12), else chunk
 *       + embed + replace + publish {@link DocumentIngested}.</li>
 *   <li>{@link #handleVisibilityChanged(DocumentVisibilityChangedEvent)} —
 *       UPDATE-only re-tag (no body fetch, no embedding) per ADR-13 §5.</li>
 *   <li>{@link #handleDeleted(DocumentDeletedEvent)} — purge every chunk for
 *       the document; no further event published.</li>
 * </ul>
 *
 * <p>The lock parameters (60 s lease, 30 s wait) sit well inside the 5-min
 * ADR-08 cap. The 30 s wait gives a serialized second-arriving event ample
 * time to acquire the lock once the first releases — the worst-case
 * {@code uploaded} budget is ~47 s per ADR-13 §2 so a longer wait would
 * occasionally surface as a spurious DLQ route under healthy operation.
 */
@Service
public class IngestionService {

    private static final Log log = LogFactory.getLog(IngestionService.class);

    /** Lock TTL — ADR-13 §5 + §A. Well below the ADR-08 5-min cap. */
    private static final Duration LOCK_LEASE = Duration.ofSeconds(60);

    /** How long a serialized event waits for the lock holder to release. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(30);

    private final ChunkRepository chunkRepository;
    private final BodyFetchPort bodyFetchPort;
    private final EmbeddingPort embeddingPort;
    private final DistributedLockPort lockPort;
    private final MarkdownChunker chunker;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IngestionService(
            ChunkRepository chunkRepository,
            BodyFetchPort bodyFetchPort,
            EmbeddingPort embeddingPort,
            DistributedLockPort lockPort,
            MarkdownChunker chunker,
            ApplicationEventPublisher events,
            Clock clock) {
        this.chunkRepository = chunkRepository;
        this.bodyFetchPort = bodyFetchPort;
        this.embeddingPort = embeddingPort;
        this.lockPort = lockPort;
        this.chunker = chunker;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Idempotent re-ingestion per ADR-13 §12. Acquires the lock, checks the
     * stored {@code bodyChecksum}, short-circuits if unchanged, else chunks +
     * embeds + replaces the chunk set + publishes
     * {@link DocumentIngested}.
     */
    public void handleUploaded(DocumentUploadedEvent event) {
        String lockKey = lockKey(event.documentId());
        lockPort.runWithLock(lockKey, LOCK_WAIT, LOCK_LEASE, () -> {
            ingestInTx(event);
            return null;
        });
    }

    /**
     * The transactional half of {@code handleUploaded}. Split into a method
     * Spring can proxy so {@code @Transactional} actually applies — the
     * Redisson lock runs *outside* the JPA transaction so the lock holds
     * across the WebClient calls (body fetch + embedding).
     */
    @Transactional
    public void ingestInTx(DocumentUploadedEvent event) {
        Optional<BodyChecksum> existing = chunkRepository.findBodyChecksum(event.documentId());
        BodyChecksum eventChecksum = BodyChecksum.of(event.bodyChecksum());

        if (existing.isPresent() && existing.get().equals(eventChecksum)) {
            log.info(String.format(
                    "rag-ingestion: skip uploaded — checksum unchanged documentId=%s userId=%s checksum=%s",
                    event.documentId(), event.userId(), eventChecksum));
            // ADR-13 §12 step 3: emit ingestion-complete defensively so a
            // downstream consumer that missed the original emission still
            // sees the document as ready.
            publishIngested(event, chunkRepository.countByDocument(event.documentId()), eventChecksum);
            return;
        }

        DocumentBody body = bodyFetchPort.fetchBody(event.documentId());
        BodyChecksum fetchedChecksum = body.bodyChecksum();
        // Defensive: a docs-asserted checksum that disagrees with the event
        // is a wire / cache invariant violation. Use the docs-asserted one
        // (source of truth) and surface the divergence in the log.
        if (!fetchedChecksum.equals(eventChecksum)) {
            log.warn(String.format(
                    "rag-ingestion: checksum mismatch event=%s docs=%s for document=%s — using docs value",
                    eventChecksum, fetchedChecksum, event.documentId()));
        }

        List<ChunkText> texts = chunker.chunk(body.body());
        if (texts.isEmpty()) {
            // Empty body: purge any stale chunks and emit ingested with 0
            // count so downstream can render "queryable (but empty)" state.
            chunkRepository.deleteAll(event.documentId());
            log.info(String.format(
                    "rag-ingestion: empty body documentId=%s userId=%s — purged stale chunks",
                    event.documentId(), event.userId()));
            publishIngested(event, 0, fetchedChecksum);
            return;
        }

        List<Embedding> embeddings = embeddingPort.embed(texts);
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding port returned " + embeddings.size() + " vectors for " + texts.size() + " chunks "
                            + "(document=" + event.documentId() + ")");
        }

        Instant now = Instant.now(clock);
        List<DocumentChunk> chunks = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(new DocumentChunk(
                    ChunkId.of(event.documentId(), i),
                    event.userId(),
                    event.visibility(),
                    texts.get(i),
                    embeddings.get(i),
                    fetchedChecksum,
                    now));
        }
        chunkRepository.replaceAll(event.documentId(), chunks);

        log.info(String.format(
                "rag-ingestion: ingested documentId=%s userId=%s visibility=%s chunkCount=%d checksum=%s",
                event.documentId(), event.userId(), event.visibility().wireValue(),
                chunks.size(), fetchedChecksum));
        publishIngested(event, chunks.size(), fetchedChecksum);
    }

    /**
     * Re-tag every chunk row for the document; no body fetch, no embedding.
     * The lock is shared with {@code uploaded} per ADR-13 §5 so a
     * mid-{@code uploaded} arrival waits and runs UPDATE against the fresh
     * row set.
     */
    public void handleVisibilityChanged(DocumentVisibilityChangedEvent event) {
        String lockKey = lockKey(event.documentId());
        lockPort.runWithLock(lockKey, LOCK_WAIT, LOCK_LEASE, () -> {
            retagInTx(event);
            return null;
        });
    }

    @Transactional
    public void retagInTx(DocumentVisibilityChangedEvent event) {
        int touched = chunkRepository.updateVisibility(event.documentId(), event.newVisibility());
        log.info(String.format(
                "rag-ingestion: visibility-changed documentId=%s userId=%s newVisibility=%s rowsTouched=%d",
                event.documentId(), event.userId(),
                event.newVisibility().wireValue(), touched));
        // No DocumentIngested emission per ADR-13 §3 — the signal is for
        // first-time queryability, not re-tagging.
    }

    /**
     * Purge every chunk row for the document. Shared lock with uploaded so a
     * delete arriving mid-ingest waits for the upsert to finish before
     * purging (Kafka delivery order on the same key already makes this
     * monotonic in practice).
     */
    public void handleDeleted(DocumentDeletedEvent event) {
        String lockKey = lockKey(event.documentId());
        lockPort.runWithLock(lockKey, LOCK_WAIT, LOCK_LEASE, () -> {
            deleteInTx(event);
            return null;
        });
    }

    @Transactional
    public void deleteInTx(DocumentDeletedEvent event) {
        int purged = chunkRepository.deleteAll(event.documentId());
        log.info(String.format(
                "rag-ingestion: deleted documentId=%s userId=%s purgedRows=%d",
                event.documentId(), event.userId(), purged));
    }

    private void publishIngested(DocumentUploadedEvent event, int chunkCount, BodyChecksum checksum) {
        DocumentIngested ingested = new DocumentIngested(
                event.documentId(),
                event.userId(),
                event.visibility(),
                chunkCount,
                checksum.value(),
                Instant.now(clock));
        events.publishEvent(ingested);
    }

    private static String lockKey(DocumentId documentId) {
        // The DistributedLockPort adapter prepends the rag-ingestion namespace
        // (per ADR-08 Exception 2). The key here is the unqualified suffix.
        return "document:" + documentId.value();
    }
}
