package com.playground.chat.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.application.properties.ChatProperties;
import com.playground.chat.application.tool.ToolArtifact;
import com.playground.chat.application.tool.ToolDispatcherPort;
import com.playground.chat.application.tool.ToolInvocationResult;
import com.playground.chat.application.tool.UserContext;
import com.playground.chat.domain.tool.ToolCatalog;
import com.playground.chat.domain.tool.ToolDescriptor;
import com.playground.chat.domain.tool.ToolErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

/**
 * WebClient-backed {@link ToolDispatcherPort} adapter per ADR-17 §1 + §9,
 * consuming the NDJSON streaming tool contract (tool-streaming spec D2/D4).
 *
 * <p>Wire contract: the tool BC answers {@code POST /internal/tools/<name>}
 * with HTTP 200 and an {@code application/x-ndjson} body — one JSON object
 * per line:
 * <ul>
 *   <li>{@code {"event":"progress", "stage", "label", "stageIndex",
 *       "stageCount", "attempt"?}} — relayed to the {@code onProgress}
 *       listener.</li>
 *   <li>{@code {"event":"heartbeat"}} — liveness only; resets the idle
 *       timer, otherwise ignored.</li>
 *   <li>exactly one terminal line: {@code {"event":"result", "result":{…},
 *       "artifact":{…}?}} or {@code {"event":"error", "code", "message",
 *       "status"}}.</li>
 * </ul>
 *
 * <p>Per-descriptor concerns:
 * <ul>
 *   <li>WebClient instance — cached lazily by descriptor name.</li>
 *   <li>Resilience4j circuit breaker — {@code tool-<name>}, see
 *       {@link ToolBreakerRegistry}.</li>
 *   <li>Idle timeout — descriptor's {@code timeout} applied via Reactor
 *       {@code Flux.timeout(Duration)} (gap <i>between</i> signals; a
 *       heartbeat resets it).</li>
 *   <li>Total timeout — descriptor's {@code totalTimeout} applied as the
 *       {@code blockLast(Duration)} cap on the whole stream.</li>
 *   <li>Result truncation — 16 KiB cap from
 *       {@code ChatProperties.toolMaxResultBytes()}; an over-cap
 *       {@code result} node is replaced with a {@code {"truncated": true,
 *       "originalBytes": …, "excerpt": …}} envelope per ADR-17 §4.</li>
 * </ul>
 *
 * <p>Header forwarding per ADR-17 §9 + PRD Story 3: {@code X-User-Id}
 * and {@code X-User-Sub} are forwarded; {@code Authorization} and
 * cookie headers are NOT.
 *
 * <p>Failure mapping (ADR-17 §2 table):
 * <ul>
 *   <li>terminal {@code error} line, {@code status >= 500} → {@code UPSTREAM_5XX};
 *       otherwise → {@code UPSTREAM_4XX}, message {@code "<CODE>: <message>"}</li>
 *   <li>stream ends with no terminal line → {@code INTERNAL}</li>
 *   <li>idle gap exceeded ({@link TimeoutException}) → {@code TIMEOUT}</li>
 *   <li>total cap exceeded ({@code blockLast} {@link IllegalStateException}
 *       "Timeout on blocking read") → {@code TIMEOUT}</li>
 *   <li>{@link WebClientResponseException} 4xx → {@code UPSTREAM_4XX}
 *       (transport-level non-2xx, e.g. a BC that violated the 200-only
 *       contract)</li>
 *   <li>{@link WebClientResponseException} 5xx → {@code UPSTREAM_5XX}</li>
 *   <li>{@link CallNotPermittedException} → {@code CIRCUIT_OPEN}</li>
 *   <li>malformed JSON line anywhere in the stream → Jackson's NDJSON
 *       decoder errors the whole Flux → {@code INTERNAL}. (Spec D2 said
 *       "skip the line", but the decoder cannot skip; agent-tools is a
 *       controlled producer emitting typed events, so this is unreachable
 *       in production — recorded as a spec deviation.)</li>
 *   <li>any other → {@code INTERNAL}</li>
 * </ul>
 *
 * <p><b>Circuit-breaker semantics change (spec D2):</b> the breaker
 * operator now wraps the NDJSON {@code Flux}, so it still records
 * <i>transport</i> failures — idle {@link TimeoutException}, {@link IOException},
 * and non-2xx {@link WebClientResponseException}. A domain-level terminal
 * {@code error} line is <b>not</b> an exception (the HTTP call succeeded
 * with 200 and a well-formed stream), so it is <b>not</b> recorded as a
 * breaker failure — unlike the pre-streaming contract where an upstream
 * 5xx body counted against the breaker.
 */
