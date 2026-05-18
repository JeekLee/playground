package com.playground.docs.api.controller;

import com.playground.docs.api.request.CreateDocumentRequest;
import com.playground.docs.api.request.PatchDocumentRequest;
import com.playground.docs.api.response.DocListResponse;
import com.playground.docs.api.response.DocumentDetailResponse;
import com.playground.docs.api.response.MyDocumentListResponse;
import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.application.service.DocumentFeedService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.shared.error.ExceptionCreator;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for the docs CRUD + feed surface per M2 spec §6.1.
 *
 * <p>Paths reflect the post-gateway-strip view: the gateway routes
 * {@code /api/docs/**} → {@code docs-api} with {@code StripPrefix=2}, so the
 * upstream service sees {@code /}, {@code /{id}}, {@code /{id}/publish} etc.
 *
 * <p>Auth contract:
 * <ul>
 *   <li>{@code GET /} with no scope (or with {@code author=...}) — auth optional;
 *       returns the community feed (or per-author public feed).</li>
 *   <li>{@code GET /?scope=mine} — auth required.</li>
 *   <li>{@code GET /{id}} — auth optional; visibility-or-ownership gate inside
 *       the app service.</li>
 *   <li>Mutations ({@code POST /}, {@code PATCH /{id}}, {@code POST /{id}/publish},
 *       {@code POST /{id}/unpublish}, {@code DELETE /{id}}) — auth required +
 *       owner.</li>
 * </ul>
 *
 * <p>Per M2 spec §6.5 + §10 "Tenant isolation" non-author callers attempting to
 * mutate (or read a private) document receive 404, never 403 — leaking
 * existence by status defeats tenant isolation.
 */
@RestController
@RequestMapping
public class DocumentController {

    private final DocumentAppService docService;
    private final DocumentFeedService feedService;

    public DocumentController(DocumentAppService docService, DocumentFeedService feedService) {
        this.docService = docService;
        this.feedService = feedService;
    }

    @PostMapping(
            value = {"/", ""},
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentDetailResponse> create(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @Valid @RequestBody CreateDocumentRequest request) {
        UUID author = requireUserId(userIdHeader);
        var detail = docService.create(request.toCommand(author));
        return ResponseEntity.status(201).body(DocumentDetailResponse.from(detail));
    }

    /**
     * Multipart variant of {@code POST /api/docs} per M2 spec §6.1: accepts a
     * {@code .md} file part + optional {@code title} + optional {@code path}.
     * The file contents (UTF-8) become the document body; if {@code title} is
     * absent the filename (sans {@code .md}/{@code .markdown} extension) is
     * used as a fallback.
     */
    @PostMapping(
            value = {"/", ""},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDetailResponse> createMultipart(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "path", required = false) String path) {
        UUID author = requireUserId(userIdHeader);
        if (file == null || file.isEmpty()) {
            ExceptionCreator.of(DocsErrorCode.UPLOAD_FILE_MISSING).throwIt();
        }
        // Defense in depth: the 1 MB cap is enforced by DocumentBody.of() in
        // the application service, but rejecting oversize uploads here avoids
        // copying the bytes off the multipart stream into memory only to fail
        // the VO check downstream.
        if (file.getSize() > DocumentBody.MAX_OCTET_LENGTH) {
            ExceptionCreator.of(DocsErrorCode.BODY_TOO_LARGE).throwIt();
        }
        String body = readFileAsUtf8(file);
        String resolvedTitle = (title == null || title.isBlank())
                ? deriveTitleFromFilename(file.getOriginalFilename())
                : title;
        var command = new CreateDocumentCommand(author, resolvedTitle, body, path);
        var detail = docService.create(command);
        return ResponseEntity.status(201).body(DocumentDetailResponse.from(detail));
    }

    /**
     * Unified {@code GET /api/docs} per M2 spec §6.1 + §6.2. Mode selection:
     * <ul>
     *   <li>{@code scope=mine} → caller's own docs (auth required). Combined
     *       with {@code path} filter (M2 S3 territory; S2 rejects with 400 so
     *       the frontend's UX isn't silently broken).</li>
     *   <li>{@code author={uuid}} → that author's public docs (auth optional).</li>
     *   <li>no scope, no author → community feed: every author's public docs,
     *       sorted {@code published_at DESC} (auth optional).</li>
     * </ul>
     */
    @GetMapping(value = {"/", ""})
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "path", required = false) String pathFilter,
            @RequestParam(value = "cursor", required = false) String cursor) {
        // ---- scope=mine: caller's docs ----
        if ("mine".equals(scope)) {
            UUID caller = requireUserId(userIdHeader);
            if (author != null || pathFilter != null) {
                // Path-filter on mine-scope lands in S3 (folder UI); reject in S2.
                ExceptionCreator.of(DocsErrorCode.SCOPE_FILTER_UNSUPPORTED).throwIt();
            }
            return ResponseEntity.ok(MyDocumentListResponse.from(docService.listMine(caller)));
        }
        // ---- scope present but not "mine": invalid ----
        if (scope != null && !scope.isBlank()) {
            ExceptionCreator.of(DocsErrorCode.SCOPE_REQUIRED).throwIt();
        }
        // ---- author=<uuid>: per-author public feed ----
        if (author != null && !author.isBlank()) {
            UUID authorId;
            try {
                authorId = UUID.fromString(author);
            } catch (IllegalArgumentException e) {
                ExceptionCreator.of(DocsErrorCode.AUTHOR_PARAM_INVALID).throwIt();
                return null; // unreachable
            }
            return ResponseEntity.ok(DocListResponse.from(feedService.authorFeed(authorId, cursor)));
        }
        // ---- default: community feed ----
        return ResponseEntity.ok(DocListResponse.from(feedService.communityFeed(cursor)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentDetailResponse> getById(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID documentId = parseDocumentId(id);
        UUID caller = parseOptionalUserId(userIdHeader);
        var detail = docService.getById(documentId, caller);
        return ResponseEntity.ok(DocumentDetailResponse.from(detail));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DocumentDetailResponse> patch(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id,
            @Valid @RequestBody PatchDocumentRequest request) {
        UUID caller = requireUserId(userIdHeader);
        UUID documentId = parseDocumentId(id);
        var detail = docService.patch(request.toCommand(documentId, caller));
        return ResponseEntity.ok(DocumentDetailResponse.from(detail));
    }

    /** Per M2 spec §6.1 {@code POST /api/docs/{id}/publish} — empty body, idempotent. */
    @PostMapping("/{id}/publish")
    public ResponseEntity<DocumentDetailResponse> publish(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID caller = requireUserId(userIdHeader);
        UUID documentId = parseDocumentId(id);
        var detail = docService.publish(documentId, caller);
        return ResponseEntity.ok(DocumentDetailResponse.from(detail));
    }

    /** Per M2 spec §6.1 {@code POST /api/docs/{id}/unpublish} — empty body, idempotent, publishedAt retained. */
    @PostMapping("/{id}/unpublish")
    public ResponseEntity<DocumentDetailResponse> unpublish(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID caller = requireUserId(userIdHeader);
        UUID documentId = parseDocumentId(id);
        var detail = docService.unpublish(documentId, caller);
        return ResponseEntity.ok(DocumentDetailResponse.from(detail));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID caller = requireUserId(userIdHeader);
        UUID documentId = parseDocumentId(id);
        docService.delete(documentId, caller);
        return ResponseEntity.noContent().build();
    }

    // --- helpers ---

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

    private static UUID parseOptionalUserId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            return null;
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

    private static String readFileAsUtf8(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ExceptionCreator.of(DocsErrorCode.UPLOAD_FILE_MISSING).throwIt();
            return null; // unreachable
        }
    }

    private static String deriveTitleFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Untitled";
        }
        String lower = filename.toLowerCase();
        String stripped = filename;
        if (lower.endsWith(".markdown")) {
            stripped = filename.substring(0, filename.length() - ".markdown".length());
        } else if (lower.endsWith(".md")) {
            stripped = filename.substring(0, filename.length() - ".md".length());
        }
        if (stripped.isBlank()) {
            return "Untitled";
        }
        return stripped;
    }
}
