package com.playground.docs.api.controller;

import com.playground.docs.api.request.CreateDocumentRequest;
import com.playground.docs.api.request.PatchDocumentRequest;
import com.playground.docs.api.response.DocListResponse;
import com.playground.docs.api.response.DocumentDetailResponse;
import com.playground.docs.api.response.MyDocumentListResponse;
import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.application.port.BlobStoragePort;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.application.service.DocumentFeedService;
import com.playground.docs.domain.enums.MimeType;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
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
 * <p>M6.1 — multipart uploads stream straight to MinIO and dispatch the
 * extraction via the async path (Kafka in-BC topic
 * {@code docs.document.extraction-requested}). PDF + MD share the same MinIO
 * retention path; only the JSON {@code POST /api/docs} continues the
 * synchronous M2 lineage.
 *
 * <p>Auth contract preserved from M2. Mutations are owner-only and yield 404
 * on non-author access (tenant isolation per M2 spec §6.5).
 */
@RestController
@RequestMapping
public class DocumentController {

    private final DocumentAppService docService;
    private final DocumentFeedService feedService;
    /**
     * M6.1 — optional MinIO port. Wired by docs-infra's
     * {@code MinioBlobStorageAdapter}; nullable so the M2 standalone-MockMvc
     * controller tests can construct the controller without the SDK on the
     * classpath. When null and a multipart upload arrives, the legacy
     * synchronous markdown path is used (PDF rejected — there's no way to
     * complete extraction without retention).
     */
    private final BlobStoragePort blobStorage;

