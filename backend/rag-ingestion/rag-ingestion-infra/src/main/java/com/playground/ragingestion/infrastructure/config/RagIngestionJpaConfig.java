package com.playground.ragingestion.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Pins JPA scanning to this BC's infra packages per ADR-02 v2. Without this,
 * Spring Boot's auto-configuration would scan from {@code RagIngestionApplication}'s
 * package and miss the JPA types in a different module's package.
 *
 * <p>The Spring Modulith JPA event-publication package is listed so Hibernate
 * registers the {@code JpaEventPublication} entity used by the transactional
 * outbox for {@code rag.document.ingested} (ADR-10 §8 / ADR-13 §D).
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = {
        "com.playground.ragingestion.infrastructure.persistence",
        "org.springframework.modulith.events.jpa"
})
@EnableJpaRepositories(basePackages = "com.playground.ragingestion.infrastructure.persistence")
public class RagIngestionJpaConfig {}
