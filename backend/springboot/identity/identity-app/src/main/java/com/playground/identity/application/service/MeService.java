package com.playground.identity.application.service;

import com.playground.identity.application.dto.UserDto;
import com.playground.identity.application.repository.UserRepository;
import com.playground.identity.domain.exception.UserNotFoundException;
import com.playground.identity.domain.model.id.UserId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use-case service for {@code GET /me}. */
@Service
@RequiredArgsConstructor
public class MeService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserDto findById(UUID userId) {
        UserId id = UserId.of(userId);
        return userRepository.findById(id).map(UserDto::from).orElseThrow(() -> new UserNotFoundException(id));
    }
}
