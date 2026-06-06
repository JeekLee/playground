package com.playground.chat.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Chat BC's Spring Boot entrypoint per ADR-14 §1 + §2 (port 18084,
 * gateway-routable). M4 is the first WebFlux-end-to-end BC in the project —
 * the {@code -api} module excludes {@code spring-boot-starter-web} (servlet
 * / Tomcat) so the SSE controller's reactive {@code Flux<ServerSentEvent>}
 * shape doesn't pin a request thread per stream.
 *
 * <p>Component scan is rooted at the BC's shared package prefix so the
 * {@code -app} use-case services, the {@code -infra} adapters (JPA repos,
 * Resilience4j config, Redisson adapters, Spring AI adapters), and the
 * {@code -domain} services (token counter, prompt assembler) are discovered.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.playground.chat")
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
