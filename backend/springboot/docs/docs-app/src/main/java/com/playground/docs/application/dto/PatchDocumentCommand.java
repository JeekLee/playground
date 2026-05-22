package com.playground.docs.application.dto;

import java.util.UUID;

/**
 * Input record for the patch-document use case. Mirrors M2 spec §6.4
 * {@code PatchDocRequest} ({@code title?}, {@code body?}) plus the
 * gateway-injected author id (used for the tenant-isolation check) and the
 * document id parsed from the URL.
 *
 * <p>Every payload field is nullable — {@code null} means "leave unchanged".
 * Per spec §6.1 PATCH carries no {@code visibility} or {@code path} fields:
 * publish / unpublish flow through the dedicated
 * {@code POST /api/docs/{id}/publish} + {@code /unpublish} endpoints; folder
 * moves land in M2.1 as a separate {@code POST /api/docs/{id}/move} endpoint.
 */
public record PatchDocumentCommand(
        UUID documentId,
        UUID callerId,
        String title,
        String body) {
}
