package com.playground.docs.api.controller;

import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.domain.enums.ExtractionStatus;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.docs.api.sse.SseEmitterRegistry;
import com.playground.shared.error.ExceptionCreator;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * M6.1 ADR-12 §A12.5 — Server-Sent-Events stream for an in-flight document
 * extraction. Browsers subscribe via {@code EventSource(/api/docs/{id}/extraction-stream)}
 * and receive the extraction lifecycle: snapshot on connect, then
 * {@code extracting → extracted | failed}.
 *
 * <p>Auth: visibility-aware, same gate as {@code GET /api/docs/{id}}. Public
 * docs stream to anyone; private docs require the requester to be the
 * author.
 *
 * <p>The {@link SseEmitterRegistry} owns the per-document emitter sets +
 * the 30s keepalive ping. This controller delegates to the registry on
 * register / snapshot push and never touches the SseEmitter directly after
 * handing it back to Spring.
 */
@RestController
@RequestMapping
public class DocumentExtractionStreamController {

    private final DocumentAppService docService;
    private final SseEmitterRegistry registry;

    @Autowired
    public DocumentExtractionStreamController(
            DocumentAppService docService,
            SseEmitterRegistry registry) {
        this.docService = docService;
        this.registry = registry;
    }

    @GetMapping(
            value = "/{id}/extraction-stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @PathVariable("id") String id) {
        UUID documentId = parseDocumentId(id);
        UUID caller = parseOptionalUserId(userIdHeader);
        // getById enforces the visibility-or-ownership gate and throws
        // DocumentNotFoundException (404) on miss / private + non-author.
        DocumentDetailDto detail = docService.getById(documentId, caller);
        // Register first so the worker can broadcast into the registry
        // between snapshot and any subsequent transition.
        SseEmitter emitter = registry.register(documentId);
        ExtractionStatus status = ExtractionStatus.fromWire(detail.extractionStatus());
        registry.sendSnapshot(emitter, status, detail.extractionReason());
        return emitter;
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
