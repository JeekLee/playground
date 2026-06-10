package com.playground.docs.api.controller;

import com.playground.docs.application.service.ViewIncrementService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the M2 S3 view-counter endpoint per spec §6.1 row
 * {@code POST /api/docs/{id}/view} + ADR-12 §10.
 *
 * <p>Auth optional: anonymous and authenticated callers go through the
 * same path (ADR-12 §10 "same-cookie path regardless of auth state"). The
 * dedup key is built from the {@code PLAYGROUND_ANON} cookie when present,
 * falling back to the {@code X-Forwarded-For} first hop. Private docs
 * return 204 no-op so the response is indistinguishable from a public
 * deduped hit (no visibility leak).
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class DocumentViewController {

    /** Cookie name pinned by ADR-10 §2 + the gateway's {@code AnonCookieFilter}. */
    private static final String ANON_COOKIE_NAME = "PLAYGROUND_ANON";

    private final ViewIncrementService viewService;

    @PostMapping("/{id}/view")
    public ResponseEntity<Void> view(
            @PathVariable("id") String id, HttpServletRequest request) {
        UUID documentId = parseDocumentId(id);
        String anonCookie = readAnonCookie(request);
        String clientIp = firstForwardedFor(request);
        viewService.increment(documentId, anonCookie, clientIp);
        return ResponseEntity.noContent().build();
    }

    private static String readAnonCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (ANON_COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * First hop of {@code X-Forwarded-For} (gateway → docs-api). The gateway
     * is configured with {@code forward-headers-strategy: framework} so
     * Spring's {@code HttpServletRequest#getRemoteAddr()} also resolves to
     * the Cloudflare-injected client IP — we prefer the explicit
     * {@code X-Forwarded-For} read to keep the contract explicit (and to
     * avoid surprises if a future reverse-proxy tier changes the resolution
     * order).
     */
    private static String firstForwardedFor(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = comma < 0 ? xff : xff.substring(0, comma);
            String trimmed = first.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return request.getRemoteAddr();
    }

    private static UUID parseDocumentId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.DOCUMENT_NOT_FOUND, id).throwIt();
            return null; // unreachable
        }
    }
}
