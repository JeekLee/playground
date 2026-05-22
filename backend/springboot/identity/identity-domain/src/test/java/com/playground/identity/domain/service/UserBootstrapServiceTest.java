package com.playground.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.AvatarUrl;
import com.playground.identity.domain.model.vo.DisplayName;
import com.playground.identity.domain.model.vo.Email;
import com.playground.identity.domain.model.vo.GoogleSub;
import com.playground.identity.domain.service.UserBootstrapService.Created;
import com.playground.identity.domain.service.UserBootstrapService.ProfileDrift;
import com.playground.identity.domain.service.UserBootstrapService.Touched;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserBootstrapServiceTest {

    private static final Instant FIXED = Instant.parse("2026-05-17T00:00:00Z");
    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    private final UserBootstrapService service = new UserBootstrapService(clock);

    @Test
    void decide_creates_when_existing_is_empty() {
        var decision = service.decide(
                Optional.empty(),
                GoogleSub.of("1234"),
                Email.of("a@b.com"),
                DisplayName.of("Alice"),
                AvatarUrl.of("https://x"));

        assertThat(decision).isInstanceOf(Created.class);
        assertThat(decision.next().createdAt()).isEqualTo(FIXED);
        assertThat(decision.next().lastLoginAt()).isEqualTo(FIXED);
    }

    @Test
    void decide_emits_drift_when_displayName_changes() {
        User existing = User.register(
                UserId.of(UUID.randomUUID()),
                GoogleSub.of("1234"),
                Email.of("a@b.com"),
                DisplayName.of("Alice"),
                AvatarUrl.of("https://x"),
                Instant.parse("2026-05-16T00:00:00Z"));

        var decision = service.decide(
                Optional.of(existing),
                GoogleSub.of("1234"),
                Email.of("a@b.com"),
                DisplayName.of("Alice Renamed"),
                AvatarUrl.of("https://x"));

        assertThat(decision).isInstanceOf(ProfileDrift.class);
        assertThat(decision.next().displayName().value()).isEqualTo("Alice Renamed");
        assertThat(decision.next().lastLoginAt()).isEqualTo(FIXED);
        assertThat(decision.next().createdAt()).isEqualTo(existing.createdAt());
    }

    @Test
    void decide_returns_touched_when_no_drift() {
        User existing = User.register(
                UserId.of(UUID.randomUUID()),
                GoogleSub.of("1234"),
                Email.of("a@b.com"),
                DisplayName.of("Alice"),
                AvatarUrl.of("https://x"),
                Instant.parse("2026-05-16T00:00:00Z"));

        var decision = service.decide(
                Optional.of(existing),
                GoogleSub.of("1234"),
                Email.of("a@b.com"),
                DisplayName.of("Alice"),
                AvatarUrl.of("https://x"));

        assertThat(decision).isInstanceOf(Touched.class);
        assertThat(decision.next().lastLoginAt()).isEqualTo(FIXED);
    }
}
