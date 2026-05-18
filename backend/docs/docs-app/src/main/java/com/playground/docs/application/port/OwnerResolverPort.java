package com.playground.docs.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the "owner" user id from the {@code PLAYGROUND_OWNER_GOOGLE_SUB}
 * env var per M2 spec §6.3 + ADR-12 §8. The home page's
 * {@code Latest documents} section uses this id to filter the per-author
 * feed.
 *
 * <p>Implemented in docs-infra as {@code OwnerResolverAdapter} — calls
 * {@code GET /internal/users/by-google-sub/{sub}} on identity-api at boot.
 * Fail-closed: returns {@link Optional#empty()} when the env var is unset or
 * the lookup misses; the public feed renders empty.
 */
public interface OwnerResolverPort {

    Optional<UUID> ownerUserId();
}
