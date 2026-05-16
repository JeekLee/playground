// Convention for *-api modules per ADR-01 v2 / ADR-02 v2.
//
// -api is the inbound HTTP adapter — runnable Spring Boot app, servlet stack
// (MVC) per ADR-10 §1 ("identity has no reactive requirement"). Owns
// controllers, request/response DTOs, OpenAPI configs, and the
// @SpringBootApplication entrypoint.

plugins {
    id("playground.spring-boot-app")
}

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter-web")
    "implementation"("org.springframework.boot:spring-boot-starter-validation")
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("org.springframework.boot:spring-boot-starter-security")
    "implementation"("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    "compileOnly"("org.projectlombok:lombok")
    "annotationProcessor"("org.projectlombok:lombok")
    "testCompileOnly"("org.projectlombok:lombok")
    "testAnnotationProcessor"("org.projectlombok:lombok")

    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.springframework.security:spring-security-test")
}
