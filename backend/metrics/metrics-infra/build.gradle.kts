plugins {
    id("playground.bc-infra")
}

// ADR-15 §2 + §B — `metrics` BC has no Postgres schema and no Kafka surface.
// Strip the JPA / Kafka / Modulith starters that bc-infra brings in by
// default so Spring Boot autoconfig doesn't try to wire a DataSource /
// KafkaTemplate / outbox at startup.
configurations.all {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-data-jpa")
    exclude(group = "org.springframework.kafka")
    exclude(group = "org.springframework.modulith")
    exclude(group = "org.flywaydb")
    exclude(group = "org.postgresql")
    exclude(group = "jakarta.persistence")
    exclude(group = "org.hibernate.orm")
}

dependencies {
    "implementation"(project(":metrics:metrics-domain"))
    "implementation"(project(":metrics:metrics-app"))

    // WebClient adapters for Prometheus + Loki + spark-inference-gateway HEAD
    // probe (ADR-15 §7 + §12). bc-infra already pulls in
    // spring-boot-starter-webflux but list it explicitly here for clarity.
    "implementation"("org.springframework.boot:spring-boot-starter-webflux")

    // Caffeine in-process cache for the spark-gateway HEAD probe (ADR-15 §12).
    "implementation"("com.github.ben-manes.caffeine:caffeine")

    // Jackson is on the classpath via the WebFlux starter; explicit dep on
    // jackson-databind for the record-decoded DTOs in the adapters.
    "implementation"("com.fasterxml.jackson.core:jackson-databind")

    // WireMock stubs Prometheus + Loki + spark-inference-gateway in adapter
    // tests (ADR-15 §H).
    "testImplementation"("org.wiremock:wiremock-standalone:3.9.2")
    "testImplementation"("io.projectreactor:reactor-test")
}
