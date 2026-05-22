package com.playground.docs.infrastructure.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a UTC system clock — domain services depend on {@link Clock}. */
@Configuration(proxyBeanMethods = false)
public class DocsClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
