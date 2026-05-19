plugins {
    id("playground.bc-app")
}

dependencies {
    "api"(project(":metrics:metrics-domain"))

    // Reactor Core for use cases that return Mono / Flux composed by the
    // WebFlux controllers (ADR-15 §2 — WebFlux end-to-end). The bc-app
    // convention plugin excludes spring-webflux; reactor-core is its own
    // coordinate so it remains independently importable. Version is managed
    // by the Spring Boot BOM imported in the convention plugin.
    "implementation"("io.projectreactor:reactor-core")

    // Jackson annotations for @JsonInclude on response DTOs. The DTO records
    // are pure value objects (no JSON parser pulled in here — the -api / -infra
    // modules ship Jackson via the WebFlux starter); only the annotation jar
    // is on the -app classpath.
    "implementation"("com.fasterxml.jackson.core:jackson-annotations")
}
