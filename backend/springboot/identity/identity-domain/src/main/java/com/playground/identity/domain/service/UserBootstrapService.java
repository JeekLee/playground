package com.playground.identity.domain.service;

import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.AvatarUrl;
import com.playground.identity.domain.model.vo.DisplayName;
import com.playground.identity.domain.model.vo.Email;
import com.playground.identity.domain.model.vo.GoogleSub;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Pure-domain decision service for the bootstrap flow per ADR-10 §7. Given the
 * Google-supplied profile fields and the currently-persisted {@link User}
 * (if any), it produces a {@link BootstrapDecision} describing what the
 * application layer should write + publish.
 *
 * <p>VIA-compromise per ADR-02 v2: {@code @Service} is permitted on stateless
 * domain orchestrators — this class holds no state and depends only on a
 * {@link Clock}.
 */
@Service
@RequiredArgsConstructor
public class UserBootstrapService {

    private final Clock clock;

    public BootstrapDecision decide(
            Optional<User> existing,
            GoogleSub googleSub,
            Email email,
            DisplayName displayName,
            AvatarUrl avatarUrl) {
        Instant now = Instant.now(clock);
        if (existing.isEmpty()) {
            User created = User.register(UserId.of(UUID.randomUUID()), googleSub, email, displayName, avatarUrl, now);
            return BootstrapDecision.created(created);
        }
        User current = existing.get();
        if (current.profileDiffers(email, displayName, avatarUrl)) {
            User updated = current
                    .applyGoogleProfile(email, displayName, avatarUrl == null ? AvatarUrl.EMPTY : avatarUrl)
                    .touchLastLogin(now);
            return BootstrapDecision.profileDrift(updated, current);
        }
        return BootstrapDecision.touched(current.touchLastLogin(now));
    }

    /** Outcome of {@link #decide(Optional, GoogleSub, Email, DisplayName, AvatarUrl)}. */
    public sealed interface BootstrapDecision permits Created, ProfileDrift, Touched {

        User next();

        static BootstrapDecision created(User user) {
            return new Created(user);
        }

        static BootstrapDecision profileDrift(User next, User previous) {
            return new ProfileDrift(next, previous);
        }

        static BootstrapDecision touched(User user) {
            return new Touched(user);
        }
    }

    public record Created(User next) implements BootstrapDecision {}

    public record ProfileDrift(User next, User previous) implements BootstrapDecision {}

    public record Touched(User next) implements BootstrapDecision {}
}
