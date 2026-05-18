package com.playground.ragchat.infrastructure.config;

import com.playground.ragchat.application.properties.RagChatProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the typed {@link RagChatPropertiesBinding} into a Spring-free
 * {@link RagChatProperties} POJO the application layer consumes.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagChatPropertiesBinding.class)
public class RagChatPropertiesConfig {

    @Bean
    public RagChatProperties ragChatProperties(RagChatPropertiesBinding binding) {
        return binding.toProperties();
    }

    @Bean
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
