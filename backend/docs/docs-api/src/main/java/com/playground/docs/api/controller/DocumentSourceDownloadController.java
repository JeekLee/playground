package com.playground.docs.api.controller;

import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.port.BlobStoragePort;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * M6.1 ADR-12 §A12.4 — original source-blob download endpoint.
 *
 * <p>Streams the MinIO {@code getObject} payload straight to the HTTP
 * response body. Visibility-aware (public → anyone, private → owner only).
 * Returns 404 (indistinguishable from missing) for non-author callers of
 * private docs and for documents whose source blob has been purged from
 * MinIO.
 *
 * <p>Content-Disposition: {@code attachment; filename="..."} where the
 * filename is derived from {@code source.{ext}} with the ext chosen from the
 * stored {@code mime_type}.
 */
@RestController
@RequestMapping
public class DocumentSourceDownloadController {

    private static final Logger log = LoggerFactory.getLogger(DocumentSourceDownloadController.class);

    private final DocumentAppService docService;
    private final BlobStoragePort blobStorage;

    public DocumentSourceDownloadController(
            DocumentAppService docService,
            BlobStoragePort blobStorage) {
        this.docService = docService;
        this.blobStorage = blobStorage;
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID documentId = parseDocumentId(id);
        UUID caller = parseOptionalUserId(userIdHeader);
        DocumentDetailDto detail = docService.getById(documentId, caller);
        if (detail == null || detail.id() == null) {
            ExceptionCreator.of(DocsErrorCode.DOCUMENT_NOT_FOUND, id).throwIt();
        }

        // The detail DTO is from a row that may pre-date M6.1 — no
        // source_object_key. Surface as 404 (no source available).
        // We need access to source_object_key + source_mime, which the
        // DocumentDetailDto doesn't currently expose. Issue an additional
        // lookup against the repository via the app service to keep the
        // wire DTO stable.
        var sourceMeta = docService.getSourceMeta(documentId, caller);
        if (sourceMeta == null || sourceMeta.objectKey() == null) {
            ExceptionCreator.of(DocsErrorCode.SOURCE_BLOB_NOT_FOUND, id).throwIt();
        }

        Optional<BlobStoragePort.BlobHandle> handleOpt = blobStorage.getObject(sourceMeta.objectKey());
        if (handleOpt.isEmpty()) {
            ExceptionCreator.of(DocsErrorCode.SOURCE_BLOB_NOT_FOUND, id).throwIt();
        }

        BlobStoragePort.BlobHandle handle = handleOpt.get();
        String contentType = sourceMeta.contentType() != null ? sourceMeta.contentType() : handle.contentType();
        String filename = "source." + (sourceMeta.isPdf() ? "pdf" : "md");

        StreamingResponseBody body = (OutputStream out) -> {
            try (InputStream in = handle.stream()) {
                in.transferTo(out);
            } catch (IOException e) {
                log.warn("MinIO stream copy interrupted for document {}: {}", documentId, e.toString());
                throw e;
            } finally {
                try {
                    handle.close();
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .attachment()
                .filename(filename)
                .build());
        if (handle.sizeBytes() >= 0) {
            headers.setContentLength(handle.sizeBytes());
        }
        MediaType media;
        try {
            media = MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            media = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(media)
                .body(body);
    }

    private static UUID parseDocumentId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.DOCUMENT_NOT_FOUND, id).throwIt();
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
}