@Component
public class WebClientToolDispatcher implements ToolDispatcherPort {

    private static final Logger log = LoggerFactory.getLogger(WebClientToolDispatcher.class);

    private final WebClient.Builder webClientBuilder;
    private final ToolBreakerRegistry breakerRegistry;
    private final ObjectMapper objectMapper;
    private final ChatProperties properties;

    /** Resolver returning the descriptor for a given tool name. Pluggable for testing. */
    private final java.util.function.Function<String, Optional<ToolDescriptor>> descriptorResolver;

    private final ConcurrentMap<String, WebClient> webClients = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public WebClientToolDispatcher(
            @Qualifier("toolWebClientBuilder") WebClient.Builder webClientBuilder,
            ToolBreakerRegistry breakerRegistry,
            ObjectMapper objectMapper,
            ChatProperties properties) {
        this(webClientBuilder, breakerRegistry, objectMapper, properties,
                name -> ToolCatalog.descriptors().stream()
                        .filter(d -> d.name().equals(name))
                        .findFirst());
    }

    /** Test-friendly constructor — injects a custom descriptor resolver. */
    public WebClientToolDispatcher(
            WebClient.Builder webClientBuilder,
            ToolBreakerRegistry breakerRegistry,
            ObjectMapper objectMapper,
            ChatProperties properties,
            java.util.function.Function<String, Optional<ToolDescriptor>> descriptorResolver) {
        this.webClientBuilder = webClientBuilder;
        this.breakerRegistry = breakerRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.descriptorResolver = descriptorResolver;
    }

    @Override
    public ToolInvocationResult invoke(String id, String toolName, JsonNode args, UserContext userCtx,
            Consumer<ToolDispatcherPort.ToolProgress> onProgress) {
        Optional<ToolDescriptor> opt = descriptorResolver.apply(toolName);
        if (opt.isEmpty()) {
            return new ToolInvocationResult.Failure(
                    id, toolName, ToolErrorCode.INTERNAL,
                    "No descriptor registered for tool '" + toolName + "'");
        }
        ToolDescriptor descriptor = opt.get();
        CircuitBreaker breaker = breakerRegistry.forTool(descriptor.name());
        WebClient client = webClients.computeIfAbsent(descriptor.name(),
                n -> webClientBuilder.build());

        JsonNode argsToSend = args == null ? MissingNode.getInstance() : args;
        AtomicReference<JsonNode> terminal = new AtomicReference<>();

        try {
            client.post()
                    .uri(descriptor.endpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_NDJSON)
                    .header("X-User-Id", userCtx.userId().value().toString())
                    .headers(h -> {
                        if (userCtx.userSub() != null && !userCtx.userSub().isBlank()) {
                            h.set("X-User-Sub", userCtx.userSub());
                        }
                    })
                    .bodyValue(argsToSend)
                    .retrieve()
                    .bodyToFlux(JsonNode.class)
                    // Reactor's Flux.timeout = inter-signal IDLE timeout — a
                    // heartbeat line resets the timer (tool-streaming spec D2).
                    .timeout(descriptor.timeout(), Flux.error(
                            new TimeoutException("Tool '" + descriptor.name()
                                    + "' sent no event within " + descriptor.timeout())))
                    .transformDeferred(CircuitBreakerOperator.of(breaker))
                    .doOnNext(node -> {
                        String event = node.path("event").asText("");
                        switch (event) {
                            case "progress" -> emitProgress(onProgress, id, descriptor.name(), node);
                            case "result", "error" -> {
                                if (!terminal.compareAndSet(null, node)) {
                                    // 컨트랙트 위반(터미널 2개) — 첫 줄이 이긴다.
                                    log.warn("tool_stream_duplicate_terminal tool={}", descriptor.name());
                                }
                            }
                            case "heartbeat" -> { /* idle-reset effect only — ignore */ }
                            default -> log.debug("tool_stream_unknown_event tool={} event={}",
                                    descriptor.name(), event);
                        }
                    })
                    // Absolute cap on the whole stream regardless of activity.
                    // Total-cap breaches are intentionally breaker-INVISIBLE: blockLast
                    // sits outside the breaker operator, so a hung-but-heartbeating tool
                    // burns the full cap each call without tripping the breaker (spec D4
                    // 의도 — idle은 heartbeat가 리셋; total cap은 비용 상한일 뿐 health
                    // 신호로 보지 않는다).
                    .blockLast(descriptor.totalTimeout());
        } catch (Throwable t) {
            return classify(id, descriptor, t);
        }

        JsonNode t = terminal.get();
        if (t == null) {
            return new ToolInvocationResult.Failure(id, descriptor.name(),
                    ToolErrorCode.INTERNAL, "tool stream ended without a terminal event");
        }
        if ("error".equals(t.path("event").asText())) {
            int status = t.path("status").asInt(400);
            ToolErrorCode code = status >= 500 ? ToolErrorCode.UPSTREAM_5XX : ToolErrorCode.UPSTREAM_4XX;
            String message = t.path("code").asText("INTERNAL") + ": " + t.path("message").asText("");
            log.info("tool_stream_error tool={} status={} code={}", descriptor.name(), status, code);
            return new ToolInvocationResult.Failure(id, descriptor.name(), code, message);
        }
        return wrapResult(id, descriptor.name(), t);
    }

