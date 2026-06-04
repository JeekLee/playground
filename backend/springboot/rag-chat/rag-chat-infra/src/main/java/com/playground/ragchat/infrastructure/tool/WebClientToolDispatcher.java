package com.playground.ragchat.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.ragchat.application.properties.RagChatProperties;
import com.playground.ragchat.application.tool.ToolArtifact;
import com.playground.ragchat.application.tool.ToolDispatcherPort;
import com.playground.ragchat.application.tool.ToolInvocationResult;
import com.playground.ragchat.application.tool.UserContext;
import com.playground.ragchat.domain.tool.ToolCatalog;
import com.playground.ragchat.domain.tool.ToolDescriptor;
import com.playground.ragchat.domain.tool.ToolErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * WebClient-backed {@link ToolDispatcherPort} adapter per ADR-17 §1 + §9.
 *
 * <p>Per-descriptor concerns:
 * <ul>
 *   <li>WebClient instance — cached lazily by descriptor name.</li>
 *   <li>Resilience4j circuit breaker — {@code tool-<name>}, see
 *       {@link ToolBreakerRegistry}.</li>
 *   <li>Timeout — descriptor's {@code timeout} applied via Reactor
 *       {@code .timeout(...)}.</li>
 *   <li>Result truncation — 16 KiB cap from
 *       {@code RagChatProperties.toolMaxResultBytes()}; over-cap bodies
 *       are replaced with a {@code {"truncated": true, "originalBytes":
 *       …, "excerpt": …}} envelope per ADR-17 §4.</li>
 * </ul>
 *
 * <p>Header forwarding per ADR-17 §9 + PRD Story 3: {@code X-User-Id}
 * and {@code X-User-Sub} are forwarded; {@code Authorization} and
 * cookie headers are NOT.
 *
 * <p>Failure mapping (ADR-17 §2 table):
 * <ul>
 *   <li>{@link WebClientResponseException} 4xx → {@code UPSTREAM_4XX}</li>
 *   <li>{@link WebClientResponseException} 5xx → {@code UPSTREAM_5XX}</li>
 *   <li>{@link TimeoutException} → {@code TIMEOUT}</li>
 *   <li>{@link CallNotPermittedException} → {@code CIRCUIT_OPEN}</li>
 *   <li>JSON parse error → {@code SCHEMA_INVALID}</li>
 *   <li>any other → {@code INTERNAL}</li>
 * </ul>
 */
@Component
public class WebClientToolDispatcher implements ToolDispatcherPort {

    private static final Logger log = LoggerFactory.getLogger(WebClientToolDispatcher.class);

    private final WebClient.Builder webClientBuilder;
    private final ToolBreakerRegistry breakerRegistry;
    private final ObjectMapper objectMapper;
    private final RagChatProperties properties;

    /** Resolver returning the descriptor for a given tool name. Pluggable for testing. */
    private final java.util.function.Function<String, Optional<ToolDescriptor>> descriptorResolver;

    private final ConcurrentMap<String, WebClient> webClients = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public WebClientToolDispatcher(
            @Qualifier("toolWebClientBuilder") WebClient.Builder webClientBuilder,
            ToolBreakerRegistry breakerRegistry,
            ObjectMapper objectMapper,
            RagChatProperties properties) {
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
            RagChatProperties properties,
            java.util.function.Function<String, Optional<ToolDescriptor>> descriptorResolver) {
        this.webClientBuilder = webClientBuilder;
        this.breakerRegistry = breakerRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.descriptorResolver = descriptorResolver;
    }

