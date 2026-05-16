package com.playground.identity.application.dto;

/**
 * Use-case output for the bootstrap flow. The boolean flags let the gateway
 * (and tests) know whether a new row was created and/or a profile-drift event
 * was published — neither is part of the {@code POST /users/bootstrap}
 * response per ADR-10 §4, which only returns {@code id}, but the application
 * service exposes both for observability and test assertions.
 *
 * @param id              internal user UUID
 * @param created         true if a new row was inserted
 * @param profileUpdated  true if profile fields drifted on the existing row
 */
public record UserBootstrapResult(String id, boolean created, boolean profileUpdated) {}
