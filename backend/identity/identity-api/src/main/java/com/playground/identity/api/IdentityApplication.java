package com.playground.identity.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Identity BC's Spring Boot entrypoint.
 *
 * <p>Component scan is rooted at the BC's shared package prefix so the
 * {@code -app} use-case services, the {@code -infra} adapters (JPA repos, event
 * externalizer config), and the {@code -domain} services (the
 * {@code @Service}-annotated bootstrap decision helper per ADR-02 v2) are all
 * discovered.
 *
 * <p>Spring Modulith's JPA event-publication store auto-configures from the
 * {@code spring-modulith-starter-jpa} dependency in {@code identity-infra} —
 * no explicit {@code @Enable} annotation needed.
 *
 * <p>Slice tests inside this module use {@code @ContextConfiguration(classes =
 * Whatever.class)} with a narrower scan, so the broad scan here does not
 * affect them.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.playground.identity")
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