    @Override
    public ToolInvocationResult invoke(String id, String toolName, JsonNode args, UserContext userCtx) {
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

        try {
            byte[] bodyBytes = client.post()
                    .uri(descriptor.endpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-User-Id", userCtx.userId().value().toString())
                    .headers(h -> {
                        if (userCtx.userSub() != null && !userCtx.userSub().isBlank()) {
                            h.set("X-User-Sub", userCtx.userSub());
                        }
                    })
                    .bodyValue(argsToSend)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(descriptor.timeout(), reactor.core.publisher.Mono.error(
                            new TimeoutException("Tool '" + descriptor.name()
                                    + "' did not respond within " + descriptor.timeout())))
                    .transformDeferred(CircuitBreakerOperator.of(breaker))
                    .block(descriptor.timeout().plus(Duration.ofSeconds(2)));

            return parseAndWrap(id, descriptor.name(), bodyBytes);
        } catch (Throwable t) {
            return classify(id, descriptor.name(), t);
        }
    }

    private ToolInvocationResult parseAndWrap(String id, String name, byte[] bodyBytes) {
        if (bodyBytes == null) {
            // Body absent — equivalent to empty JSON object.
            return new ToolInvocationResult.Success(id, name, objectMapper.createObjectNode());
        }

        // ADR-20 §D2 — first try to parse the full body as JSON so we can detect
        // the {result, artifact} envelope. The artifact's base64 bytes (~27 KB
        // for a 20 KB .3dm) are NOT subject to the LLM result cap — they travel
        // off the LLM path — so the cap is applied to the LLM-visible `result`
        // AFTER the envelope split, not to the raw body up front. A non-JSON
        // body (or a JSON body too large to parse meaningfully) is handled by
        // the byte-cap fallback below.
        JsonNode root = tryParse(bodyBytes);
        if (root != null && isArtifactEnvelope(root)) {
            JsonNode resultNode = root.get("result");
            if (resultNode == null || resultNode.isNull() || resultNode.isMissingNode()) {
                resultNode = objectMapper.createObjectNode();
            }
            ToolArtifact artifact = parseArtifact(name, root.get("artifact"));
            JsonNode cappedResult = applyResultCap(name, resultNode);
            return new ToolInvocationResult.Success(id, name, cappedResult, artifact);
        }

        // Plain tool (no envelope) — whole body is the result (M7 back-compat).
        // Apply the byte-level cap to the raw body before parsing so an
        // over-cap plain body still degrades to the truncate-and-warn envelope.
        int cap = properties.toolMaxResultBytes();
        byte[] effective = bodyBytes;
        int originalLen = bodyBytes.length;
        boolean truncated = false;
        if (bodyBytes.length > cap) {
            truncated = true;
            log.warn("tool_result_truncated tool={} originalBytes={} cap={}",
                    name, originalLen, cap);
            int excerptCap = Math.max(0, cap - 64); // leave room for marker
            effective = new byte[excerptCap];
            System.arraycopy(bodyBytes, 0, effective, 0, excerptCap);
        }
        try {
            JsonNode parsed;
            if (truncated) {
                ObjectNode envelope = objectMapper.createObjectNode();
                envelope.put("truncated", true);
                envelope.put("originalBytes", originalLen);
                envelope.put("excerpt", new String(effective, StandardCharsets.UTF_8));
                parsed = envelope;
            } else {
                parsed = objectMapper.readTree(effective);
                if (parsed == null || parsed.isMissingNode()) {
                    parsed = objectMapper.createObjectNode();
                }
            }
            return new ToolInvocationResult.Success(id, name, parsed);
        } catch (IOException e) {
            log.warn("tool_response_parse_error tool={} reason={}", name, e.toString());
            return new ToolInvocationResult.Failure(
                    id, name, ToolErrorCode.SCHEMA_INVALID,
                    "Tool response not valid JSON: " + e.getMessage());
        }
    }

    private JsonNode tryParse(byte[] bytes) {
        try {
            JsonNode node = objectMapper.readTree(bytes);
            return (node == null || node.isMissingNode()) ? null : node;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The {result, artifact} envelope is detected (ADR-20 §D2) by an object
     * carrying BOTH a {@code result} and an {@code artifact} key. A body with
     * only one (or neither) is treated as a plain tool result (back-compat).
     */
    private static boolean isArtifactEnvelope(JsonNode node) {
        return node.isObject() && node.has("result") && node.has("artifact");
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

    private ToolInvocationResult classify(String id, String name, Throwable t) {
        // Unwrap Reactor wrappers — block() wraps in RuntimeException chain.
        Throwable cause = unwrap(t);

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
