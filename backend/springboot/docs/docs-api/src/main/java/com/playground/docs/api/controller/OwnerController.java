package com.playground.docs.api.controller;

import com.playground.docs.api.response.OwnerResponse;
import com.playground.docs.application.service.OwnerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner resolution endpoint per M2 spec §6.3 + ADR-12 §8.
 *
 * <p>{@code GET /api/docs/owner} returns the resolved owner UUID
 * ({@code { ownerUserId: UUID }}) or {@code null} when the env var is unset
 * or the boot lookup against identity-api missed. Auth optional —
 * the frontend boots the home tile before login.
 */
@RestController
@RequiredArgsConstructor
public class OwnerController {

    private final OwnerService ownerService;

    @GetMapping("/owner")
    public ResponseEntity<OwnerResponse> owner() {
        return ResponseEntity.ok(OwnerResponse.of(ownerService.ownerUserId().orElse(null)));
    }
}
