package com.playground.docs.infrastructure.external;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.playground.docs.application.dto.AuthorDto;
import com.playground.docs.application.port.IdentityLookupPort;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Calls {@code identity-api}'s {@code GET /internal/users/{id}} and
 * {@code GET /internal/users?ids=...} routes per ADR-12 §8. Caches resolved
 * authors in a short-TTL Caffeine cache so a 20-row feed page costs at most
 * one HTTP roundtrip + 20 cache reads (subsequent pages within the TTL hit
 * the cache).
 *
 * <p>Fail-soft on any HTTP error: an unreachable / slow identity-api surfaces
 * as an empty {@link Optional} (single lookup) or a partial map (batch lookup),
 * NOT as a thrown exception. The detail / feed responses render without the
 * author block; the controller does not 503 the whole page.
 *
 * <p>TTL: 5 minutes. M2 P0 accepts display-name drift per ADR-12 §12; the
 * cache TTL is the de-facto refresh interval.
 */
@Component
public class IdentityLookupAdapter implements IdentityLookupPort {

    private static final Logger log = LoggerFactory.getLogger(IdentityLookupAdapter.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_ENTRIES = 1_000;
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient identityWebClient;
    private final Cache<UUID, AuthorDto> cache;

    public IdentityLookupAdapter(@Qualifier("identityWebClient") WebClient identityWebClient) {
        this.identityWebClient = identityWebClient;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_ENTRIES)
                .build();
    }

    @Override
    public Optional<AuthorDto> findById(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        AuthorDto cached = cache.getIfPresent(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            UserDto response = identityWebClient.get()
                    .uri("/internal/users/{id}", userId)
                    .retrieve()
                    .bodyToMono(UserDto.class)
                    .blockOptional(BLOCK_TIMEOUT)
                    .orElse(null);
            if (response == null) {
                return Optional.empty();
            }
            AuthorDto author = response.toAuthor();
            cache.put(userId, author);
            return Optional.of(author);
        } catch (RuntimeException e) {
            log.warn("identity lookup failed for {} — falling back to null author", userId, e);
            return Optional.empty();
        }
    }

    @Override
    public Map<UUID, AuthorDto> findAllByIds(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AuthorDto> result = new HashMap<>();
        Set<UUID> misses = new HashSet<>();
        for (UUID id : userIds) {
            AuthorDto cached = cache.getIfPresent(id);
            if (cached != null) {
                result.put(id, cached);
            } else {
                misses.add(id);
            }
        }
        if (misses.isEmpty()) {
            return result;
        }
        try {
            String csv = misses.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
            UsersResponse response = identityWebClient.get()
                    .uri(uri -> uri.path("/internal/users").queryParam("ids", csv).build())
                    .retrieve()
                    .bodyToMono(UsersResponse.class)
                    .blockOptional(BLOCK_TIMEOUT)
                    .orElse(null);
            if (response != null && response.users() != null) {
                for (UserDto user : response.users()) {
                    AuthorDto author = user.toAuthor();
                    result.put(author.id(), author);
                    cache.put(author.id(), author);
                }
            }
        } catch (RuntimeException e) {
            log.warn("batch identity lookup failed (ids={}) — returning cache-only result", misses, e);
        }
        return result;
    }

    /** Wire-shape mirror of identity-api's {@code InternalUserController} single-user response. */
    public record UserDto(UUID id, String googleSub, String displayName, String avatarUrl) {
        AuthorDto toAuthor() {
            return new AuthorDto(id, displayName, avatarUrl);
        }
    }

    /** Wire-shape mirror of identity-api's batch response. */
    public record UsersResponse(List<UserDto> users) {
    }
}
