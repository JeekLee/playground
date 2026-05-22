package com.playground.ragchat.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.playground.ragchat.domain.tool.ToolErrorCode;
import java.util.Objects;

/**
 * Sealed result type returned by {@link ToolDispatcherPort#invoke}.
 *
 * <p>Discriminated by the two concrete records:
 * <ul>
 *   <li>{@link Success} — tool BC returned a non-error response;
 *       {@link Success#body()} carries the parsed JSON body (≤ 16 KiB
 *       per ADR-17 §4).</li>
 *   <li>{@link Failure} — tool BC dispatch failed for one of the
 *       reasons enumerated in {@link ToolErrorCode}. The
 *       {@code message} is human-readable and used in the
 *       {@code tool_error} SSE payload.</li>
 * </ul>
 *
 * <p>Each variant carries the correlation {@code id} so the SSE
 * emitter can pair the result with its originating {@code tool_call}
 * (ADR-17 §3.2).
 */
public sealed interface ToolInvocationResult {

    /** Correlation id matching the originating {@code tool_call.id}. */
    String id();

    /** Tool descriptor name (matches the originating {@code tool_call.name}). */
    String name();

    /** Successful tool invocation — body is the parsed (and possibly truncated) JSON. */
    record Success(String id, String name, JsonNode body) implements ToolInvocationResult {
        public Success {
            Objects.requireNonNull(id, "Success.id must not be null");
            Objects.requireNonNull(name, "Success.name must not be null");
            Objects.requireNonNull(body, "Success.body must not be null");
        }
    }

    /** Failed tool invocation — code enumerates the failure class. */
    record Failure(String id, String name, ToolErrorCode code, String message)
            implements ToolInvocationResult {
        public Failure {
            Objects.requireNonNull(id, "Failure.id must not be null");
            Objects.requireNonNull(name, "Failure.name must not be null");
            Objects.requireNonNull(code, "Failure.code must not be null");
        }
    }
}
