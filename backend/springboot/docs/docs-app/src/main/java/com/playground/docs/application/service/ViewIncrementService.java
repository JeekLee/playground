package com.playground.docs.application.service;

import com.playground.docs.application.port.ViewClaimPort;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.DocumentId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case service for the view-counter endpoint per M2 spec §6.1
 * {@code POST /api/docs/{id}/view} + ADR-12 §10.
 *
 * <p>Behavior contract:
 * <ul>
 *   <li>Document not found → 404 (via {@link DocumentNotFoundException}).</li>
 *   <li>Document {@code visibility='private'} → 204 no-op (regardless of
 *       whether the caller is the author — spec §6.1: "Otherwise no-op
 *       (still 204, no leak)").</li>
 *   <li>Document {@code visibility='public'} → claim the dedup key via
 *       {@link ViewClaimPort#claim}; on first claim, increment the
 *       denormalized {@code view_count}. Returns 204 either way.</li>
 * </ul>
 *
 * <p>Per ADR-12 §10 "same-cookie path regardless of auth state" — the dedup
 * key is built the same way whether the caller is anonymous or
 * authenticated. The controller passes the resolved cookie value (or
 * {@code null} → IP fallback); the service has no opinion on the auth
 * state.
 */
@Service
@RequiredArgsConstructor
public class ViewIncrementService {

    /** Per ADR-12 §10 the dedup window is 24 hours, fixed. */
    public static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final DocumentRepository documentRepository;
    private final ViewClaimPort viewClaim;

    /**
     * Attempt to increment {@code view_count} for the given doc + viewer
     * tuple. The {@code anonCookie} is preferred; when null the {@code ip}
     * fallback is used (per spec §6.1 and ADR-12 §10).
     *
     * @param documentId the doc UUID
     * @param anonCookie value of the {@code PLAYGROUND_ANON} cookie set by
     *                   the gateway's {@link
     *                   com.playground.gateway.filter.AnonCookieFilter}
     *                   (may be null on the IP-only fallback)
     * @param clientIp   first hop of the {@code X-Forwarded-For} header
     *                   (used only when {@code anonCookie} is null/blank)
     */
    @Transactional
    public void increment(UUID documentId, String anonCookie, String clientIp) {
        DocumentId id = DocumentId.of(documentId);
        Optional<Document> docOpt = documentRepository.findById(id);
        if (docOpt.isEmpty()) {
            throw new DocumentNotFoundException(id);
        }
        Document doc = docOpt.get();
        if (!doc.isPublic()) {
            // Per spec §6.1: visibility='private' → no-op, still 204 (no leak).
            return;
        }
        String dedupKey = buildKey(documentId, anonCookie, clientIp);
        if (dedupKey == null) {
            // Neither cookie nor IP available — gateway misconfiguration.
            // Treat as deduped to fail-closed against runaway counters
            // when the dedup substrate is degraded.
            return;
        }
        boolean firstClaim = viewClaim.claim(dedupKey, DEDUP_TTL);
        if (firstClaim) {
            documentRepository.incrementViewCount(id);
        }
    }

    /**
     * Build the Redis key for the (document, viewer) tuple per ADR-12 §10's
     * Java sketch. Returns {@code null} when neither cookie nor IP is
     * available (defensive — production gateway always supplies at least
     * one).
     */
    static String buildKey(UUID documentId, String anonCookie, String clientIp) {
        if (anonCookie != null && !anonCookie.isBlank()) {
            return "view:" + documentId + ":anon:" + anonCookie;
        }
        if (clientIp != null && !clientIp.isBlank()) {
            return "view:" + documentId + ":ip:" + clientIp;
        }
        return null;
    }
}
