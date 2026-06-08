package com.playground.docs.api.controller;

import com.playground.docs.api.response.DocumentBodyResponse;
import com.playground.docs.api.response.DocumentManifestResponse;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal body-fetch route for M3 rag-ingestion per ADR-12 §2.
 *
 * <p>{@code /internal/**} is NOT exposed by the gateway (ADR-07's route table
 * excludes the prefix), so this route is reachable only over the compose-
 * internal network. No auth filter / no {@code X-User-*} header reading —
 * the caller is rag-ingestion-infra, which has no user identity to
 * propagate.
 *
 * <p>Read-only: only {@code GET} verbs live here. M3 calls this when
 * processing a {@code docs.document.uploaded} event to fetch the raw
 * body for chunking + embedding.
 */
@RestController
@RequestMapping("/internal/docs")
public class InternalDocumentController {

    private static final int MAX_MANIFEST_LIMIT = 100;

    private final DocumentAppService docService;

    public InternalDocumentController(DocumentAppService docService) {
        this.docService = docService;
    }

    /**
     * SP3a spec D1 — lightweight {@code {id,title}} manifest for the chat
     * {@code [YOUR DOCUMENTS]} prompt section. The caller (chat) passes the
     * target {@code userId} explicitly — {@code /internal/**} reads no auth
     * header. Ordering is {@code created_at ASC}; {@code limit} is clamped to
     * {@code [1, 100]}. Malformed/missing userId → 400; empty result → 200 with
     * an empty array.
     */
    @GetMapping("/manifest")
    public ResponseEntity<DocumentManifestResponse> manifest(
            @RequestParam("userId") String userId,
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        UUID uid;
        try {
            uid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.MANIFEST_USER_INVALID).throwIt();
            return null; // unreachable
        }
        int clamped = Math.max(1, Math.min(MAX_MANIFEST_LIMIT, limit));
        return ResponseEntity.ok(
                DocumentManifestResponse.from(docService.manifestForUser(uid, clamped)));
    }

    @GetMapping("/{id}/body")
    public ResponseEntity<DocumentBodyResponse> getBody(@PathVariable("id") String id) {
        UUID documentId;
        try {
            documentId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.DOCUMENT_NOT_FOUND, id).throwIt();
            return null; // unreachable
        }
        var body = docService.getBody(documentId);
        return ResponseEntity.ok(DocumentBodyResponse.from(body));
    }
}
