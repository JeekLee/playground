package com.playground.ragchat.application.port;

import com.playground.ragchat.application.tool.ToolBinding;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Outbound port to the LLM chat completion service (spark-inference-gateway
 * Qwen3-32B per ADR-04 + ADR-14 §1). The infra adapter
 * ({@code SparkInferenceChatAdapter}) is Resilience4j-wrapped per ADR-14 §4
 * — calls that trip the {@code spark-gateway} circuit breaker surface as a
 * {@code CallNotPermittedException} on the {@code Flux}.
 *
 * <p>{@link #stream(String, int)} returns a hot/cold {@link Flux} of delta
 * strings; the orchestrator subscribes once. Cancellation of the downstream
 * {@code Subscription} propagates per ADR-14 §14 to the upstream WebClient,
 * closing the connection to vLLM.
 *
 * <p>{@link #generateOnce(String, double, int)} is the non-streaming
 * counterpart used by the auto-title path (ADR-14 §6).
 */
public interface ChatGenerationPort {

    /** Stream delta strings as they arrive from the LLM. */
    Flux<String> stream(String prompt, int maxTokens);

    /**
     * Stream delta strings while Spring AI's function-calling layer
     * transparently round-trips registered tool callbacks per ADR-17 §1.
     *
     * <p>The adapter MUST register each {@link ToolBinding} as a Spring AI
     * {@code ToolCallback} on the {@code chatClient.prompt(...).tools(...)}
     * call. When the model decides to invoke a tool, Spring AI calls the
     * binding's {@code handler} (which delegates to {@link
     * com.playground.ragchat.application.tool.ToolDispatcherPort}); the
     * handler's return value is fed back to the LLM, and the resulting
     * deltas continue to flow on the returned {@link Flux}.
     *
     * <p>If {@code bindings} is empty this MUST behave identically to
     * {@link #stream(String, int)} (M4 invariant per PRD Story 10).
     */
    Flux<String> streamWithTools(String prompt, int maxTokens, List<ToolBinding> bindings);

    /**
     * One-shot non-streaming call. Used by auto-title with
     * {@code temperature=0.1, maxTokens=24}.
     */
    Mono<String> generateOnce(String prompt, double temperature, int maxTokens);
}
