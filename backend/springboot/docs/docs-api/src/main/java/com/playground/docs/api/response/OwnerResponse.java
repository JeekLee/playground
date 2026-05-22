package com.playground.docs.api.response;

import java.util.UUID;

/**
 * Response for {@code GET /api/docs/owner} per M2 spec §6.3.
 *
 * <p>{@code ownerUserId} is null when the {@code PLAYGROUND_OWNER_GOOGLE_SUB}
 * env var is unset / the identity-api lookup misses — fail-closed (the home's
 * "Latest documents" tile renders empty).
 */
public record OwnerResponse(UUID ownerUserId) {

    public static OwnerResponse of(UUID ownerUserId) {
        return new OwnerResponse(ownerUserId);
    }
}
