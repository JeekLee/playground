package com.playground.docs.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.docs.search.application.service.SearchDocumentsService;
import com.playground.docs.search.application.service.SearchDocumentsService.SearchOutcome;
import com.playground.shared.error.ExceptionCreator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code search_documents} tool endpoint (agentic-search spec D1).
 *
 * <p>{@code /internal/**} follows the InternalDocumentController convention —
 * compose-internal only, not exposed by the gateway (ADR-07). Unlike the
 * read-only body-fetch route, search needs the caller's identity for the
 * visibility filter, so {@code X-User-Id} is required (a missing/blank header
 * or blank query is a pre-handshake 400).
 *
 * <p>NDJSON tool wire: search runs in ~hundreds of ms, well under the tool
 * dispatcher's 30s idle timeout, so a single terminal line satisfies the
 * stream contract — no progress/heartbeat events are emitted. A success is one
 * {@code result} line; a failure <em>after</em> the (implicit) handshake is a
 * terminal {@code error} event line with HTTP still 200, per the stream
 * contract (only pre-handshake validation returns a real 4xx).
 */
@RestController
@RequestMapping("/internal/tools")
public class SearchToolController {

    private static final Logger log = LoggerFactory.getLogger(SearchToolController.class);

    private final SearchDocumentsService searchService;
    private final ObjectMapper objectMapper;

    public SearchToolController(SearchDocumentsService searchService, ObjectMapper objectMapper) {
        this.searchService = searchService;
        this.objectMapper = objectMapper;
    }

    /** Tool invocation body. {@code topK}/{@code documentId} are optional. */
    public record SearchRequest(String query, Integer topK, UUID documentId) {}

    @PostMapping(value = "/search-documents", produces = "application/x-ndjson")
    public ResponseEntity<String> search(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody SearchRequest req) {
        UUID callerId = requireUserId(userId);
        String query = req == null ? null : req.query();
        if (query == null || query.isBlank()) {
            ExceptionCreator.of(DocsErrorCode.TOOL_QUERY_BLANK).throwIt();
        }

        try {
            SearchOutcome outcome = searchService.search(
                    callerId, query, req.topK(), req.documentId());
            return ndjson(resultEvent(outcome));
        } catch (RuntimeException e) {
            // Post-handshake failure: terminal error EVENT, HTTP stays 200.
            log.warn("search-documents tool failed: {}", e.toString());
            return ndjson(errorEvent("SEARCH_EMBEDDING_FAILED", e.getMessage(), 502));
        }
    }

    private static UUID requireUserId(String header) {
        if (header == null || header.isBlank()) {
            ExceptionCreator.of(DocsErrorCode.TOOL_USER_HEADER_MISSING).throwIt();
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.TOOL_USER_HEADER_MISSING).throwIt();
            return null; // unreachable
        }
    }

    private String resultEvent(SearchOutcome outcome) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "result");
        event.put("result", outcome);
        return serialize(event);
    }

    private String errorEvent(String code, String message, int status) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "error");
        event.put("code", code);
        event.put("message", message == null ? "" : message);
        event.put("status", status);
        return serialize(event);
    }

    private String serialize(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Serializing a plain map of primitives + a record cannot realistically
            // fail; fall back to a hand-built error line so the stream stays valid.
            return "{\"event\":\"error\",\"code\":\"SEARCH_SERIALIZATION_FAILED\","
                    + "\"message\":\"\",\"status\":500}";
        }
    }

    private static ResponseEntity<String> ndjson(String line) {
        // Pin UTF-8 — the result body carries the Korean summary ("…건") and may
        // carry non-ASCII titles/excerpts; without an explicit charset the
        // servlet defaults to ISO-8859-1 and mojibakes them.
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .body(line + "\n");
    }
}
