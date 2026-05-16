package com.playground.identity.domain.event;

import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.AvatarUrl;
import com.playground.identity.domain.model.vo.DisplayName;
import com.playground.identity.domain.model.vo.Email;
import java.time.Instant;
import java.util.List;

/**
 * Domain event for profile-field drift on a subsequent login. {@code changedFields}
 * is informational per ADR-10 §8 — consumers read the full current state from
 * the payload, not from the delta.
 */
public record UserProfileUpdated(
        UserId id,
        List<String> changedFields,
        Email email,
        DisplayName displayName,
        AvatarUrl avatarUrl,
        Instant updatedAt
) {}
