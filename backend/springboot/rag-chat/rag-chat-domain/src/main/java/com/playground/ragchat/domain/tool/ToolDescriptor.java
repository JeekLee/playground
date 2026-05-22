package com.playground.ragchat.domain.tool;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable value object describing one LLM-invokable tool BC endpoint per
 * ADR-17 §1 + §8.
 *
 * <p>Lives in {@code rag-chat-domain} so it carries no Spring annotations
 * — Spring AI {@code ToolCallback} construction happens in
 * {@code rag-chat-infra} via {@code ToolCallbackFactory}.
 *
 * <p>ADR-17 §1 pins {@code parameterSchema} as a <b>JSON-Schema string</b>
 * (not a Jackson {@code JsonNode}) so the descriptor stays Jackson-free
 * at the domain layer; Spring AI's {@code FunctionToolCallback.builder()}
 * accepts the string verbatim via its {@code .inputSchema(...)} setter.
 *
 * @param name             tool name — must be non-blank, used as the
 *                         Spring AI callback name and the per-tool circuit
 *                         breaker name (e.g., {@code tool-<name>}).
 *                         Names must be unique within the {@code ToolCatalog}.
 * @param description      human-readable description the LLM uses to
 *                         decide whether to invoke the tool. Must be non-blank.
 * @param parameterSchema  JSON-Schema string describing the tool's input
 *                         arguments. May be {@code null} or blank for
 *                         no-argument tools.
 * @param endpoint         tool BC's {@code POST /internal/tools/<name>}
 *                         endpoint. Compose-internal URI per ADR-08
 *                         §A08.8 Exception 4. Must be non-null.
 * @param timeout          WebClient call timeout per descriptor. Must be
 *                         positive.
 */
public record ToolDescriptor(
        String name,
        String description,
        String parameterSchema,
        URI endpoint,
        Duration timeout) {

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolDescriptor.name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("ToolDescriptor.description must not be blank");
        }
        Objects.requireNonNull(endpoint, "ToolDescriptor.endpoint must not be null");
        Objects.requireNonNull(timeout, "ToolDescriptor.timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "ToolDescriptor.timeout must be positive (was " + timeout + ")");
        }
    }
}
