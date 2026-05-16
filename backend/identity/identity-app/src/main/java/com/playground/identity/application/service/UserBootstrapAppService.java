package com.playground.identity.application.service;

import com.playground.identity.application.dto.UserBootstrapCommand;
import com.playground.identity.application.dto.UserBootstrapResult;
import com.playground.identity.application.repository.UserRepository;
import com.playground.identity.domain.event.UserProfileUpdated;
import com.playground.identity.domain.event.UserRegistered;
import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.vo.AvatarUrl;
import com.playground.identity.domain.model.vo.DisplayName;
import com.playground.identity.domain.model.vo.Email;
import com.playground.identity.domain.model.vo.GoogleSub;
import com.playground.identity.domain.service.UserBootstrapService;
import com.playground.identity.domain.service.UserBootstrapService.BootstrapDecision;
import com.playground.identity.domain.service.UserBootstrapService.Created;
import com.playground.identity.domain.service.UserBootstrapService.ProfileDrift;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstrap use-case service per ADR-10 §4 + §7. Orchestrates the decision
 * (delegated to {@link UserBootstrapService}), the persistence write, and the
 * domain-event publication. All three happen inside a single
 * {@code @Transactional} boundary so Spring Modulith's JPA outbox persists the
 * event row atomically with the {@code users} row (ADR-10 §8).
 */
@Service
public class UserBootstrapAppService {

    private final UserRepository userRepository;
    private final UserBootstrapService userBootstrapService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public UserBootstrapAppService(
            UserRepository userRepository,
            UserBootstrapService userBootstrapService,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.userRepository = userRepository;
        this.userBootstrapService = userBootstrapService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public UserBootstrapResult bootstrap(UserBootstrapCommand command) {
        GoogleSub googleSub = GoogleSub.of(command.googleSub());
        Email email = Email.of(command.email());
        DisplayName displayName = DisplayName.of(command.displayName());
        AvatarUrl avatarUrl = AvatarUrl.of(command.avatarUrl());

        Optional<User> existing = userRepository.findByGoogleSub(googleSub);
        BootstrapDecision decision = userBootstrapService.decide(existing, googleSub, email, displayName, avatarUrl);

        User saved = userRepository.save(decision.next());
        Instant now = Instant.now(clock);

        if (decision instanceof Created) {
            eventPublisher.publishEvent(new UserRegistered(
                    saved.id(),
                    saved.googleSub(),
                    saved.email(),
                    saved.displayName(),
                    saved.avatarUrl(),
                    now));
            return new UserBootstrapResult(saved.id().value().toString(), true, false);
        }
        if (decision instanceof ProfileDrift drift) {
            List<String> changedFields = diffFields(drift.previous(), saved);
            eventPublisher.publishEvent(new UserProfileUpdated(
                    saved.id(),
                    changedFields,
                    saved.email(),
                    saved.displayName(),
                    saved.avatarUrl(),
                    now));
            return new UserBootstrapResult(saved.id().value().toString(), false, true);
        }
        return new UserBootstrapResult(saved.id().value().toString(), false, false);
    }

    private static List<String> diffFields(User previous, User next) {
        List<String> changed = new ArrayList<>();
        if (!previous.email().equals(next.email())) changed.add("email");
        if (!previous.displayName().equals(next.displayName())) changed.add("displayName");
        if (!previous.avatarUrl().equals(next.avatarUrl())) changed.add("avatarUrl");
        return changed;
    }
}
