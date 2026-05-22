// Convention for *-app (application / use-case) modules per ADR-01 v2 / ADR-02 v2.
//
// -app holds use-case services, DTOs, repository ports, and outbound port
// interfaces. Spring Context + Spring TX are allowed (for @Service /
// @Transactional). Spring Web, JPA, Kafka, Spring Data, Spring AI are
// forbidden — adapters live in -infra.

plugins {
    id("playground.java-conventions")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5")
    }
}

configurations.all {
    exclude(group = "org.springframework.boot")
    exclude(group = "org.springframework", module = "spring-webmvc")
    exclude(group = "org.springframework", module = "spring-webflux")
    exclude(group = "org.springframework.data")
    exclude(group = "org.springframework.kafka")
    exclude(group = "org.springframework.ai")
    exclude(group = "jakarta.persistence")
    exclude(group = "org.hibernate.orm")
}

dependencies {
    "api"(project(":shared-kernel"))

    "compileOnly"("org.projectlombok:lombok")
    "annotationProcessor"("org.projectlombok:lombok")
    "testCompileOnly"("org.projectlombok:lombok")
    "testAnnotationProcessor"("org.projectlombok:lombok")

    "implementation"("org.springframework:spring-context")
    "implementation"("org.springframework:spring-tx")

    // Bean Validation API only — providers live in -api / -infra.
    "implementation"("jakarta.validation:jakarta.validation-api")

    // Spring Modulith events API — application services publish domain events
    // via ApplicationEventPublisher; the actual JPA outbox + Kafka externalizer
    // sit in -infra. The events-api jar is starter-free (no Spring Boot
    // autoconfig hookup at this layer).
    "implementation"("org.springframework.modulith:spring-modulith-events-api:1.2.5")

    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testImplementation"("org.assertj:assertj-core")
    "testImplementation"("org.mockito:mockito-core")
    "testImplementation"("org.mockito:mockito-junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
