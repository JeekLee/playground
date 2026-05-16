package com.playground.identity.application.repository;

import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.GoogleSub;
import java.util.Optional;

/**
 * Repository port per ADR-02 v2 (port placement = application layer, return
 * types = domain types). The JPA-backed implementation lives in
 * {@code identity-infra} as {@code UserRepositoryImpl}.
 */
public interface UserRepository {

    Optional<User> findByGoogleSub(GoogleSub googleSub);

    Optional<User> findById(UserId id);

    User save(User user);
}
