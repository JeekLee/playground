package com.playground.ragchat.infrastructure.tool;

import com.playground.ragchat.application.properties.RagChatProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the shared {@link WebClient.Builder} used by
 * {@link WebClientToolDispatcher} per ADR-17 §9.
 *
 * <p>The builder caps in-memory buffer size at
 * {@code RagChatProperties.toolMaxResultBytes()} (default 16 KiB) so a
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

    @Bean(name = "toolWebClientBuilder")
    public WebClient.Builder toolWebClientBuilder(RagChatProperties properties) {
        int max = properties.toolMaxResultBytes();
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(max));
    }
}
