package com.playground.chat.domain.tool;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable value object describing one LLM-invokable tool BC endpoint per
 * ADR-17 §1 + §8.
 *
 * <p>Lives in {@code chat-domain} so it carries no Spring annotations
 * — Spring AI {@code ToolCallback} construction happens in
 * {@code chat-infra} via {@code ToolCallbackFactory}.
 *
 * <p>ADR-17 §1 pins {@code parameterSchema} as a <b>JSON-Schema string</b>
 * (not a Jackson {@code JsonNode}) so the descriptor stays Jackson-free
 * at the domain layer; Spring AI's {@code FunctionToolCallback.builder()}
 * accepts the string verbatim via its {@code .inputSchema(...)} setter.
 *
 * <p>Streaming-tool contract (tool-streaming spec D2/D4): tool BCs now
 * answer {@code POST /internal/tools/<name>} with an
 * {@code application/x-ndjson} stream (progress / heartbeat lines + one
 * terminal {@code result} or {@code error} line) rather than a single
 * JSON body. Two distinct time bounds govern the call:
 * <ul>
 *   <li>{@code timeout} is the <b>idle</b> bound — the maximum gap
 *       <i>between</i> NDJSON signals. A heartbeat line resets it, so a
 *       long-running tool that keeps emitting never trips it. Mapped to
 *       Reactor's {@code Flux.timeout(Duration)} in the dispatcher.</li>
 *   <li>{@code totalTimeout} is the <b>absolute</b> bound — the hard cap
 *       on the whole stream regardless of activity. Mapped to the
 *       dispatcher's {@code blockLast(Duration)} cap.</li>
 * </ul>
 *
 * @param name             tool name — must be non-blank, used as the
 *                         Spring AI callback name and the per-tool circuit
 *                         breaker name (e.g., {@code tool-<name>}).
 *                         Names must be unique within the {@code ToolCatalog}.
 * @param displayName      short human-facing label for the in-flight tool
 *                         card the frontend renders while the tool runs
 *                         (tool-streaming spec W2). Must be non-blank.
 * @param description      human-readable description the LLM uses to
 *                         decide whether to invoke the tool. Must be non-blank.
 * @param parameterSchema  JSON-Schema string describing the tool's input
 *                         arguments. May be {@code null} or blank for
 *                         no-argument tools.
 * @param endpoint         tool BC's {@code POST /internal/tools/<name>}
 *                         endpoint. Compose-internal URI per ADR-08
 *                         §A08.8 Exception 4. Must be non-null.
 * @param timeout          IDLE timeout — max no-event gap between NDJSON
 *                         signals (heartbeat resets it). Must be positive.
 * @param totalTimeout     absolute upper bound on the whole stream
 *                         regardless of activity. Must be positive.
 */
public record ToolDescriptor(
        String name,
        String displayName,
        String description,
        String parameterSchema,
        URI endpoint,
        Duration timeout,
        Duration totalTimeout) {

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolDescriptor.name must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("ToolDescriptor.displayName must not be blank");
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
        Objects.requireNonNull(totalTimeout, "ToolDescriptor.totalTimeout must not be null");
        if (totalTimeout.isZero() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "ToolDescriptor.totalTimeout must be positive (was " + totalTimeout + ")");
        }
    }
}
