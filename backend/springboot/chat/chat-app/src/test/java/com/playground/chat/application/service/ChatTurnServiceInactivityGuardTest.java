package com.playground.chat.application.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.playground.shared.chat.ChatStreamEvent;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Virtual-time tests for {@link ChatTurnService#withInactivityGuard}.
 *
 * <p>Root cause this guards against: the 150s inactivity timeout on the LLM
 * token flux fired during long synchronous tool runs (the token flux is
 * legitimately silent while Spring AI runs the tool on the token-generating
 * thread), killing e.g. generate_massing at T+150s though agent-tools finished
 * ~178s in. The dispatcher already governs tool liveness (idle 60s / total
 * 600s), so while a tool is in-flight the outer guard is SUSPENDED via a 30s
 * keepalive tick; it only fires on genuine LLM token silence with no tool
 * in-flight.
 *
 * <p>The flux-under-test is constructed INSIDE the {@code withVirtualTime}
 * supplier so Reactor installs the {@link reactor.test.scheduler.VirtualTimeScheduler}
 * for {@code Flux.interval} (keepalive) and {@code timeout}.
 */
class ChatTurnServiceInactivityGuardTest {

    private static ChatStreamEvent toolCall(String id) {
        return new ChatStreamEvent.ToolCall(id, "generate_massing", "Generate massing",
                JsonNodeFactory.instance.objectNode());
    }

    private static ChatStreamEvent toolResult(String id) {
        return new ChatStreamEvent.ToolResult(id, "generate_massing",
                JsonNodeFactory.instance.objectNode());
    }

    /**
     * Case 1 — a tool in-flight suspends the guard: the source emits a
     * ToolCall, then is silent for 200s (well past the 150s timeout) with
     * {@code inFlightTools=1}, then emits a ToolResult and completes. Expect NO
     * timeout: both events pass through (keepalive ticks filtered out) and the
     * flux completes normally.
     */
    @Test
    void toolInFlightSuspendsTheGuard() {
        AtomicInteger inFlightTools = new AtomicInteger(1);

        StepVerifier.withVirtualTime(() -> {
                    Flux<ChatStreamEvent> source = Flux.concat(
                            Flux.just(toolCall("call_1")),
                            Mono.delay(Duration.ofSeconds(200)).then(Mono.empty()),
                            Flux.just(toolResult("call_1")));
                    return ChatTurnService.withInactivityGuard(source, inFlightTools);
                })
                .expectSubscription()
                .expectNext(toolCall("call_1"))
                .thenAwait(Duration.ofSeconds(200))
                .expectNext(toolResult("call_1"))
                .verifyComplete();
    }

    /**
     * Case 2 — pure LLM silence still trips: no tool in-flight
     * ({@code inFlightTools=0}), source silent forever; after 150s the guard
     * errors with TimeoutException.
     */
    @Test
    void pureLlmSilenceStillTrips() {
        AtomicInteger inFlightTools = new AtomicInteger(0);

        StepVerifier.withVirtualTime(() ->
                        ChatTurnService.withInactivityGuard(Flux.never(), inFlightTools))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(150))
                .expectError(TimeoutException.class)
                .verify();
    }

    /**
     * Case 3 — suspension lifts after the tool completes: a tool is in-flight
     * during a long silence (no trip), then {@code inFlightTools} returns to 0
     * and a further 150s of silence trips the guard.
     */
    @Test
    void suspensionLiftsAfterToolCompletes() {
        AtomicInteger inFlightTools = new AtomicInteger(1);

        StepVerifier.withVirtualTime(() -> {
                    // Emit a ToolCall, then go silent. The counter starts at 1
                    // (tool in-flight); we flip it to 0 after 200s of virtual
                    // time, simulating the dispatcher returning. The source never
                    // completes, so once suspension lifts the guard must trip.
                    Flux<ChatStreamEvent> source = Flux.concat(
                            Flux.just(toolCall("call_1")),
                            Flux.<ChatStreamEvent>never());
                    return ChatTurnService.withInactivityGuard(source, inFlightTools);
                })
                .expectSubscription()
                .expectNext(toolCall("call_1"))
                // Tool runs for 200s — keepalive ticks hold the window open.
                .thenAwait(Duration.ofSeconds(200))
                // Dispatcher returns; in-flight count drops to 0.
                .then(() -> inFlightTools.set(0))
                // Now genuine LLM silence: 150s with no tool in-flight trips it.
                .thenAwait(Duration.ofSeconds(150))
                .expectError(TimeoutException.class)
                .verify();
    }
}
