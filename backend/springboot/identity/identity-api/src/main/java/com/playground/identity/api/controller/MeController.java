package com.playground.identity.api.controller;

import com.playground.identity.api.response.MeResponse;
import com.playground.identity.application.service.MeService;
import com.playground.identity.domain.exception.IdentityErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /me — returns the current user's profile.
 *
 * <p>Reads {@code X-User-Id} injected by the gateway per ADR-07 + ADR-10 §2.
 * If absent (defense in depth — should never happen because /me is an
 * authenticated route), responds 401 via the shared exception hierarchy.
 */
@RestController
public class MeController {

    private final MeService meService;

    public MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            ExceptionCreator.of(IdentityErrorCode.USER_HEADER_MISSING).throwIt();
        }
        UUID userId;
        try {
            userId = UUID.fromString(userIdHeader);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(IdentityErrorCode.USER_HEADER_MISSING).throwIt();
            return null; // unreachable — throwIt() always throws
        }
        return ResponseEntity.ok(MeResponse.from(meService.findById(userId)));
    }
}
