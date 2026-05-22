package com.playground.massinggen.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Massing-Gen BC's Spring Boot entrypoint per ADR-18 §1 + §2 (port 18083,
 * gateway-routable for {@code /api/arch/**} only).
 *
 * <p>M8 ships a synchronous Spring MVC app: one POST tool endpoint
 * ({@code /internal/tools/generate-massing}) plus one GET download
 * ({@code /api/arch/outputs/&lbrace;id&rbrace;}). No reactive end-to-end
 * requirement — Spring MVC owns the request transport. Outbound WebClient
 * calls (docs-api body fetch, rhino3dm-bridge sidecar) are wrapped in
 * synchronous {@code .block(...)} sites per the BC's per-turn-throughput
 * profile (one call per chat turn, comfortably under the per-tool 60s
 * timeout descriptor).
 *
 * <p>Component scan is rooted at the BC's shared package prefix so the
 * {@code -app} use cases, the {@code -infra} adapters (JPA repos,
 * Resilience4j config, Spring AI ChatClient, WebClient adapters), and
 * the {@code -domain} helpers (MassingAlgorithm, MassingSummary) are
 * discovered.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.playground.massinggen")
public class MassingGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(MassingGenApplication.class, args);
    }
}
