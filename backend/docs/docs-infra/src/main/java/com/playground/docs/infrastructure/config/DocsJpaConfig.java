package com.playground.docs.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Pins JPA scanning to this BC's infra packages per ADR-02 v2. Without this,
 * Spring Boot's auto-configuration would scan from {@code DocsApplication}'s
 * package and miss the JPA types that live in a different module's package.
 *
 * <p>The Spring Modulith JPA event-publication package is also listed so
 * Hibernate registers the {@code JpaEventPublication} entity used by the
 * transactional outbox (ADR-10 §8 / ADR-12 §1). Without it, every publication
 * attempt fails with {@code Unable to locate persister: ...JpaEventPublication}.
 *
 * <p>S1 does not publish any events yet (M2 S2+ wires the domain events per
 * M2 spec §5), but the starter is on the classpath via the {@code bc-infra}
 * convention plugin and Hibernate still scans the entity on boot. Listing the
 * package keeps the boot path identical to identity-infra's pattern.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = {
        "com.playground.docs.infrastructure.persistence",
        // M6.1 (ADR-12 §A12.1) — chunk JPA entity moved from the retired
        // rag-ingestion BC; lives under the `ingestion.persistence`
        // subpackage now. The JpaEventPublication entity below is
        // unchanged; the rag.event_publication outbox is dropped per
        // ADR-12 §A12.13.
        "com.playground.docs.infrastructure.ingestion.persistence",
        "org.springframework.modulith.events.jpa"
})
@EnableJpaRepositories(basePackages = {
        "com.playground.docs.infrastructure.persistence",
        "com.playground.docs.infrastructure.ingestion.persistence"
})
public class DocsJpaConfig {}
