package com.playground.identity.infrastructure.persistence;

import com.playground.identity.application.repository.UserRepository;
import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.GoogleSub;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** JPA-backed adapter satisfying {@link UserRepository}. */
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public UserRepositoryImpl(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<User> findByGoogleSub(GoogleSub googleSub) {
        return jpaRepository.findByGoogleSub(googleSub.value()).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findById(UserId id) {
        return jpaRepository.findById(id.value()).map(UserMapper::toDomain);
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = jpaRepository.findById(user.id().value())
                .map(managed -> UserMapper.copyMutable(user, managed))
                .orElseGet(() -> UserMapper.toEntity(user));
        UserJpaEntity persisted = jpaRepository.save(entity);
        return UserMapper.toDomain(persisted);
    }
}
