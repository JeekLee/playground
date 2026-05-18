package com.playground.docs.application.port;

import com.playground.docs.application.dto.AuthorDto;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only port for identity-api's {@code /internal/users/**} routes per
 * ADR-12 §8 — sanctioned cross-BC HTTP exception to the ADR-08 "Kafka-only"
 * rule. Used by the docs application service to resolve the author block on
 * detail / list / search responses.
 *
 * <p>Implemented in docs-infra as {@code IdentityLookupAdapter} with a
 * short-TTL Caffeine cache (M2 P0 accepts author display-name drift per
 * ADR-12 §12 — the cache stays warm for ~5 minutes and refreshes lazily).
 *
 * <p>Both methods return absent / empty results when the lookup misses or the
 * identity-api is unreachable — the caller renders a fallback display name
 * rather than 503'ing the entire response. The OpenSearch projector and the
 * detail-response composition both follow this fail-soft contract.
 */
public interface IdentityLookupPort {

    /**
     * Resolve a single user. {@link Optional#empty()} on miss / network
     * failure.
     */
    Optional<AuthorDto> findById(UUID userId);

    /**
     * Resolve a batch of users. Returns a map keyed by {@link UUID}; missing
     * ids are simply absent from the map (no entry, not a null value).
     * Used by the community feed and per-author feed to amortize identity
     * lookups across a page's distinct author set.
     */
    Map<UUID, AuthorDto> findAllByIds(Collection<UUID> userIds);
}
