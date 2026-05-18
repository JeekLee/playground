package com.playground.ragchat.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request body for {@code POST /api/rag/chat} per spec §5.1. */
public record ChatTurnRequestBody(
        @NotNull UUID sessionId,
        @NotBlank String message) {}
