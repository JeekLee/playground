package com.playground.docs.application.dto;

import java.util.UUID;

/**
 * Input record for the create-document use case. Mirrors M2 spec §6.4
 * {@code CreateDocRequest} plus the gateway-injected author id.
 *
 * <p>{@code body} and {@code path} are optional — the application service
 * normalizes nulls to {@code ""} and {@code "/"} respectively.
 */
public record CreateDocumentCommand(
        UUID authorId,
        String title,
        String body,
        String path) {
}
