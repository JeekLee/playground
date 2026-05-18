package com.playground.ragingestion.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * RAG-Ingestion BC's Spring Boot entrypoint per ADR-13 §4 + §A (port 18083).
 *
 * <p>UI-less BC — the only HTTP surface is {@code /actuator/**}; no
 * controllers. Inbound traffic arrives via three {@code @KafkaListener}
 * methods in {@code DocumentEventListener}. Per ADR-13 §4 the listener
 * methods sit in {@code rag-ingestion-api} (the BC's outermost adapter,
 * analogous to controllers in an HTTP-fronted BC).
 *
 * <p>Component scan is rooted at the BC's shared package prefix so the
 * {@code -app} use-case services, the {@code -infra} adapters (JPA repos,
 * Kafka consumer config, WebClient adapters, Redisson lock, Spring AI
 * embedder), and the {@code -domain} chunker bean are all discovered.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.playground.ragingestion")
public class RagIngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagIngestionApplication.class, args);
    }
}
