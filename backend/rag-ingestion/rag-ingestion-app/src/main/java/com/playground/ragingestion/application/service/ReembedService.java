package com.playground.ragingestion.application.service;

import com.playground.ragingestion.application.dto.DocumentBody;
import com.playground.ragingestion.application.port.BodyFetchPort;
import com.playground.ragingestion.application.port.DistributedLockPort;
import com.playground.ragingestion.application.port.EmbeddingPort;
import com.playground.ragingestion.application.repository.ChunkOwnerMeta;
import com.playground.ragingestion.application.repository.ChunkRepository;
import com.playground.ragingestion.domain.event.DocumentIngested;
import com.playground.ragingestion.domain.model.DocumentChunk;
import com.playground.ragingestion.domain.model.id.ChunkId;
import com.playground.ragingestion.domain.model.id.DocumentId;
import com.playground.ragingestion.domain.model.vo.ChunkDraft;
import com.playground.ragingestion.domain.model.vo.ChunkText;
import com.playground.ragingestion.domain.model.vo.Embedding;
import com.playground.ragingestion.domain.service.MarkdownAwareChunker;
import com.playground.shared.error.NotFoundException;
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
 * Per-document re-ingest pipeline used by {@code ReembedCommandLineRunner}
 * (Task 18). Takes existing documents (chunked with the old fixed-size chunker,
 * no {@code heading_path}) and re-processes them through
 * {@link MarkdownAwareChunker}, yielding heading-aware chunks that populate
 * {@code heading_path}.
 *
 * <p>Uses the same Redisson lock key namespace as {@link IngestionService} per
 * M3.1 plan §Task 17: {@code "document:" + documentId} so live
 * {@code docs.document.uploaded} events arriving mid-job serialize correctly
 * — they wait for the re-embed to commit before writing their own chunk set.
 *
 * <p>{@code userId} and {@code visibility} are looked up from existing chunk
 * rows (any row for the document suffices — both are invariant per document).
 * This avoids a docs-api round-trip and is safe because:
 * <ul>
 *   <li>Visibility changes go through {@code updateVisibility} which rewrites
 *       every row atomically, so any row reflects the current value.</li>
 *   <li>Ownership ({@code user_id}) is immutable for the lifetime of a
 *       document.</li>
 * </ul>
 */
@Service
public class ReembedService {

    private static final Log log = LogFactory.getLog(ReembedService.class);

    /** Same lock parameters as {@link IngestionService} per M3.1 §Task 17. */
    private static final Duration LOCK_LEASE = Duration.ofSeconds(60);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(30);

    /**
     * Outcome of a single {@link #reembedOne(DocumentId)} call. The caller
     * (Task 18's runner) aggregates these to produce a job-completion summary.
     */
    public enum Outcome {
        /** Chunks replaced successfully. */
        SUCCESS,
        /**
         * Document skipped — either no existing chunks (nothing to re-embed)
         * or docs-api returned 404 (document was deleted between scheduling
         * and execution).
         */
        SKIPPED,
        /** An unexpected error occurred; the document was not re-embedded. */
        FAILED
    }

