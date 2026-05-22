package com.playground.ragchat.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.playground.ragchat.domain.tool.ToolDescriptor;
import java.util.Objects;
import java.util.function.Function;

/**
 * Carrier the application layer hands to the {@code ChatGenerationPort}
 * to wire Spring AI {@code ToolCallback}s without leaking Spring AI types
 * into {@code rag-chat-app}.
 *
 * <p>The infra adapter builds a {@code FunctionToolCallback} per binding:
 * the callback's name + description + input schema come from the
 * {@link #descriptor()}; the callback's function body delegates to
 * {@link #handler()} which (in the use case) emits the {@code tool_call} /
 * {@code tool_result} / {@code tool_error} SSE events, invokes
 * {@link ToolDispatcherPort}, applies the truncation cap, enforces the
 * depth limit, and returns the JSON body that Spring AI feeds back to
 * the model.
 *
 * @param descriptor the immutable tool descriptor sourced from
 *                   {@code ToolCatalog.descriptors()}.
 * @param handler    function the adapter must call when Spring AI invokes
 *                   the tool. Receives the LLM-produced {@code args}
 *                   JSON; returns the JSON to feed back to the LLM. The
 *                   handler is responsible for any side-effects
 *                   (event emission, dispatcher invocation, error mapping).
 */
public record ToolBinding(ToolDescriptor descriptor, Function<JsonNode, JsonNode> handler) {

    public ToolBinding {
        Objects.requireNonNull(descriptor, "ToolBinding.descriptor must not be null");
        Objects.requireNonNull(handler, "ToolBinding.handler must not be null");
    }
}
