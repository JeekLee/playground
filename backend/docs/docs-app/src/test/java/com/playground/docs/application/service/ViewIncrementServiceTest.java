package com.playground.docs.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.docs.application.port.ViewClaimPort;
import com.playground.docs.application.repository.DocumentRepository;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.Document;
import com.playground.docs.domain.model.id.AuthorId;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentPath;
import com.playground.docs.domain.model.vo.DocumentTitle;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ViewIncrementService} per M2 spec §6.1 + §10 +
 * ADR-12 §10.
 *
 * <p>Three invariants the service must hold:
 * <ol>
 *   <li>Visibility gate: private docs return 204 no-op (no counter bump,
 *       no Redis call).</li>
 *   <li>Dedup correctness: the Redis {@code setIfAbsent} verdict drives
 *       the counter bump — a deduped hit does NOT bump.</li>
 *   <li>Key shape: the cookie variant is preferred; IP fallback when
 *       cookie is null/blank; no key at all when both are missing
 *       (degrade gracefully, do not bump).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ViewIncrementServiceTest {

    @Mock
    DocumentRepository documentRepository;
    @Mock
    ViewClaimPort viewClaim;

    private ViewIncrementService service;

    @BeforeEach
    void setUp() {
        service = new ViewIncrementService(documentRepository, viewClaim);
    }

    @Test
    void public_doc_first_view_bumps_counter() {
        UUID docId = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(publicDoc(docId)));
        when(viewClaim.claim(any(), eq(ViewIncrementService.DEDUP_TTL))).thenReturn(true);

        service.increment(docId, "anon-cookie-value", "1.2.3.4");

        verify(viewClaim).claim("view:" + docId + ":anon:anon-cookie-value", ViewIncrementService.DEDUP_TTL);
        verify(documentRepository).incrementViewCount(id);
    }

    @Test
    void public_doc_deduped_hit_does_not_bump_counter() {
        UUID docId = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(publicDoc(docId)));
        when(viewClaim.claim(any(), any())).thenReturn(false);

        service.increment(docId, "anon-cookie-value", "1.2.3.4");

        verify(documentRepository, never()).incrementViewCount(id);
    }

    @Test
    void private_doc_is_204_noop_no_redis_no_bump() {
        UUID docId = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(privateDoc(docId)));

        service.increment(docId, "anon-cookie", "1.2.3.4");

        // Spec §6.1: visibility='private' → 204 no-op. No Redis claim, no
        // counter bump. Importantly the response is indistinguishable from
        // a public deduped hit, so no visibility leak.
        verify(viewClaim, never()).claim(any(), any());
        verify(documentRepository, never()).incrementViewCount(id);
    }

    @Test
    void missing_doc_throws_not_found() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(DocumentId.of(docId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.increment(docId, "cookie", "1.2.3.4"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void cookie_preferred_over_ip_in_key() {
        UUID docId = UUID.randomUUID();
        String key = ViewIncrementService.buildKey(docId, "the-cookie", "9.9.9.9");
        assertThat(key).isEqualTo("view:" + docId + ":anon:the-cookie");
    }

    @Test
    void ip_used_when_cookie_null() {
        UUID docId = UUID.randomUUID();
        String key = ViewIncrementService.buildKey(docId, null, "1.2.3.4");
        assertThat(key).isEqualTo("view:" + docId + ":ip:1.2.3.4");
    }

    @Test
    void ip_used_when_cookie_blank() {
        UUID docId = UUID.randomUUID();
        String key = ViewIncrementService.buildKey(docId, "   ", "1.2.3.4");
        assertThat(key).isEqualTo("view:" + docId + ":ip:1.2.3.4");
    }

    @Test
    void null_when_both_cookie_and_ip_absent() {
        // When neither cookie nor IP is available the service degrades to a
        // dedup-hit semantic (no counter bump). Defensive — production
        // gateway always supplies at least one.
        UUID docId = UUID.randomUUID();
        assertThat(ViewIncrementService.buildKey(docId, null, null)).isNull();
        assertThat(ViewIncrementService.buildKey(docId, "", "")).isNull();
    }

    @Test
    void degraded_substrate_no_keys_no_counter_bump() {
        UUID docId = UUID.randomUUID();
        DocumentId id = DocumentId.of(docId);
        when(documentRepository.findById(id)).thenReturn(Optional.of(publicDoc(docId)));

        // Neither cookie nor IP available — service treats as deduped
        // (no Redis call, no counter bump). Avoids runaway counters when
        // the dedup substrate is degraded.
        service.increment(docId, null, null);

        verify(viewClaim, never()).claim(any(), any());
        verify(documentRepository, never()).incrementViewCount(id);
    }

    @Test
    void ttl_is_24_hours() {
        assertThat(ViewIncrementService.DEDUP_TTL).isEqualTo(Duration.ofHours(24));
    }

    private Document publicDoc(UUID docId) {
        return Document.create(
                DocumentId.of(docId),
                AuthorId.of(UUID.randomUUID()),
                DocumentTitle.of("Hi"),
                DocumentBody.of("body"),
                DocumentPath.ROOT,
                Instant.parse("2026-05-18T00:00:00Z"))
                .publish(Instant.parse("2026-05-18T01:00:00Z"));
    }

    private Document privateDoc(UUID docId) {
        return Document.create(
                DocumentId.of(docId),
                AuthorId.of(UUID.randomUUID()),
                DocumentTitle.of("Hi"),
                DocumentBody.of("body"),
                DocumentPath.ROOT,
                Instant.parse("2026-05-18T00:00:00Z"));
    }
}
