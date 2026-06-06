package com.playground.ragchat.application.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outbound port from {@code rag-chat-app} to the tool-BC dispatch
 * adapter per ADR-17 §8. Implementation lives in {@code rag-chat-infra}
 * ({@code WebClientToolDispatcher}).
 *
 * <p>The port is intentionally narrow: one method, JSON-shaped args
 * in, classified result out. Spring AI integration ({@code ToolCallback}
 * construction, function-calling round trip) happens in
 * {@code rag-chat-infra}; this port is the application-layer surface
 * the multi-turn use-case talks to.
 *
 * <p>The implementation MUST:
 * <ul>
 *   <li>Resolve the tool descriptor via {@code ToolCatalog}.</li>
 *   <li>Apply the per-descriptor {@code timeout}.</li>
 *   <li>Apply the per-tool Resilience4j circuit breaker
 *       ({@code tool-<name>}).</li>
 *   <li>Forward {@code X-User-Id} + {@code X-User-Sub} headers (ADR-17 §9).</li>
 *   <li>Truncate response bodies above 16 KiB per ADR-17 §4.</li>
 *   <li>Map every failure mode to a {@link ToolInvocationResult.Failure}
 *       carrying the right {@code ToolErrorCode} (ADR-17 §2 table).</li>
 * </ul>
 *
 * <p>The port returns synchronously (no {@code Mono} / {@code Flux}).
 * The dispatch happens on a {@code Schedulers.boundedElastic()} thread
 * inside the streaming pipeline; blocking once per tool round trip is
 * acceptable and simpler than weaving a Mono into the SSE event mapper.
 *
 * @see ToolInvocationResult
 * @see UserContext
 */
public interface ToolDispatcherPort {

    /**
     * App-layer copy of one NDJSON {@code progress} event the tool BC
     * emits while running (tool-streaming spec W2). The dispatcher decodes
     * each {@code progress} line into this record and hands it to the
     * caller's {@code onProgress} listener so the use-case can relay it
     * onto the SSE stream as a {@code tool_progress} event.
     *
     * @param id          correlation id (matches the originating
     *                    {@code tool_call.id}).
     * @param name        tool descriptor name.
     * @param stage       machine stage key (e.g., {@code "extract"}).
     * @param label       human-facing label rendered verbatim by the FE.
     * @param stageIndex  1-based index of the current stage.
     * @param stageCount  total number of stages.
     * @param attempt     retry attempt number, or {@code null} on the first
     *                    attempt.
     */
    record ToolProgress(
            String id,
            String name,
            String stage,
            String label,
            int stageIndex,
            int stageCount,
            Integer attempt) {}

    /**
     * Dispatch one tool call, consuming the NDJSON progress stream.
     *
     * @param id          correlation id for the {@code tool_call} /
     *                    {@code tool_result} pair (ADR-17 §3.2). Must not
     *                    be null.
     * @param toolName    name of a descriptor registered in
     *                    {@code ToolCatalog.descriptors()}. If absent from
     *                    the catalog, the implementation returns
     *                    {@link ToolInvocationResult.Failure} with
     *                    {@code code = INTERNAL}.
     * @param args        LLM-produced JSON arguments. May be {@code null}
     *                    for no-argument tools.
     * @param userCtx     identity context forwarded as HTTP headers.
     * @param onProgress  best-effort listener invoked once per NDJSON
     *                    {@code progress} event. Listener exceptions are
     *                    isolated by the implementation — they never abort
     *                    the dispatch. Never null (pass a no-op when
     *                    progress is not needed).
     * @return a {@link ToolInvocationResult.Success} or
     *         {@link ToolInvocationResult.Failure}. Never null, never
     *         throws — every failure mode is classified into the
     *         {@code Failure} envelope.
     */
    ToolInvocationResult invoke(
            String id,
            String toolName,
            JsonNode args,
            UserContext userCtx,
            java.util.function.Consumer<ToolProgress> onProgress);
}
