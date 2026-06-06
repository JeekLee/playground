package com.playground.chat.infrastructure.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.chat.application.port.ChatGenerationPort;
import com.playground.chat.application.tool.ToolBinding;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring AI-backed adapter for {@link ChatGenerationPort} per ADR-04 + ADR-14
 * §1. The underlying {@link ChatClient} is wired by
 * {@code spring-ai-starter-model-openai} against the spark-inference-gateway's
 * OpenAI-compatible {@code /v1/chat/completions} endpoint (base URL =
 * {@code SPRING_AI_OPENAI_BASE_URL}; model = Qwen3-32B per
 * {@code application.yml}).
 *
 * <p>Both {@link #stream(String, int)} and {@link #generateOnce(String, double, int)}
 * are wrapped with the shared {@code spark-gateway} Resilience4j circuit
 * breaker per ADR-14 §4. A breaker-OPEN call surfaces as
 * {@code CallNotPermittedException} on the returned reactive type.
 */
@Component
public class SparkInferenceChatAdapter implements ChatGenerationPort {

    private static final Logger log = LoggerFactory.getLogger(SparkInferenceChatAdapter.class);

    private final ChatClient chatClient;
    private final CircuitBreaker breaker;
    private final ObjectMapper objectMapper;

    public SparkInferenceChatAdapter(
            ChatClient.Builder chatClientBuilder,
            CircuitBreaker sparkGatewayBreaker,
            ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.breaker = sparkGatewayBreaker;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<String> stream(String promptText, int maxTokens) {
        Prompt prompt = new Prompt(
                new UserMessage(promptText),
                OpenAiChatOptions.builder().maxTokens(maxTokens).build());
        return chatClient.prompt(prompt)
                .stream()
                .content()
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .doOnError(err -> log.warn("chat stream error: {}", err.toString()));
    }

    @Override
    public Flux<String> streamWithTools(String promptText, int maxTokens, List<ToolBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            // M4 invariant — no tool callbacks registered means we drop straight
            // back to the existing path (Spring AI never sees a tools(...) call,
            // never engages function-calling, SSE event grammar matches M4).
            return stream(promptText, maxTokens);
        }
        List<ToolCallback> callbacks = new ArrayList<>(bindings.size());
        for (ToolBinding b : bindings) {
            callbacks.add(toCallback(b));
        }
        Prompt prompt = new Prompt(
                new UserMessage(promptText),
                OpenAiChatOptions.builder().maxTokens(maxTokens).build());
        return chatClient.prompt(prompt)
                .toolCallbacks(callbacks)
                .stream()
                .content()
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .doOnError(err -> log.warn("chat stream error (tools): {}", err.toString()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ToolCallback toCallback(ToolBinding binding) {
        // Spring AI calls FunctionToolCallback with the LLM-serialized args.
        // vLLM (spark-inference-gateway) ships args as a JSON object, so we
        // accept `Map` here — Spring AI deserializes the JSON object into a
        // LinkedHashMap<String, Object> for us. (Earlier the inputType was
        // `String.class` per the M7 implementer dispatch — that path errored
        // with `Conversion from JSON to java.lang.String failed` on every
        // tool-emitting LLM turn because vLLM emits objects, not strings,
        // for function-call args. The 2026-05-22 incident in production
        // surfaced this within minutes of M8 going live.)
        FunctionToolCallback.Builder<Map, String> builder = FunctionToolCallback
                .builder(binding.descriptor().name(), (Map argsMap) -> {
                    JsonNode args;
                    try {
                        args = argsMap == null
                                ? objectMapper.createObjectNode()
                                : objectMapper.valueToTree(argsMap);
                    } catch (Exception e) {
                        log.warn("tool_args_parse_error tool={} reason={}",
                                binding.descriptor().name(), e.toString());
                        args = objectMapper.createObjectNode();
                    }
                    JsonNode response = binding.handler().apply(args);
                    if (response == null) {
                        return "{}";
                    }
                    try {
                        return objectMapper.writeValueAsString(response);
                    } catch (Exception e) {
                        log.warn("tool_response_serialize_error tool={} reason={}",
                                binding.descriptor().name(), e.toString());
                        return "{}";
                    }
                })
                .description(binding.descriptor().description())
                .inputType(Map.class);
        String schema = binding.descriptor().parameterSchema();
        if (schema != null && !schema.isBlank()) {
            builder = builder.inputSchema(schema);
        }
        return builder.build();
    }

    @Override
    public Mono<String> generateOnce(String promptText, double temperature, int maxTokens) {
        Prompt prompt = new Prompt(
                new UserMessage(promptText),
                OpenAiChatOptions.builder()
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .build());
        return Mono.fromCallable(() -> chatClient.prompt(prompt).call().content())
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .doOnError(err -> log.warn("chat one-shot error: {}", err.toString()));
    }
}
