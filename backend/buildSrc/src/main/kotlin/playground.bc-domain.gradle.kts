// Convention for *-domain modules (per ADR-01 v2 / ADR-02 v2).
//
// -domain holds aggregates, value objects, identifiers, domain services, and
// domain events. Spring Context is allowed (so domain services can carry
// @Service / @RequiredArgsConstructor — VIA-compromise per ADR-02 v2).
// Spring Boot, Web (other than HttpStatus reference), JPA, Kafka, and Spring
// Data are forbidden at the classpath level so a violation fails compile, not
// test.

plugins {
    id("playground.java-conventions")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5")
    }
}

// Block the frameworks -domain must never depend on. configurations.all{} runs
// for every resolvable configuration so a stray transitive cannot sneak
// through. We do NOT exclude spring-core / spring-context (allowed for the
// @Service annotation in domain services) or spring-web (allowed compileOnly
// for the HttpStatus reference per ADR-11).
configurations.all {
    exclude(group = "org.springframework.boot")
    exclude(group = "org.springframework", module = "spring-webmvc")
    exclude(group = "org.springframework", module = "spring-webflux")
    exclude(group = "org.springframework.data")
    exclude(group = "org.springframework.kafka")
    exclude(group = "jakarta.persistence")
    exclude(group = "org.hibernate.orm")
}

dependencies {
    // shared-kernel — every BC's domain uses ErrorCode / AbstractException / EventEnvelope.
    "api"(project(":shared-kernel"))

    // Lombok is allowed per ADR-02 v2.
    "compileOnly"("org.projectlombok:lombok")
    "annotationProcessor"("org.projectlombok:lombok")
    "testCompileOnly"("org.projectlombok:lombok")
    "testAnnotationProcessor"("org.projectlombok:lombok")

    // Spring Context — @Service is permitted on domain services per ADR-02 v2.
    // Version managed by the imported BOM above.
    "compileOnly"("org.springframework:spring-context")

    // Spring Web is permitted compileOnly so domain exceptions may reference
    // org.springframework.http.HttpStatus per ADR-11. No runtime dependency on
    // a servlet/reactive stack.
    "compileOnly"("org.springframework:spring-web")

    // Tests in -domain are pure JUnit per ADR-01 v2.
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testImplementation"("org.assertj:assertj-core")
    "testImplementation"("org.mockito:mockito-core")
    "testImplementation"("org.mockito:mockito-junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
