package com.playground.identity.application.service;

import com.playground.identity.application.dto.InternalUserDto;
import com.playground.identity.application.repository.UserRepository;
import com.playground.identity.domain.exception.UserNotFoundException;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.GoogleSub;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only use-case service backing the {@code /internal/users/**} routes
 * (ADR-12 §8). Lives in identity-app per ADR-02 v2 layering; the controller
 * in identity-api routes by ID / google-sub / batch into the same service.
 *
 * <p>The routes are reachable only on the compose-internal network — the
 * gateway does not forward {@code /internal/**}. Callers in M2 are
 * {@code docs-api} (owner boot resolution + author lookups for the feed
 * and detail responses).
 */
@Service
@RequiredArgsConstructor
public class InternalUserService {

    private final UserRepository repository;

    /**
     * Single-user lookup by internal UUID. Throws {@link UserNotFoundException}
     * (404 via the shared exception hierarchy) when the id is unknown.
     */
    @Transactional(readOnly = true)
    public InternalUserDto findById(UUID userId) {
        UserId id = UserId.of(userId);
        return repository.findById(id)
                .map(InternalUserDto::from)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    /**
     * Lookup by Google sub. Used by docs-api at boot to resolve the owner
     * user id per M2 spec §6.3. Throws {@link UserNotFoundException} (404)
     * when the sub has never logged in.
     */
    @Transactional(readOnly = true)
    public InternalUserDto findByGoogleSub(String googleSub) {
        GoogleSub sub = GoogleSub.of(googleSub);
        return repository.findByGoogleSub(sub)
                .map(InternalUserDto::from)
                .orElseThrow(() -> new UserNotFoundException("googleSub=" + googleSub));
    }

    /**
     * Batch lookup by ids. Returns the resolved users; missing ids are silently
     * omitted (the controller wraps the list in
     * {@code { users: [...] }}). Used by docs-api to amortize author lookups
     * across a community feed page.
     */
    @Transactional(readOnly = true)
    public List<InternalUserDto> findAllByIds(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<UserId> ids = userIds.stream().map(UserId::of).toList();
        return repository.findAllByIds(ids).stream()
                .map(InternalUserDto::from)
                .toList();
    }
}
