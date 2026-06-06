package com.playground.chat.infrastructure.tool;

import com.playground.chat.application.properties.ChatProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the shared {@link WebClient.Builder} used by
 * {@link WebClientToolDispatcher} per ADR-17 §9.
 *
 * <p>The builder caps in-memory buffer size at
 * {@code ChatProperties.toolMaxResultBytes()} (default 16 KiB) so a
 * runaway-large tool response is rejected at the WebClient codec layer
 * before it inflates JVM heap. The dispatcher applies its own byte-level
 * truncate-and-warn on top — the codec cap is the belt-and-suspenders
 * defense.
 *
 * <p>Note: this is a dedicated builder bean (not the auto-configured
 * Spring Boot {@code WebClient.Builder}) so the in-memory cap is scoped
 * to tool dispatch only — other WebClient consumers in this BC (none at
 * M7, but future ones) are unaffected.
 */
@Configuration(proxyBeanMethods = false)
public class ToolDispatcherConfig {

    /**
     * WebClient in-memory buffer for the WHOLE tool HTTP response body.
     *
     * <p>Per ADR-20 §D3 revised, agent-tools owns the MinIO write path — the
     * artifact is metadata only (no base64 bytes) in the HTTP response. The
     * buffer is kept at 16 MiB as a belt-and-suspenders defence against
     * unexpectedly large tool responses (future tools, plain non-envelope
     * responses). The LLM-visible {@code result} is separately capped to
     * {@code ChatProperties.toolMaxResultBytes()} (default 16 KiB) by the
     * dispatcher AFTER parsing.
     */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024; // 16 MiB

    @Bean(name = "toolWebClientBuilder")
    public WebClient.Builder toolWebClientBuilder(ChatProperties properties) {
        // Ensure buffer is never below the LLM-result cap; 16 MiB default wins.
        int max = Math.max(MAX_RESPONSE_BYTES, properties.toolMaxResultBytes());
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(max));
    }
}
