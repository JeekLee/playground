package com.playground.identity.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Pins JPA scanning to this BC's infra packages per ADR-02 v2. Without this,
 * Spring Boot's auto-configuration would scan from
 * {@code IdentityApplication}'s package and miss the JPA types that live in a
 * different module's package.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = "com.playground.identity.infrastructure.persistence")
@EnableJpaRepositories(basePackages = "com.playground.identity.infrastructure.persistence")
public class IdentityJpaConfig {}