    private final MarkdownAwareChunker chunker;
    private final EmbeddingPort embeddingPort;
    private final ChunkRepository chunkRepository;
    private final BodyFetchPort bodyFetchPort;
    private final DistributedLockPort lockPort;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReembedService(
            MarkdownAwareChunker chunker,
            EmbeddingPort embeddingPort,
            ChunkRepository chunkRepository,
            BodyFetchPort bodyFetchPort,
            DistributedLockPort lockPort,
            ApplicationEventPublisher events,
            Clock clock) {
        this.chunker = chunker;
        this.embeddingPort = embeddingPort;
        this.chunkRepository = chunkRepository;
        this.bodyFetchPort = bodyFetchPort;
        this.lockPort = lockPort;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Re-embed a single document. Acquires the per-document lock (same key as
     * {@link IngestionService}) before delegating to the transactional inner
     * method so that a concurrent {@code uploaded} event is serialized.
     *
     * @param documentId the document to re-embed
     * @return {@link Outcome} — never throws; all exceptions are caught and
     *         mapped to {@link Outcome#FAILED}
     */
    public Outcome reembedOne(DocumentId documentId) {
        String lockKey = lockKey(documentId);
        try {
            return lockPort.runWithLock(lockKey, LOCK_WAIT, LOCK_LEASE,
                    () -> reembedInTx(documentId));
        } catch (NotFoundException e) {
            // docs-api returned 404: document was deleted — harmless, skip it.
            log.warn(String.format(
                    "rag-ingestion reembed: SKIPPED documentId=%s — docs-api 404 (document deleted?)",
                    documentId));
            return Outcome.SKIPPED;
        } catch (RuntimeException e) {
            log.error(String.format(
                    "rag-ingestion reembed: FAILED documentId=%s — %s",
                    documentId, e.getMessage()), e);
            return Outcome.FAILED;
        }
    }

    /**
     * Transactional inner method. Spring proxies this so {@code @Transactional}
     * applies; the lock runs outside the transaction so it covers the body
     * fetch + embedding HTTP calls.
     */
    @Transactional
    public Outcome reembedInTx(DocumentId documentId) {
        // Step 1 — look up userId + visibility from existing chunks.
        Optional<ChunkOwnerMeta> ownerOpt = chunkRepository.findOwnerMeta(documentId);
        if (ownerOpt.isEmpty()) {
            log.warn(String.format(
                    "rag-ingestion reembed: SKIPPED documentId=%s — no existing chunks found",
                    documentId));
            return Outcome.SKIPPED;
        }
        ChunkOwnerMeta owner = ownerOpt.get();

        // Step 2 — fetch the current body from docs-api.
        // NotFoundException (404) propagates to reembedOne which maps it to SKIPPED.
        DocumentBody body = bodyFetchPort.fetchBody(documentId);

        // Step 3 — chunk + embed.
        List<ChunkDraft> drafts = chunker.chunk(body.body());
        if (drafts.isEmpty()) {
            // Empty body: purge stale chunks and emit ingested(0) so downstream
            // sees the document as queryable-but-empty (same policy as IngestionService).
            chunkRepository.deleteAll(documentId);
            log.info(String.format(
                    "rag-ingestion reembed: empty body documentId=%s userId=%s — purged stale chunks",
                    documentId, owner.userId()));
            publishIngested(documentId, owner, 0, body.bodyChecksum().value());
            return Outcome.SUCCESS;
        }

        List<ChunkText> texts = drafts.stream().map(ChunkDraft::text).toList();
        List<Embedding> embeddings = embeddingPort.embed(texts);
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException(
                    "Embedding port returned " + embeddings.size() + " vectors for " + texts.size()
                            + " chunks (document=" + documentId + ")");
        }

        // Step 4 — build DocumentChunk list + replaceAll.
        Instant now = Instant.now(clock);
        List<DocumentChunk> chunks = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft d = drafts.get(i);
            chunks.add(new DocumentChunk(
                    ChunkId.of(documentId, i),
                    owner.userId(),
                    owner.visibility(),
                    d.text(),
                    embeddings.get(i),
                    d.headingPath(),
                    body.bodyChecksum(),
                    now));
        }
        chunkRepository.replaceAll(documentId, chunks);

        log.info(String.format(
                "rag-ingestion reembed: SUCCESS documentId=%s userId=%s visibility=%s chunkCount=%d",
                documentId, owner.userId(), owner.visibility().wireValue(), chunks.size()));

        // Step 5 — publish DocumentIngested so downstream sees the re-indexed state.
        publishIngested(documentId, owner, chunks.size(), body.bodyChecksum().value());
        return Outcome.SUCCESS;
    }

    private void publishIngested(
            DocumentId documentId, ChunkOwnerMeta owner, int chunkCount, String bodyChecksum) {
        events.publishEvent(new DocumentIngested(
                documentId,
                owner.userId(),
                owner.visibility(),
                chunkCount,
                bodyChecksum,
                Instant.now(clock)));
    }

    private static String lockKey(DocumentId documentId) {
        // Same key format as IngestionService — both services share the same
        // Redisson lock namespace so concurrent upload + reembed events serialize.
        return "document:" + documentId.value();
    }
}
