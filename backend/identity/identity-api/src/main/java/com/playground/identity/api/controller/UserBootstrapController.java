package com.playground.identity.api.controller;

import com.playground.identity.api.request.UserBootstrapRequest;
import com.playground.identity.api.response.UserBootstrapResponse;
import com.playground.identity.application.service.UserBootstrapAppService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /users/bootstrap per ADR-10 §4 — invoked by the gateway after a
 * successful OAuth round-trip when the Google subject is not yet in Redis.
 * Idempotent: repeat calls with the same {@code googleSub} return the same
 * {@code id}.
 */
@RestController
@RequestMapping("/users")
public class UserBootstrapController {

    private final UserBootstrapAppService bootstrapService;

    public UserBootstrapController(UserBootstrapAppService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<UserBootstrapResponse> bootstrap(@Valid @RequestBody UserBootstrapRequest request) {
        var result = bootstrapService.bootstrap(request.toCommand());
        UserBootstrapResponse body = UserBootstrapResponse.from(result);
        return result.created() ? ResponseEntity.status(201).body(body) : ResponseEntity.ok(body);
    }
}
