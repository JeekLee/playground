package com.playground.docs.infrastructure.scheduler;

import com.playground.docs.application.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly counter resync per M2 spec §10 + ADR-12 §11. The job repairs
 * drift on the denormalized {@code docs.documents.like_count} column by
 * re-deriving it from the source-of-truth {@code docs.document_likes}
 * table.
 *
 * <p>{@code view_count} is <strong>not</strong> resynced — M2 has no
 * view-audit table (ADR-12 §11: "{@code view_count} is resync'd from
 * itself"), so the only honest source is the counter itself. If the
 * audience scope expands and {@code docs.document_view_log} lands as
 * a sibling source table, this job extends to cover both counters.
 *
 * <p>Schedule: {@code 0 0 3 * * *} (03:00 daily server time, a quiet
 * hour per ADR-12 §11). Single-instance dev — no ShedLock — explicitly
 * deferred in ADR-12 §11.
 *
 * <p>Failure handling: the {@code UPDATE} runs as one statement, so it
 * either applies wholesale or rolls back; partial failure isn't a
 * concern. A persistent failure (e.g. Postgres down at 03:00) lands a
 * WARN log and is retried at the next scheduled tick — daily retry is
 * acceptable for drift that's already informational.
 */
@Component
public class CounterResyncJob {

    private static final Logger log = LoggerFactory.getLogger(CounterResyncJob.class);

    private final DocumentRepository documentRepository;

    public CounterResyncJob(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * Per ADR-12 §11: 03:00 daily. The cron expression matches Spring's
     * {@code @Scheduled} 6-field format (seconds, minutes, hours, day-of-
     * month, month, day-of-week).
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void resyncLikeCounts() {
        long start = System.nanoTime();
        log.info("counter-resync: starting like_count repair from docs.document_likes");
        try {
            int rows = documentRepository.resyncLikeCounts();
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            log.info(
                    "counter-resync: like_count repair complete; rows={} durationMs={}",
                    rows,
                    durationMs);
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;
            log.warn(
                    "counter-resync: like_count repair failed after durationMs={}; will retry next tick",
                    durationMs,
                    e);
            throw e;
        }
    }
}
