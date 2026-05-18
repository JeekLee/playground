package com.playground.identity.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data adapter for {@link UserJpaEntity}. */
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    Optional<UserJpaEntity> findByGoogleSub(String googleSub);

    /**
     * Batch fetch for docs-api's {@code GET /internal/users?ids=...} call per
     * ADR-12 §8 — used to resolve author display names + avatars across a
     * community feed page in one HTTP roundtrip.
     */
    List<UserJpaEntity> findAllByIdIn(Collection<UUID> ids);
}
