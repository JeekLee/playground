package com.playground.docs.application.dto;

import java.util.UUID;

/**
 * Author block surfaced on public document responses per M2 spec §6.4
 * {@code Author}. Resolved at request time via the identity-api
 * {@code /internal/users/**} routes (ADR-12 §8) and cached in a short-TTL
 * Caffeine cache by the infra adapter.
 *
 * @param id           identity.users.id
 * @param displayName  identity.users.display_name (at lookup time)
 * @param avatarUrl    identity.users.avatar_url (nullable)
 */
public record AuthorDto(UUID id, String displayName, String avatarUrl) {
}
