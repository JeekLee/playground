package com.playground.identity.application.repository;

import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.GoogleSub;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository port per ADR-02 v2 (port placement = application layer, return
 * types = domain types). The JPA-backed implementation lives in
 * {@code identity-infra} as {@code UserRepositoryImpl}.
 */
public interface UserRepository {

    Optional<User> findByGoogleSub(GoogleSub googleSub);

    Optional<User> findById(UserId id);

    /**
     * Batch fetch users by id — used by docs-api's
     * {@code GET /internal/users?ids=...} route per ADR-12 §8 + M2 S2 author
     * resolution. Returns the rows that exist; missing ids are silently
     * omitted (no null entries in the list).
     */
    List<User> findAllByIds(Collection<UserId> ids);

    User save(User user);
}
