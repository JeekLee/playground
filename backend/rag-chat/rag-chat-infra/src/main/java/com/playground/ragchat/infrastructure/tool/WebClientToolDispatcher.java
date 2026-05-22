package com.playground.ragchat.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.ragchat.application.properties.RagChatProperties;
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
