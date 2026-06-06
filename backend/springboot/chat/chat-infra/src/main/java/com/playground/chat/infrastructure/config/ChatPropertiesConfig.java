package com.playground.chat.infrastructure.config;

import com.playground.chat.application.properties.ChatProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the typed {@link ChatPropertiesBinding} into a Spring-free
 * {@link ChatProperties} POJO the application layer consumes.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatPropertiesBinding.class)
public class ChatPropertiesConfig {

    @Bean
    public ChatProperties chatProperties(ChatPropertiesBinding binding) {
        return binding.toProperties();
    }

    @Bean
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