    private void emitProgress(Consumer<ToolDispatcherPort.ToolProgress> onProgress,
            String id, String name, JsonNode node) {
        try {
            onProgress.accept(new ToolDispatcherPort.ToolProgress(
                    id, name,
                    node.path("stage").asText(""),
                    node.path("label").asText(""),
                    node.path("stageIndex").asInt(0),
                    node.path("stageCount").asInt(0),
                    node.hasNonNull("attempt") ? node.get("attempt").asInt() : null));
        } catch (RuntimeException e) {
            // Progress display is best-effort — a listener exception must not
            // kill the stream / dispatch.
            log.warn("tool_progress_listener_failed tool={} reason={}", name, e.toString());
        }
    }

    /**
     * Wrap the terminal {@code result} line into a {@link ToolInvocationResult.Success}.
     *
     * <p>The terminal node is the {@code {result, artifact}} envelope (plus the
     * {@code event} discriminator). The LLM-visible {@code result} node is
     * capped at the 16 KiB byte cap (ADR-17 §4); the optional {@code artifact}
     * carries MinIO metadata only (ADR-20 §D3) and never enters the LLM path.
     */
    private ToolInvocationResult wrapResult(String id, String name, JsonNode terminal) {
        JsonNode resultNode = terminal.get("result");
        if (resultNode == null || resultNode.isNull() || resultNode.isMissingNode()) {
            resultNode = objectMapper.createObjectNode();
        }
        ToolArtifact artifact = parseArtifact(name, terminal.get("artifact"));
        JsonNode cappedResult = applyResultCap(name, resultNode);
        return new ToolInvocationResult.Success(id, name, cappedResult, artifact);
    }

    /**
     * Parse the artifact metadata per ADR-20 §D3 revised — agent-tools owns
     * MinIO writes; the artifact carries {@code storageKey} + {@code sizeBytes}
     * (no base64 bytes). Returns null if required fields are absent.
     */
    private ToolArtifact parseArtifact(String name, JsonNode artifactNode) {
        if (artifactNode == null || !artifactNode.isObject()) {
            return null;
        }
        JsonNode filename = artifactNode.get("filename");
        JsonNode storageKey = artifactNode.get("storageKey");
        if (filename == null || !filename.isTextual() || storageKey == null || !storageKey.isTextual()) {
            log.warn("tool_artifact_malformed tool={} (missing filename/storageKey) — dropping artifact", name);
            return null;
        }
        JsonNode ct = artifactNode.get("contentType");
        String contentType = (ct != null && ct.isTextual()) ? ct.asText() : null;
        JsonNode sb = artifactNode.get("sizeBytes");
        long sizeBytes = (sb != null && sb.isNumber()) ? sb.longValue() : 0L;
        return new ToolArtifact(filename.asText(), contentType, sizeBytes, storageKey.asText());
    }

