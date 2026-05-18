package com.playground.docs.api.controller;

import com.playground.docs.api.response.FolderListResponse;
import com.playground.docs.application.service.FolderListService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the M2 S3 folder-list endpoint per spec §6.1 row
 * {@code GET /api/docs/folders} + §6.4 {@code FolderListItem}.
 *
 * <p>Auth required (X-User-Id). The downstream
 * {@link FolderListService} scopes the query by the caller's {@code user_id},
 * so user A never observes user B's paths (tenant isolation, spec §10).
 */
@RestController
@RequestMapping("/folders")
public class FolderController {

    private final FolderListService folderService;

    public FolderController(FolderListService folderService) {
        this.folderService = folderService;
    }

    @GetMapping
    public ResponseEntity<FolderListResponse> listFolders(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        UUID caller = requireUserId(userIdHeader);
        return ResponseEntity.ok(FolderListResponse.from(folderService.listFolders(caller)));
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
}
