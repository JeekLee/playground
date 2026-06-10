package com.playground.chat.api.controller;

import com.playground.chat.application.port.BlobStoragePort;
import com.playground.chat.application.service.AttachmentDownloadService;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.id.AttachmentId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.error.ExceptionCreator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
 * Owner-only attachment download — {@code GET /api/chat/attachments/{id}}
 * per ADR-20 §D4. The gateway routes {@code /api/chat/**} with
 * {@code StripPrefix=2}, so this controller listens on {@code /attachments/{id}}.
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
 *
 * <p>A sibling {@code GET /attachments/{id}/preview} route streams the derived
 * .glb inline for the chat card's 3D viewer.
 */
@RestController
@RequestMapping("/attachments")
@RequiredArgsConstructor
public class AttachmentDownloadController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentDownloadController.class);

    private final AttachmentDownloadService downloadService;

    @GetMapping("/{id}")
    public Mono<ResponseEntity<byte[]>> download(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false) String xUserId) {

        if (xUserId == null || xUserId.isBlank()) {
            throw ExceptionCreator.of(ChatErrorCode.AUTH_REQUIRED).build();
        }
        AttachmentId attachmentId = parseAttachmentId(id);
        UserId caller = UserId.fromString(xUserId);

        return Mono.fromCallable(() -> buildResponse(attachmentId, caller))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Inline 3D preview — streams the .glb sibling the architecture BC uploads
     * next to each .3dm (design spec 2026-06-05-massing-glb-preview). Same
     * owner-only rule as {@link #download}; 415 for attachment types without
     * a preview representation, 404 when the .glb is absent.
     */
    @GetMapping("/{id}/preview")
    public Mono<ResponseEntity<byte[]>> preview(
            @PathVariable("id") String id,
            @RequestHeader(value = "X-User-Id", required = false) String xUserId) {

        if (xUserId == null || xUserId.isBlank()) {
            throw ExceptionCreator.of(ChatErrorCode.AUTH_REQUIRED).build();
        }
        AttachmentId attachmentId = parseAttachmentId(id);
        UserId caller = UserId.fromString(xUserId);

        return Mono.fromCallable(() -> buildPreviewResponse(attachmentId, caller))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static AttachmentId parseAttachmentId(String id) {
        try {
            return AttachmentId.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            // Malformed id reads the same as "not yours / not found".
            throw ExceptionCreator.of(ChatErrorCode.ATTACHMENT_NOT_FOUND).build();
        }
    }

    private ResponseEntity<byte[]> buildPreviewResponse(AttachmentId attachmentId, UserId caller) {
        AttachmentDownloadService.Download preview = downloadService.openPreview(attachmentId, caller);
        byte[] bytes;
        try (BlobStoragePort.BlobHandle handle = preview.handle();
                InputStream in = handle.stream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.warn("attachment_preview_stream_failed attachmentId=" + attachmentId + " reason=" + e);
            throw ExceptionCreator.of(ChatErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        }

        // Inline (no Content-Disposition) — <model-viewer> fetches this URL
        // directly; it is not a user-facing download.
        log.info("attachment_preview attachmentId=" + attachmentId + " userId=" + caller
                + " sizeBytes=" + bytes.length);
        return ResponseEntity.ok()
                .contentLength(bytes.length)
                .contentType(MediaType.parseMediaType("model/gltf-binary"))
                .body(bytes);
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
            throw ExceptionCreator.of(ChatErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
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
