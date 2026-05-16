// Convention for *-infra modules per ADR-01 v2 / ADR-02 v2.
//
// -infra owns the outbound adapters: JPA entities + repository impls + mappers,
// Kafka producers, WebClient clients, Spring Modulith outbox wiring. Not a
// runnable app — library JAR consumed by -api at runtime.

plugins {
    id("playground.java-conventions")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5")
    }
}

dependencies {
    "api"(project(":shared-kernel"))

    "compileOnly"("org.projectlombok:lombok")
    "annotationProcessor"("org.projectlombok:lombok")
    "testCompileOnly"("org.projectlombok:lombok")
    "testAnnotationProcessor"("org.projectlombok:lombok")

    "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
    "implementation"("org.springframework.kafka:spring-kafka")
    "implementation"("org.springframework.boot:spring-boot-starter-webflux")

    // Postgres + Flyway (ADR-05).
    "runtimeOnly"("org.postgresql:postgresql")
    "implementation"("org.flywaydb:flyway-core")
    "runtimeOnly"("org.flywaydb:flyway-database-postgresql")

    // Spring Modulith Events — JPA outbox table + Kafka externalizer (ADR-10 §8).
    "implementation"("org.springframework.modulith:spring-modulith-starter-jpa:1.2.5")
    "implementation"("org.springframework.modulith:spring-modulith-events-kafka:1.2.5")

    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.springframework.kafka:spring-kafka-test")
    "testImplementation"("org.testcontainers:junit-jupiter:1.20.3")
    "testImplementation"("org.testcontainers:postgresql:1.20.3")
    "testImplementation"("org.testcontainers:kafka:1.20.3")
}
