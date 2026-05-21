package com.playground.docs.api.controller;

import com.playground.docs.api.request.CreateDocumentRequest;
import com.playground.docs.api.request.PatchDocumentRequest;
import com.playground.docs.api.response.DocListResponse;
import com.playground.docs.api.response.DocumentDetailResponse;
import com.playground.docs.api.response.MyDocumentListResponse;
import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.port.PdfExtractorPort;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.application.service.DocumentFeedService;
import com.playground.docs.domain.enums.MimeType;
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
    /**
     * M6 ADR-16 — optional PDF extractor port. Wired by docs-infra's
     * {@code PdfExtractorAdapter}; nullable so the M2 standalone-MockMvc
     * controller tests can construct the controller without a Spring AI
     * {@code ChatClient} on the classpath. When null and a PDF arrives the
     * controller throws {@link DocsErrorCode#INVALID_FILE_TYPE} as a
     * defense-in-depth backstop (the multipart filter chain is the real
     * gate — Spring DI resolves the bean in production).
     */
    private final PdfExtractorPort pdfExtractor;

    public DocumentController(DocumentAppService docService, DocumentFeedService feedService) {
        this(docService, feedService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentController(
            DocumentAppService docService,
            DocumentFeedService feedService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) PdfExtractorPort pdfExtractor) {
        this.docService = docService;
        this.feedService = feedService;
        this.pdfExtractor = pdfExtractor;
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
     * Multipart variant of {@code POST /api/docs} per M2 spec §6.1 + M6
     * ADR-16. Accepts:
     * <ul>
     *   <li>{@code .md} / {@code .markdown} file → contents become the document
     *       body verbatim (UTF-8 decoded).</li>
     *   <li>{@code .pdf} file → PDFBox extracts the text layer; pages with no
     *       text layer fall back to Spring AI Vision OCR
     *       ({@link PdfExtractorPort}). The resulting Markdown is stored in
     *       {@code documents.body}; M3 rag-ingestion sees a regular Markdown
     *       doc and needs zero code changes.</li>
     * </ul>
     *
     * <p>Validation gate (3-step, ADR-16):
     * <ol>
     *   <li>Filename suffix — {@code .md}, {@code .markdown}, or {@code .pdf}
     *       (case-insensitive).</li>
     *   <li>{@code Content-Type} — {@code text/markdown} or
     *       {@code application/pdf} (or absent, in which case the suffix-derived
     *       type wins — browsers occasionally drop the header for
     *       {@code .md}).</li>
     *   <li>For {@code .pdf}: PDF magic bytes ({@code %PDF-} = {@code 0x25 50
     *       44 46 2D}) at offset 0.</li>
     * </ol>
     *
     * <p>If {@code title} is absent the filename (sans extension) is used.
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
        // M6 ADR-16: 3-step input gate (filename → content-type → magic bytes).
        MimeType mimeType = detectMimeType(file);

        // Defense in depth: multipart cap (25 MB per ADR-16) is enforced by
        // spring.servlet.multipart.max-file-size — fail-fast here so we avoid
        // copying oversized bytes into memory only to fail downstream.
        if (file.getSize() > MULTIPART_MAX_FILE_SIZE) {
            ExceptionCreator.of(DocsErrorCode.FILE_TOO_LARGE).throwIt();
        }

        String body;
        if (mimeType == MimeType.PDF) {
            // Read raw bytes; PdfExtractorPort handles parse + Vision OCR.
            byte[] pdfBytes = readFileBytes(file);
            if (pdfExtractor == null) {
                // Defense-in-depth — production wiring always provides the
                // adapter, but the standalone-MockMvc tests construct the
                // controller with a 2-arg ctor and no PDF support.
                ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
            }
            body = pdfExtractor.extractToMarkdown(pdfBytes);
        } else {
            // Markdown path — UTF-8 decode the file contents directly.
            body = readFileAsUtf8(file);
        }

        // The DocumentBody VO enforces the 10 MB body cap (M6 ADR-16). PDFs
        // that explode past 10 MB after extraction surface as BODY_TOO_LARGE.
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > DocumentBody.MAX_OCTET_LENGTH) {
            ExceptionCreator.of(DocsErrorCode.BODY_TOO_LARGE).throwIt();
        }

        String resolvedTitle = (title == null || title.isBlank())
                ? deriveTitleFromFilename(file.getOriginalFilename())
                : title;
        var command = new CreateDocumentCommand(author, resolvedTitle, body, path, mimeType);
        var detail = docService.create(command);
        return ResponseEntity.status(201).body(DocumentDetailResponse.from(detail));
    }

    /** Multipart upload cap — 25 MB per ADR-16. */
    static final long MULTIPART_MAX_FILE_SIZE = 25L * 1_048_576L;

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
            // M2 S3: path filter is now supported (folder-pane right list);
            // author filter on mine-scope is still meaningless (caller is the
            // only author scope can resolve to).
            if (author != null) {
                ExceptionCreator.of(DocsErrorCode.SCOPE_FILTER_UNSUPPORTED).throwIt();
            }
            return ResponseEntity.ok(
                    MyDocumentListResponse.from(docService.listMine(caller, pathFilter)));
        }
        // ---- scope present but not "mine": invalid ----
        if (scope != null && !scope.isBlank()) {
            ExceptionCreator.of(DocsErrorCode.SCOPE_REQUIRED).throwIt();
        }
        UUID caller = parseOptionalUserId(userIdHeader);
        // ---- author=<uuid>: per-author public feed ----
        if (author != null && !author.isBlank()) {
            UUID authorId;
            try {
                authorId = UUID.fromString(author);
            } catch (IllegalArgumentException e) {
                ExceptionCreator.of(DocsErrorCode.AUTHOR_PARAM_INVALID).throwIt();
                return null; // unreachable
            }
            return ResponseEntity.ok(
                    DocListResponse.from(feedService.authorFeed(authorId, cursor, caller)));
        }
        // ---- default: community feed ----
        return ResponseEntity.ok(
                DocListResponse.from(feedService.communityFeed(cursor, caller)));
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

    private static byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            ExceptionCreator.of(DocsErrorCode.UPLOAD_FILE_MISSING).throwIt();
            return null; // unreachable
        }
    }

    /**
     * M6 ADR-16 — 3-step input gate. Surface {@link DocsErrorCode#INVALID_FILE_TYPE}
     * (400) on any mismatch.
     *
     * <ol>
     *   <li>Filename suffix (case-insensitive): {@code .md}, {@code .markdown},
     *       {@code .pdf}.</li>
     *   <li>{@code Content-Type}: {@code text/markdown}, {@code text/plain}
     *       (some browsers send it for .md), {@code application/pdf}, or
     *       {@code application/octet-stream} (fallback when the browser
     *       doesn't know the type — suffix wins). Must be consistent with
     *       the suffix.</li>
     *   <li>For {@code .pdf}: PDF magic bytes ({@code %PDF-}) at offset 0.</li>
     * </ol>
     */
    private static MimeType detectMimeType(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String lower = filename == null ? "" : filename.toLowerCase();
        boolean suffixPdf = lower.endsWith(".pdf");
        boolean suffixMd = lower.endsWith(".md") || lower.endsWith(".markdown");
        if (!suffixPdf && !suffixMd) {
            ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
        }

        String contentType = file.getContentType();
        if (suffixPdf) {
            // Step 2 — content type. application/pdf is canonical; allow
            // application/octet-stream (browsers without registered handlers)
            // and null (raw uploads from curl) — the magic-byte check in step 3
            // is what really gates the PDF path.
            if (contentType != null
                    && !"application/pdf".equalsIgnoreCase(contentType)
                    && !"application/octet-stream".equalsIgnoreCase(contentType)) {
                ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
            }
            // Step 3 — magic bytes %PDF- at offset 0.
            byte[] bytes = readFileBytes(file);
            if (bytes == null || bytes.length < 5
                    || bytes[0] != (byte) 0x25  // %
                    || bytes[1] != (byte) 0x50  // P
                    || bytes[2] != (byte) 0x44  // D
                    || bytes[3] != (byte) 0x46  // F
                    || bytes[4] != (byte) 0x2D) {  // -
                ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
            }
            return MimeType.PDF;
        }
        // Markdown branch — content type may be text/markdown, text/plain,
        // application/octet-stream, or null. Allow them all; reject only
        // when the type clearly indicates a different media (e.g.
        // application/pdf).
        if (contentType != null
                && !"text/markdown".equalsIgnoreCase(contentType)
                && !"text/plain".equalsIgnoreCase(contentType)
                && !"application/octet-stream".equalsIgnoreCase(contentType)
                && !contentType.toLowerCase().startsWith("text/")) {
            ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
        }
        return MimeType.MARKDOWN;
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
        } else if (lower.endsWith(".pdf")) {
            // M6 ADR-16 — PDF filename → title fallback strips the suffix too.
            stripped = filename.substring(0, filename.length() - ".pdf".length());
        }
        if (stripped.isBlank()) {
            return "Untitled";
        }
        return stripped;
    }
}
