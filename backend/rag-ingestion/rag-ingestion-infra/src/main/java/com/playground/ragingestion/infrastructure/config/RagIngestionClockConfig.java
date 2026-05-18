package com.playground.ragingestion.infrastructure.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** UTC system clock for the BC's services. Tests override with a fixed clock. */
@Configuration(proxyBeanMethods = false)
public class RagIngestionClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
