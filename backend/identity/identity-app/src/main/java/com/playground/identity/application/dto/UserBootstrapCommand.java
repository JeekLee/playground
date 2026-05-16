package com.playground.identity.application.dto;

/**
 * Use-case input for the bootstrap flow per ADR-10 §4. Strings on the wire,
 * converted to domain VOs inside the application service.
 */
public record UserBootstrapCommand(String googleSub, String email, String displayName, String avatarUrl) {}
