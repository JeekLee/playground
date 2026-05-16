package com.playground.identity.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a UTC system clock — domain services depend on {@link Clock}. */
@Configuration(proxyBeanMethods = false)
public class IdentityClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
