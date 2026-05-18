package com.playground.ragchat.infrastructure.external;

import com.playground.ragchat.application.port.ChatGenerationPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
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

    public SparkInferenceChatAdapter(ChatClient.Builder chatClientBuilder, CircuitBreaker sparkGatewayBreaker) {
        this.chatClient = chatClientBuilder.build();
        this.breaker = sparkGatewayBreaker;
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
