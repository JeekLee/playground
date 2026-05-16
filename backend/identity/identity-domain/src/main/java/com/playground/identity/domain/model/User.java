package com.playground.identity.domain.model;

import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.AvatarUrl;
import com.playground.identity.domain.model.vo.DisplayName;
import com.playground.identity.domain.model.vo.Email;
import com.playground.identity.domain.model.vo.GoogleSub;
import java.time.Instant;
import java.util.Objects;

/**
 * The {@code User} aggregate root. POJO per ADR-02 v2 — no Spring, no JPA, no
 * Jackson annotations. The {@link com.playground.identity.infrastructure
 * .persistence.UserJpaEntity} mirror lives in -infra; the hand-written
 * {@code UserMapper} bridges them.
 *
 * <p>Mutations follow the aggregate-root pattern: {@link #applyGoogleProfile}
 * and {@link #touchLastLogin} return new instances rather than mutating in
 * place, because cross-cutting code (event publishing, persistence) needs to
 * compare old vs new state to decide whether to emit
 * {@code identity.user.profile-updated}.
 */
public final class User {

    private final UserId id;
    private final GoogleSub googleSub;
    private final Email email;
    private final DisplayName displayName;
    private final AvatarUrl avatarUrl;
    private final Instant createdAt;
    private final Instant lastLoginAt;

    public User(
            UserId id,
            GoogleSub googleSub,
            Email email,
            DisplayName displayName,
            AvatarUrl avatarUrl,
            Instant createdAt,
            Instant lastLoginAt) {
        this.id = Objects.requireNonNull(id, "User.id must not be null");
        this.googleSub = Objects.requireNonNull(googleSub, "User.googleSub must not be null");
        this.email = Objects.requireNonNull(email, "User.email must not be null");
        this.displayName = Objects.requireNonNull(displayName, "User.displayName must not be null");
        this.avatarUrl = avatarUrl == null ? AvatarUrl.EMPTY : avatarUrl;
        this.createdAt = Objects.requireNonNull(createdAt, "User.createdAt must not be null");
        this.lastLoginAt = Objects.requireNonNull(lastLoginAt, "User.lastLoginAt must not be null");
    }

    /** Factory for the first login — generates a fresh UserId and stamps both timestamps. */
    public static User register(
            UserId id,
            GoogleSub googleSub,
            Email email,
            DisplayName displayName,
            AvatarUrl avatarUrl,
            Instant now) {
        return new User(id, googleSub, email, displayName, avatarUrl, now, now);
    }

    public UserId id() {
        return id;
    }

    public GoogleSub googleSub() {
        return googleSub;
    }

    public Email email() {
        return email;
    }

    public DisplayName displayName() {
        return displayName;
    }

    public AvatarUrl avatarUrl() {
        return avatarUrl;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastLoginAt() {
        return lastLoginAt;
    }

    /** Returns a new aggregate with the Google-supplied fields updated where they differ. */
    public User applyGoogleProfile(Email email, DisplayName displayName, AvatarUrl avatarUrl) {
        return new User(
                this.id,
                this.googleSub,
                email,
                displayName,
                avatarUrl == null ? AvatarUrl.EMPTY : avatarUrl,
                this.createdAt,
                this.lastLoginAt);
    }

    /** Returns a new aggregate with {@link #lastLoginAt} updated to the supplied instant. */
    public User touchLastLogin(Instant when) {
        Objects.requireNonNull(when, "touchLastLogin.when must not be null");
        return new User(this.id, this.googleSub, this.email, this.displayName, this.avatarUrl, this.createdAt, when);
    }

    /** True if Google-supplied profile fields differ from the supplied snapshot. */
    public boolean profileDiffers(Email email, DisplayName displayName, AvatarUrl avatarUrl) {
        AvatarUrl normalized = avatarUrl == null ? AvatarUrl.EMPTY : avatarUrl;
        return !this.email.equals(email)
                || !this.displayName.equals(displayName)
                || !this.avatarUrl.equals(normalized);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
