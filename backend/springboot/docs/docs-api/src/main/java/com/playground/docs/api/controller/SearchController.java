package com.playground.docs.api.controller;

import com.playground.docs.api.response.SearchResponse;
import com.playground.docs.application.service.DocumentSearchService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search controller per M2 spec §6.1 {@code GET /api/docs/search}.
 *
 * <p>Scopes:
 * <ul>
 *   <li>{@code public} — auth optional; searches community-wide public corpus.</li>
 *   <li>{@code mine} — auth required; searches caller's own docs (any visibility).</li>
 * </ul>
 *
 * <p>Gateway-side: the route is allow-listed as {@code permitAll()} per the
 * S2 amendment to ADR-09 — the docs-api enforces the {@code scope=mine}
 * authentication check internally (returns 401 via the shared exception
 * hierarchy if the header is missing). This is cleaner than splitting one
 * route across two gateway rules.
 *
 * <p>OpenSearch failures surface as 503 with
 * {@link DocsErrorCode#SEARCH_UNAVAILABLE} so callers can distinguish
 * "no hits" from "search subsystem down" per spec §10.
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final DocumentSearchService searchService;

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam("q") String query,
            @RequestParam(value = "scope", required = false, defaultValue = "public") String scope,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return switch (scope) {
            case "public" -> ResponseEntity.ok(SearchResponse.from(
                    searchService.searchPublic(query, cursor)));
            case "mine" -> ResponseEntity.ok(SearchResponse.from(
                    searchService.searchMine(requireUserId(userIdHeader), query, cursor)));
            default -> {
                ExceptionCreator.of(DocsErrorCode.SEARCH_SCOPE_INVALID).throwIt();
                yield null; // unreachable
            }
        };
    }

    private static UUID requireUserId(String header) {
        if (header == null || header.isBlank()) {
            ExceptionCreator.of(DocsErrorCode.USER_HEADER_MISSING).throwIt();
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.USER_HEADER_MISSING).throwIt();
            return null; // unreachable
        }
    }
}