    public DocumentController(DocumentAppService docService, DocumentFeedService feedService) {
        this(docService, feedService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentController(
            DocumentAppService docService,
            DocumentFeedService feedService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BlobStoragePort blobStorage) {
        this.docService = docService;
        this.feedService = feedService;
        this.blobStorage = blobStorage;
    }

    /** Multipart upload cap — 25 MB per ADR-16, preserved in M6.1. */
    static final long MULTIPART_MAX_FILE_SIZE = 25L * 1_048_576L;

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
     * Multipart variant of {@code POST /api/docs}. M6.1 ADR-12 §A12.5:
     * <ul>
     *   <li>PDF — streams to MinIO, INSERTs row with
     *       {@code extraction_status='pending_extraction'}, fires
     *       {@code docs.document.extraction-requested}. The body is
     *       materialized asynchronously by {@code ExtractionWorkflow}.</li>
     *   <li>Markdown — streams to MinIO for the source-download endpoint,
     *       AND decodes inline so the response carries the body (no async
     *       hop needed). Status lands at {@code 'extracted'}.</li>
     * </ul>
     *
     * <p>3-step validation gate preserved:
     * <ol>
     *   <li>Filename suffix (case-insensitive): {@code .md}, {@code .markdown}, {@code .pdf}.</li>
     *   <li>Content-Type sanity (against suffix).</li>
     *   <li>For PDF: magic bytes {@code %PDF-} at offset 0 (without
     *       materializing the whole file).</li>
     * </ol>
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
        if (file.getSize() > MULTIPART_MAX_FILE_SIZE) {
            ExceptionCreator.of(DocsErrorCode.FILE_TOO_LARGE).throwIt();
        }

        // 3-step gate: filename suffix → content-type → (PDF only) magic bytes.
        MimeType mimeType = detectMimeTypeBySuffix(file);
        validateContentType(file, mimeType);

        String resolvedTitle = (title == null || title.isBlank())
                ? deriveTitleFromFilename(file.getOriginalFilename())
                : title;

        UUID documentId = UUID.randomUUID();
        String ext = mimeType == MimeType.PDF ? "pdf" : "md";
        String objectKey = documentId + "/source." + ext;

        if (blobStorage == null) {
            // No MinIO in test wiring — only the legacy markdown sync path
            // is available, and PDF cannot be ingested.
            if (mimeType == MimeType.PDF) {
                ExceptionCreator.of(DocsErrorCode.BLOB_STORAGE_UNAVAILABLE).throwIt();
            }
            // Fall back to the M2 sync MD path: decode bytes inline, no MinIO.
            String body = readFileAsUtf8(file);
            var cmd = new CreateDocumentCommand(author, resolvedTitle, body, path, mimeType, null, null, null);
            return ResponseEntity.status(201).body(DocumentDetailResponse.from(docService.create(cmd)));
        }

        try (InputStream raw = file.getInputStream()) {
            InputStream streamForUpload = raw;
            if (mimeType == MimeType.PDF) {
                // Magic-byte check without buffering the whole file — wrap in
                // a PushbackInputStream, peek 5 bytes, then unread for the
                // MinIO upload.
                PushbackInputStream pushback = new PushbackInputStream(raw, 5);
                byte[] header = new byte[5];
                int read = readFully(pushback, header);
                if (read < 5
                        || header[0] != (byte) 0x25
                        || header[1] != (byte) 0x50
                        || header[2] != (byte) 0x44
                        || header[3] != (byte) 0x46
                        || header[4] != (byte) 0x2D) {
                    ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
                }
                pushback.unread(header, 0, read);
                streamForUpload = pushback;
            }

            // Stream straight to MinIO inside the create transaction
            // boundary (DocumentAppService.create is @Transactional and the
            // MinIO PUT runs ON the calling thread, so a put failure throws
            // BLOB_STORAGE_UNAVAILABLE and the row insert never happens).
            String storedMime = file.getContentType() == null ? mimeType.wireValue() : file.getContentType();
            blobStorage.putObject(objectKey, streamForUpload, file.getSize(), storedMime);
        } catch (IOException e) {
            ExceptionCreator.of(DocsErrorCode.UPLOAD_FILE_MISSING).throwIt();
        }

        // Both PDF and MD go through the async-extraction path. Markdown
        // extraction is trivial (UTF-8 decode) but keeping a single code
        // path simplifies the SSE / detail-page UX.
        var command = new CreateDocumentCommand(
                author,
                resolvedTitle,
                null,
                path,
                mimeType,
                objectKey,
                file.getSize(),
                file.getContentType() == null ? mimeType.wireValue() : file.getContentType());
        // Override the generated id so the MinIO key + DB row line up.
        var detail = docService.createWithId(documentId, command);
        return ResponseEntity.status(201).body(DocumentDetailResponse.from(detail));
    }

    /**
     * Unified {@code GET /api/docs} per M2 spec §6.1 + §6.2.
     */
    @GetMapping(value = {"/", ""})
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "path", required = false) String pathFilter,
            @RequestParam(value = "cursor", required = false) String cursor) {
        if ("mine".equals(scope)) {
            UUID caller = requireUserId(userIdHeader);
            if (author != null) {
                ExceptionCreator.of(DocsErrorCode.SCOPE_FILTER_UNSUPPORTED).throwIt();
            }
            return ResponseEntity.ok(
                    MyDocumentListResponse.from(docService.listMine(caller, pathFilter)));
        }
        if (scope != null && !scope.isBlank()) {
            ExceptionCreator.of(DocsErrorCode.SCOPE_REQUIRED).throwIt();
        }
        UUID caller = parseOptionalUserId(userIdHeader);
        if (author != null && !author.isBlank()) {
            UUID authorId;
            try {
                authorId = UUID.fromString(author);
            } catch (IllegalArgumentException e) {
                ExceptionCreator.of(DocsErrorCode.AUTHOR_PARAM_INVALID).throwIt();
                return null;
            }
            return ResponseEntity.ok(
                    DocListResponse.from(feedService.authorFeed(authorId, cursor, caller)));
        }
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

    @PostMapping("/{id}/publish")
    public ResponseEntity<DocumentDetailResponse> publish(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID caller = requireUserId(userIdHeader);
        UUID documentId = parseDocumentId(id);
        var detail = docService.publish(documentId, caller);
        return ResponseEntity.ok(DocumentDetailResponse.from(detail));
    }

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
            return null;
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
            return null;
        }
    }

    private static String readFileAsUtf8(MultipartFile file) {
        try {
            return new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            ExceptionCreator.of(DocsErrorCode.UPLOAD_FILE_MISSING).throwIt();
            return null;
        }
    }

    private static int readFully(InputStream in, byte[] dst) throws IOException {
        int total = 0;
        while (total < dst.length) {
            int r = in.read(dst, total, dst.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static MimeType detectMimeTypeBySuffix(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String lower = filename == null ? "" : filename.toLowerCase();
        boolean suffixPdf = lower.endsWith(".pdf");
        boolean suffixMd = lower.endsWith(".md") || lower.endsWith(".markdown");
        if (!suffixPdf && !suffixMd) {
            ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
        }
        return suffixPdf ? MimeType.PDF : MimeType.MARKDOWN;
    }

    private static void validateContentType(MultipartFile file, MimeType mimeType) {
        String contentType = file.getContentType();
        if (mimeType == MimeType.PDF) {
            if (contentType != null
                    && !"application/pdf".equalsIgnoreCase(contentType)
                    && !"application/octet-stream".equalsIgnoreCase(contentType)) {
                ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
            }
            return;
        }
        if (contentType != null
                && !"text/markdown".equalsIgnoreCase(contentType)
                && !"text/plain".equalsIgnoreCase(contentType)
                && !"application/octet-stream".equalsIgnoreCase(contentType)
                && !contentType.toLowerCase().startsWith("text/")) {
            ExceptionCreator.of(DocsErrorCode.INVALID_FILE_TYPE).throwIt();
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
        } else if (lower.endsWith(".pdf")) {
            stripped = filename.substring(0, filename.length() - ".pdf".length());
        }
        if (stripped.isBlank()) {
            return "Untitled";
        }
        return stripped;
    }
}
