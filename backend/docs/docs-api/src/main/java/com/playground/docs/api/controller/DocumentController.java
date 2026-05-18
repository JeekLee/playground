package com.playground.docs.api.controller;

import com.playground.docs.api.request.CreateDocumentRequest;
import com.playground.docs.api.request.PatchDocumentRequest;
import com.playground.docs.api.response.DocumentDetailResponse;
import com.playground.docs.api.response.MyDocumentListResponse;
import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.service.DocumentAppService;
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
 * REST controller for the M2 S1 docs CRUD slice per spec §6.1.
 *
 * <p>Paths reflect the post-gateway-strip view: the gateway routes
 * {@code /api/docs/**} → {@code docs-api} with {@code StripPrefix=2}, so the
 * upstream service sees {@code /}, {@code /{id}}, {@code /{id}/publish} etc.
 *
 * <p>Auth contract (per M2 spec §6.1 + ADR-09 amendment):
 * <ul>
 *   <li>{@link #create} / {@link #createMultipart} / {@link #listMine} /
 *       {@link #patch} / {@link #publish} / {@link #unpublish} /
 *       {@link #delete} — {@code X-User-Id} required; missing → 401 via shared
 *       exception hierarchy.</li>
 *   <li>{@link #getById} — {@code X-User-Id} optional; visibility-or-ownership
 *       gate enforced inside {@link DocumentAppService#getById}.</li>
 * </ul>
 *
 * <p>Per M2 spec §6.5 + §10 "Tenant isolation" non-author callers attempting to
 * mutate (or read a private) document receive 404, never 403 — leaking
 * existence by status defeats tenant isolation.
 *
 * <p>S1 scope: {@code GET /api/docs} accepts only {@code ?scope=mine}. The
 * community feed ({@code GET /api/docs} with no scope, or {@code ?author=...},
 * or {@code ?scope=mine&path=...}) lands in M2 S2.
 */
@RestController
@RequestMapping
public class DocumentController {

    private final DocumentAppService docService;

    public DocumentController(DocumentAppService docService) {
        this.docService = docService;
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
     * {@code GET /api/docs?scope=mine} per M2 spec §6.1 + §6.2 ({@code GET
     * /api/docs/mine} is explicitly removed). S1 only accepts the bare
     * {@code ?scope=mine}; combinations with {@code path=} (folder filter) and
     * the community feed ({@code scope} absent or other) land in M2 S2.
     */
    @GetMapping(value = {"/", ""})
    public ResponseEntity<MyDocumentListResponse> listMine(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "path", required = false) String pathFilter) {
        UUID caller = requireUserId(userIdHeader);
        if (scope == null || !"mine".equals(scope)) {
            ExceptionCreator.of(DocsErrorCode.SCOPE_REQUIRED).throwIt();
        }
        if (author != null || pathFilter != null) {
            ExceptionCreator.of(DocsErrorCode.SCOPE_FILTER_UNSUPPORTED).throwIt();
        }
        return ResponseEntity.ok(MyDocumentListResponse.from(docService.listMine(caller)));
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
            // A malformed X-User-Id header on an auth-optional route is treated as
            // anonymous — defense in depth. We do not 400 the read because the
            // gateway is supposed to vouch for the header.
            return null;
        }
    }

    private static UUID parseDocumentId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            // A malformed id maps to "no such document" per M2 spec §6.5 — never
            // leak that we even parsed the URL.
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

    /**
     * Derive a title from the original filename when the multipart upload omits
     * an explicit {@code title} part. Strips {@code .md} or {@code .markdown}
     * (case-insensitive) extensions; falls back to a generic placeholder when
     * the filename is itself absent — the application service's blank-title
     * check will still reject empty input.
     */
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
