package com.playground.identity.infrastructure.persistence;

import com.playground.identity.application.repository.UserRepository;
import com.playground.identity.domain.model.User;
import com.playground.identity.domain.model.id.UserId;
import com.playground.identity.domain.model.vo.GoogleSub;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    public List<User> findAllByIds(Collection<UserId> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UUID> uuids = ids.stream().map(UserId::value).toList();
        return jpaRepository.findAllByIdIn(uuids).stream()
                .map(UserMapper::toDomain)
                .toList();
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
