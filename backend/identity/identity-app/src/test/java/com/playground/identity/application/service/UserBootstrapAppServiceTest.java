package com.playground.identity.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.identity.application.dto.UserBootstrapCommand;
import com.playground.identity.application.repository.UserRepository;
import com.playground.identity.domain.event.UserProfileUpdated;
import com.playground.identity.domain.event.UserRegistered;
import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.AvatarUrl;
import com.playground.identity.domain.model.vo.DisplayName;
import com.playground.identity.domain.model.vo.Email;
import com.playground.identity.domain.model.vo.GoogleSub;
import com.playground.identity.domain.service.UserBootstrapService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class UserBootstrapAppServiceTest {

    private static final Instant FIXED = Instant.parse("2026-05-17T00:00:00Z");

    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher publisher;

    UserBootstrapAppService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        UserBootstrapService domainService = new UserBootstrapService(clock);
        service = new UserBootstrapAppService(userRepository, domainService, publisher, clock);
    }

    @Test
    void first_login_inserts_row_and_publishes_user_registered() {
        when(userRepository.findByGoogleSub(any(GoogleSub.class))).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.bootstrap(
                new UserBootstrapCommand("1234", "a@b.com", "Alice", "https://x"));

        assertThat(result.created()).isTrue();
        assertThat(result.profileUpdated()).isFalse();
        verify(publisher, times(1)).publishEvent(any(UserRegistered.class));
        verify(publisher, never()).publishEvent(any(UserProfileUpdated.class));
    }

    @Test
    void second_login_with_same_profile_does_not_publish_events() {
        User existing = User.register(
                UserId.of(UUID.randomUUID()),
                GoogleSub.of("1234"),
                Email.of("a@b.com"),
                DisplayName.of("Alice"),
                AvatarUrl.of("https://x"),
                Instant.parse("2026-05-16T00:00:00Z"));
        when(userRepository.findByGoogleSub(any(GoogleSub.class))).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.bootstrap(
                new UserBootstrapCommand("1234", "a@b.com", "Alice", "https://x"));

        assertThat(result.created()).isFalse();
        assertThat(result.profileUpdated()).isFalse();
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void profile_drift_publishes_profile_updated_with_changed_fields() {
        User existing = User.register(
                UserId.of(UUID.randomUUID()),
                GoogleSub.of("1234"),
                Email.of("a@b.com"),
                DisplayName.of("Alice"),
                AvatarUrl.of("https://x"),
                Instant.parse("2026-05-16T00:00:00Z"));
        when(userRepository.findByGoogleSub(any(GoogleSub.class))).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.bootstrap(
                new UserBootstrapCommand("1234", "a@b.com", "Alice Renamed", "https://x"));

        assertThat(result.profileUpdated()).isTrue();
        ArgumentCaptor<UserProfileUpdated> captor = ArgumentCaptor.forClass(UserProfileUpdated.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().changedFields()).containsExactly("displayName");
    }
}
