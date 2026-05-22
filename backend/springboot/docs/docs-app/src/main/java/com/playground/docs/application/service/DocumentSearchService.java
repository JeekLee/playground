package com.playground.docs.application.service;

import com.playground.docs.application.dto.AuthorDto;
import com.playground.docs.application.dto.CursorPage;
import com.playground.docs.application.dto.SearchHitDto;
import com.playground.docs.application.port.IdentityLookupPort;
import com.playground.docs.application.port.SearchIndexPort;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OpenSearch-backed search service per M2 spec §6.1.
 *
 * <p>Two query shapes:
 * <ul>
 *   <li>{@code scope=public} — anonymous or authenticated; searches the entire
 *       community public corpus.</li>
 *   <li>{@code scope=mine} — authenticated only; searches the caller's own
 *       docs (any visibility).</li>
 * </ul>
 *
 * <p>Failure isolation per spec §10: any underlying OpenSearch error is
 * re-raised as a {@code DocsErrorCode.SEARCH_UNAVAILABLE} 503 — the rest of
 * M2's API remains responsive (the projector adapter throws on a downed
 * cluster, the controller surfaces 503 not an empty list).
 *
 * <p>Author resolution mirrors {@link DocumentFeedService} — one batched
 * identity lookup per page.
 */
@Service
public class DocumentSearchService {

    private final SearchIndexPort searchIndex;
    private final IdentityLookupPort identityLookup;

    public DocumentSearchService(SearchIndexPort searchIndex, IdentityLookupPort identityLookup) {
        this.searchIndex = searchIndex;
        this.identityLookup = identityLookup;
    }

    @Transactional(readOnly = true)
    public CursorPage<SearchHitDto> searchPublic(String query, String cursor) {
        validateQuery(query);
        try {
            CursorPage<SearchHitDto> page = searchIndex.searchPublic(query.trim(), cursor);
            return populateAuthors(page);
        } catch (RuntimeException e) {
            throw mapToSearchUnavailable(e);
        }
    }

    @Transactional(readOnly = true)
    public CursorPage<SearchHitDto> searchMine(UUID callerUserId, String query, String cursor) {
        validateQuery(query);
        try {
            CursorPage<SearchHitDto> page = searchIndex.searchMine(callerUserId, query.trim(), cursor);
            // For mine-scope hits the spec §6.4 says author is omitted — we
            // strip whatever the adapter set so the wire shape is honored.
            return new CursorPage<>(
                    page.items().stream()
                            .map(hit -> new SearchHitDto(
                                    hit.documentId(),
                                    hit.title(),
                                    hit.visibility(),
                                    hit.path(),
                                    null,
                                    hit.snippet(),
                                    hit.publishedAt(),
                                    hit.updatedAt()))
                            .toList(),
                    page.nextCursor());
        } catch (RuntimeException e) {
            throw mapToSearchUnavailable(e);
        }
    }

    private CursorPage<SearchHitDto> populateAuthors(CursorPage<SearchHitDto> raw) {
        if (raw.items().isEmpty() || identityLookup == null) {
            return raw;
        }
        // The adapter already does the OpenSearch denormalized name lookup;
        // we re-hydrate with the canonical AuthorDto so display-name drift
        // is repaired at-read-time per ADR-12 §12.
        Set<UUID> userIds = raw.items().stream()
                .filter(hit -> hit.author() != null)
                .map(hit -> hit.author().id())
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return raw;
        }
        Map<UUID, AuthorDto> resolved = identityLookup.findAllByIds(userIds);
        return new CursorPage<>(
                raw.items().stream()
                        .map(hit -> hit.author() == null
                                ? hit
                                : new SearchHitDto(
                                        hit.documentId(),
                                        hit.title(),
                                        hit.visibility(),
                                        hit.path(),
                                        resolved.getOrDefault(hit.author().id(), hit.author()),
                                        hit.snippet(),
                                        hit.publishedAt(),
                                        hit.updatedAt()))
                        .toList(),
                raw.nextCursor());
    }

    private static void validateQuery(String query) {
        if (query == null || query.isBlank()) {
            ExceptionCreator.of(DocsErrorCode.SEARCH_QUERY_BLANK).throwIt();
        }
    }

    private static RuntimeException mapToSearchUnavailable(RuntimeException e) {
        // Wrap whatever the adapter threw in the shared exception type so the
        // controller advice maps it to 503 with the proper error code shape.
        return new com.playground.shared.error.ServiceUnavailableException(
                DocsErrorCode.SEARCH_UNAVAILABLE, e);
    }
}
