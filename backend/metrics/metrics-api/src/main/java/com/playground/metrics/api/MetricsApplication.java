package com.playground.metrics.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Metrics BC's Spring Boot entrypoint per ADR-15 §1 + §2 (port 18085,
 * gateway-routable). M5 is WebFlux end-to-end — the {@code -api} module
 * excludes {@code spring-boot-starter-web} so Reactor Netty owns the
 * runtime. No Postgres, no Kafka, no JPA — adapters in {@code metrics-infra}
 * carry only WebClient + Caffeine.
 *
 * <p>Component scan is rooted at the BC's shared package prefix so the
 * {@code -app} use cases and the {@code -infra} adapters are discovered.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.playground.metrics")
public class MetricsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetricsApplication.class, args);
    }
}
