package com.playground.ragchat.api.controller;

import com.playground.ragchat.application.port.BlobStoragePort;
import com.playground.ragchat.application.service.AttachmentDownloadService;
import com.playground.ragchat.domain.exception.RagChatErrorCode;
import com.playground.ragchat.domain.model.Attachment;
import com.playground.ragchat.domain.model.id.AttachmentId;
import com.playground.ragchat.domain.model.id.UserId;
import com.playground.shared.error.ExceptionCreator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Owner-only attachment download — {@code GET /api/rag/chat/attachments/{id}}
 * per ADR-20 §D4. The gateway routes {@code /api/rag/chat/**} with
 * {@code StripPrefix=3}, so this controller listens on {@code /attachments/{id}}.
 *
 * <p>Authenticated (gateway injects {@code X-User-Id}); owner-only — the
 * attachment's message owner must equal the caller, else 404 (tenant isolation,
 * ADR-14 §6.5). Streams the bytes from MinIO with {@code Content-Type =
 * attachment.contentType}, {@code Content-Length}, and an RFC-6266
 * {@code Content-Disposition} that survives Korean filenames
 * ({@code filename*=UTF-8''…} + ASCII fallback).
 *
 * <p>Attachments are small (≤ ~20 KB per ADR-20 §D2), so the blob is read into
 * a {@code byte[]} on a bounded-elastic worker rather than streamed via
 * DataBuffers — simpler, and well within the inline-artifact size budget.
 */
@RestController
@RequestMapping("/attachments")
public class AttachmentDownloadController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentDownloadController.class);

    private final AttachmentDownloadService downloadService;

    public AttachmentDownloadController(AttachmentDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<byte[]>> download(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false) String xUserId) {

        if (xUserId == null || xUserId.isBlank()) {
            throw ExceptionCreator.of(RagChatErrorCode.AUTH_REQUIRED).build();
        }
        AttachmentId attachmentId;
        try {
            attachmentId = AttachmentId.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            // Malformed id reads the same as "not yours / not found".
            throw ExceptionCreator.of(RagChatErrorCode.ATTACHMENT_NOT_FOUND).build();
        }
        UserId caller = UserId.fromString(xUserId);

        return Mono.fromCallable(() -> buildResponse(attachmentId, caller))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<byte[]> buildResponse(AttachmentId attachmentId, UserId caller) {
        AttachmentDownloadService.Download download = downloadService.open(attachmentId, caller);
        Attachment attachment = download.attachment();
        byte[] bytes;
        try (BlobStoragePort.BlobHandle handle = download.handle();
                InputStream in = handle.stream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.warn("attachment_stream_failed attachmentId=" + attachmentId + " reason=" + e);
            throw ExceptionCreator.of(RagChatErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        }

        HttpHeaders headers = new HttpHeaders();
        // RFC-6266 — Korean filenames are not latin-1; the framework's
        // ContentDisposition builder emits both the ASCII fallback and the
        // percent-encoded filename*=UTF-8'' form when given a charset.
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(attachment.filename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(bytes.length);
        MediaType media;
        try {
            media = MediaType.parseMediaType(attachment.contentType());
        } catch (Exception e) {
            media = MediaType.APPLICATION_OCTET_STREAM;
        }
        log.info("attachment_download attachmentId=" + attachmentId + " userId=" + caller
                + " sizeBytes=" + bytes.length);
        return ResponseEntity.ok().headers(headers).contentType(media).body(bytes);
    }
}
