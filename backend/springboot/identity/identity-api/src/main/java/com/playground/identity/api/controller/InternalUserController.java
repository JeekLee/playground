package com.playground.identity.api.controller;

import com.playground.identity.api.response.InternalUserResponse;
import com.playground.identity.api.response.InternalUsersResponse;
import com.playground.identity.application.service.InternalUserService;
import com.playground.identity.domain.exception.IdentityErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal user lookup routes per ADR-12 §8 + M2 S2 brief.
 *
 * <p>Three routes, all reachable only on the compose-internal network (the
 * gateway has no public allowlist for {@code /internal/**}):
 * <ul>
 *   <li>{@code GET /internal/users/by-google-sub/{sub}} — docs-api owner
 *       resolution at boot (M2 spec §6.3).</li>
 *   <li>{@code GET /internal/users/{id}} — single-user author lookup.</li>
 *   <li>{@code GET /internal/users?ids=a,b,c} — batch author lookup for
 *       community feed pages.</li>
 * </ul>
 *
 * <p>No {@code X-User-*} headers; intra-network service-to-service traffic
 * does not carry a user identity. Read-only — no PATCH / POST verbs live
 * here. Visibility filtering is the caller's concern, not ours.
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final InternalUserService internalUserService;

    @GetMapping("/by-google-sub/{sub}")
    public ResponseEntity<InternalUserResponse> byGoogleSub(@PathVariable("sub") String sub) {
        return ResponseEntity.ok(InternalUserResponse.from(
                internalUserService.findByGoogleSub(sub)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InternalUserResponse> byId(@PathVariable("id") String id) {
        UUID userId;
        try {
            userId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(IdentityErrorCode.USER_NOT_FOUND, id).throwIt();
            return null; // unreachable
        }
        return ResponseEntity.ok(InternalUserResponse.from(internalUserService.findById(userId)));
    }

    @GetMapping
    public ResponseEntity<InternalUsersResponse> batch(@RequestParam("ids") String ids) {
        if (ids == null || ids.isBlank()) {
            return ResponseEntity.ok(new InternalUsersResponse(List.of()));
        }
        List<UUID> userIds = new ArrayList<>();
        for (String token : Arrays.stream(ids.split(",")).map(String::trim).toList()) {
            if (token.isEmpty()) continue;
            try {
                userIds.add(UUID.fromString(token));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed ids — batch lookup is best-effort; the caller
                // already tolerates missing entries.
            }
        }
        var users = internalUserService.findAllByIds(userIds);
        return ResponseEntity.ok(InternalUsersResponse.from(users));
    }
}
