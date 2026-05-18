package com.playground.docs.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Docs BC's Spring Boot entrypoint per ADR-12 §15 (port 18082).
 *
 * <p>Component scan is rooted at the BC's shared package prefix so the
 * {@code -app} use-case services, the {@code -infra} adapters (JPA repos +
 * configs), and any {@code -domain} services (none in S1, but the wiring is in
 * place for M2 S2's outbox externalizer / search projector) are all
 * discovered.
 *
 * <p>Spring Modulith's JPA event-publication store auto-configures from the
 * {@code spring-modulith-starter-jpa} dependency in {@code docs-infra} — no
 * explicit {@code @Enable} annotation needed. S1 leaves
 * {@code spring.modulith.events.externalization.enabled=false} in
 * {@code application.yml} since no events are published yet.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.playground.docs")
public class DocsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocsApplication.class, args);
    }
}
