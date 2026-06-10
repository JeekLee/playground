package com.playground.docs.api.controller;

import com.playground.docs.application.service.DocumentLikeService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the M2 S3 like-toggle endpoints per spec §6.1 rows
 * {@code POST /api/docs/{id}/like} + {@code DELETE /api/docs/{id}/like}.
 *
 * <p>Paths reflect the post-gateway-strip view: the gateway forwards
 * {@code /api/docs/**} → {@code docs-api} with {@code StripPrefix=2}, so
 * this controller maps the {@code /{id}/like} tail directly.
 *
 * <p>Both routes require auth (the gateway's authenticated allowlist gates
 * {@code POST}/{@code DELETE /api/docs/{id}/like} per ADR-12 amendment to
 * ADR-09). The downstream {@link DocumentLikeService} is idempotent so a
 * client that fires the same call twice (network retry, double-click) still
 * lands on the same final state.
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class DocumentLikeController {

    private final DocumentLikeService likeService;

    /** Per M2 spec §6.1: idempotent upsert, returns 204. */
    @PostMapping("/{id}/like")
    public ResponseEntity<Void> like(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID caller = requireUserId(userIdHeader);
        UUID documentId = parseDocumentId(id);
        likeService.like(documentId, caller);
        return ResponseEntity.noContent().build();
    }

    /** Per M2 spec §6.1: idempotent delete, returns 204. */
    @DeleteMapping("/{id}/like")
    public ResponseEntity<Void> unlike(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID caller = requireUserId(userIdHeader);
        UUID documentId = parseDocumentId(id);
        likeService.unlike(documentId, caller);
        return ResponseEntity.noContent().build();
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

    private static UUID parseDocumentId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.DOCUMENT_NOT_FOUND, id).throwIt();
            return null; // unreachable
        }
    }
}
