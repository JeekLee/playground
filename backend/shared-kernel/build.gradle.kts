plugins {
    id("playground.java-conventions")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5")
    }
}

// shared-kernel is a pure Java library — no Spring on the runtime classpath by
// default per ADR-01 v2 + ADR-03 (the event envelope is a record, not a Spring
// bean). M1 adds the shared error hierarchy (ADR-11): the AbstractException
// tree compiles against spring-web's HttpStatus, and SharedExceptionHandler
// against spring-webmvc — both as compileOnly so a -domain consumer doesn't
// drag the web stack onto its classpath, while a -api consumer (which already
// has spring-web at runtime) wires up the auto-configured advice via the
// AutoConfiguration.imports file.

dependencies {
    // HttpStatus reference in AbstractException + subclasses.
    compileOnly("org.springframework:spring-web")

    // @RestControllerAdvice, ResponseEntity in SharedExceptionHandler.
    compileOnly("org.springframework:spring-webmvc")

    // HttpServletRequest in SharedExceptionHandler (servlet API).
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    // @JsonInclude on ErrorResponse.
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")

    // @ConditionalOnMissingBean, Spring Boot auto-config infra.
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot")

    // SLF4J for logger calls in SharedExceptionHandler.
    compileOnly("org.slf4j:slf4j-api")

    // Validation exceptions referenced in SharedExceptionHandler.
    compileOnly("jakarta.validation:jakarta.validation-api")

    // Lombok ergonomics (records-equivalents).
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
