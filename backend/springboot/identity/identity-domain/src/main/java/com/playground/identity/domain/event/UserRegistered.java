package com.playground.identity.domain.event;

import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.AvatarUrl;
import com.playground.identity.domain.model.vo.DisplayName;
import com.playground.identity.domain.model.vo.Email;
import com.playground.identity.domain.model.vo.GoogleSub;
import java.time.Instant;

/**
 * Domain event for first-time registration. POJO record per ADR-02 v2 — no
 * Spring, no Jackson annotations. The -infra outbox externalizer wraps this in
 * the shared-kernel {@code EventEnvelope<T>} per ADR-03 before publishing.
 */
public record UserRegistered(
        UserId id,
        GoogleSub googleSub,
        Email email,
        DisplayName displayName,
        AvatarUrl avatarUrl,
        Instant registeredAt
) {}