    /** Apply the 16 KiB LLM-result cap (ADR-17 §4) to the envelope's `result` node. */
    private JsonNode applyResultCap(String name, JsonNode resultNode) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(resultNode);
            int cap = properties.toolMaxResultBytes();
            if (serialized.length <= cap) {
                return resultNode;
            }
            log.warn("tool_result_truncated tool={} originalBytes={} cap={}", name, serialized.length, cap);
            int excerptCap = Math.max(0, cap - 64);
            byte[] excerpt = new byte[excerptCap];
            System.arraycopy(serialized, 0, excerpt, 0, excerptCap);
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("truncated", true);
            envelope.put("originalBytes", serialized.length);
            envelope.put("excerpt", new String(excerpt, StandardCharsets.UTF_8));
            return envelope;
        } catch (IOException e) {
            return resultNode;
        }
    }

    private ToolInvocationResult classify(String id, ToolDescriptor descriptor, Throwable t) {
        String name = descriptor.name();
        // Unwrap Reactor wrappers — blockLast() wraps in RuntimeException chain.
        Throwable cause = unwrap(t);

        // blockLast(totalTimeout) surfaces its cap breach as IllegalStateException
        // ("Timeout on blocking read ...") rather than a TimeoutException.
        if (cause instanceof IllegalStateException ise
                && ise.getMessage() != null
                && ise.getMessage().contains("Timeout on blocking read")) {
            log.info("tool_total_cap tool={} cap={}", name, descriptor.totalTimeout());
            return new ToolInvocationResult.Failure(id, name, ToolErrorCode.TIMEOUT,
                    "Tool '" + name + "' did not finish within " + descriptor.totalTimeout());
        }

        if (cause instanceof CallNotPermittedException) {
            log.info("tool_circuit_open tool={}", name);
            return new ToolInvocationResult.Failure(
                    id, name, ToolErrorCode.CIRCUIT_OPEN,
                    "Tool '" + name + "' circuit breaker is OPEN");
        }
        if (cause instanceof WebClientResponseException wcre) {
            ToolErrorCode code = wcre.getStatusCode().is4xxClientError()
                    ? ToolErrorCode.UPSTREAM_4XX
                    : ToolErrorCode.UPSTREAM_5XX;
            log.info("tool_upstream_error tool={} status={} code={}",
                    name, wcre.getStatusCode().value(), code);
            return new ToolInvocationResult.Failure(
                    id, name, code,
                    "Tool '" + name + "' returned " + wcre.getStatusCode().value()
                            + (wcre.getStatusText() == null ? "" : " " + wcre.getStatusText()));
        }
        if (cause instanceof TimeoutException) {
            log.info("tool_timeout tool={} reason={}", name, cause.getMessage());
            return new ToolInvocationResult.Failure(
                    id, name, ToolErrorCode.TIMEOUT,
                    cause.getMessage() == null
                            ? "Tool '" + name + "' timed out"
                            : cause.getMessage());
        }
        if (cause instanceof IOException io) {
            log.info("tool_io_error tool={} reason={}", name, io.toString());
            return new ToolInvocationResult.Failure(
                    id, name, ToolErrorCode.INTERNAL,
                    "I/O error invoking tool '" + name + "': " + io.getMessage());
        }
        log.warn("tool_dispatch_failed tool={} reason={}", name, cause.toString(), cause);
        return new ToolInvocationResult.Failure(
                id, name, ToolErrorCode.INTERNAL,
                "Internal dispatch failure for tool '" + name + "': " + cause.getMessage());
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        // Reactor wraps in reactor.core.Exceptions.ReactiveException, which extends RuntimeException.
        // block() also rethrows Throwable wrapped in RuntimeException via Exceptions.propagate.
        // Walk the cause chain looking for the most specific known-classifiable cause.
        while (cur != null) {
            if (cur instanceof CallNotPermittedException
                    || cur instanceof WebClientResponseException
                    || cur instanceof TimeoutException
                    || cur instanceof IOException) {
                return cur;
            }
            if (cur.getCause() == cur || cur.getCause() == null) {
                return cur;
            }
            cur = cur.getCause();
        }
        return t;
    }
}
