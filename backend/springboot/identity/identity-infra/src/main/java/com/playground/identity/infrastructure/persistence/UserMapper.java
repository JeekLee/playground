package com.playground.identity.infrastructure.persistence;

import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.AvatarUrl;
import com.playground.identity.domain.model.vo.DisplayName;
import com.playground.identity.domain.model.vo.Email;
import com.playground.identity.domain.model.vo.GoogleSub;

/**
 * Hand-written mapper per ADR-02 v2 (MapStruct rejected for M0–M5). Bridges
 * the {@link User} domain aggregate and the {@link UserJpaEntity} persistence
 * mirror.
 */
public final class UserMapper {

    private UserMapper() {}

    public static User toDomain(UserJpaEntity entity) {
        return new User(
                UserId.of(entity.getId()),
                GoogleSub.of(entity.getGoogleSub()),
                Email.of(entity.getEmail()),
                DisplayName.of(entity.getDisplayName()),
                AvatarUrl.of(entity.getAvatarUrl()),
                entity.getCreatedAt(),
                entity.getLastLoginAt());
    }

    public static UserJpaEntity toEntity(User user) {
        return new UserJpaEntity(
                user.id().value(),
                user.googleSub().value(),
                user.email().value(),
                user.displayName().value(),
                user.avatarUrl().value(),
                user.createdAt(),
                user.lastLoginAt());
    }

    /** Copy domain-mutable fields onto a managed entity (preserves JPA identity). */
    public static UserJpaEntity copyMutable(User source, UserJpaEntity managed) {
        managed.setEmail(source.email().value());
        managed.setDisplayName(source.displayName().value());
        managed.setAvatarUrl(source.avatarUrl().value());
        managed.setLastLoginAt(source.lastLoginAt());
        return managed;
    }
}
