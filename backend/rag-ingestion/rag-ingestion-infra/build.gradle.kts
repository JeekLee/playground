plugins {
    id("playground.bc-infra")
}

// The bc-infra convention imports the Spring Boot BOM only. M3 also needs the
// Spring AI BOM so the spring-ai-openai-spring-boot-starter coordinate resolves
// without an explicit version pin (ADR-13 §C — Spring AI 1.0.0 GA).
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.0")
    }
}

dependencies {
    implementation(project(":rag-ingestion:rag-ingestion-domain"))
    implementation(project(":rag-ingestion:rag-ingestion-app"))

    // ADR-13 §C — Redisson distributed lock (ADR-08 Exception 2). The Spring
    // Boot starter wires a Spring-managed RedissonClient bean from
    // application.yml properties (spring.data.redis.host / port).
    implementation("org.redisson:redisson-spring-boot-starter:3.34.1")

    // ADR-13 §C / ADR-04 — Spring AI OpenAI-compatible embedding client backed
    // by spark-inference-gateway's BGE-M3 endpoint. The starter brings the
    // EmbeddingModel bean and OpenAI auto-configuration.
    //
    // Note: Spring AI 1.0.0 GA renamed the starter modules from
    // `spring-ai-openai-spring-boot-starter` to `spring-ai-starter-model-openai`
    // (the project-wide "spring-ai-starter-*" prefix). ADR-13 §C lists the
    // old name; the new coordinate is the one that resolves under
    // Spring AI 1.0.0 BOM.
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // Force RestClient to use Apache HttpClient5 (HTTP/1.1 default) instead of
    // the JDK HttpClient (HTTP/2 default). spark-inference-gateway's vLLM 0.19
    // backend is HTTP/1.1-only — JDK HttpClient's h2c upgrade attempt is rejected
    // with `Invalid HTTP request received.` and surfaces as HTTP 400.
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // ADR-13 §F — pgvector type bindings (Java client + Hibernate
    // dialect helpers). Used by the JPA adapter's native-SQL inserts.
    implementation("com.pgvector:pgvector:0.1.6")

    // Micrometer metrics surface (ADR-13 §6) — registered automatically by
    // spring-boot-starter-actuator (in -api), but micrometer-core types are
    // referenced in -infra to construct Timer / Counter beans.
    implementation("io.micrometer:micrometer-core")

    // M3 integration tests need a fully assembled -api artifact (matches the
    // docs-infra pattern that runs against DocsApplication.class).
    testImplementation(project(":rag-ingestion:rag-ingestion-api"))

    // WireMock pinned per ADR-13 §13 — stubs docs-api /internal/** and the
    // spark-inference-gateway during integration tests.
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
}

// Integration tests opt in via -PintegrationTests. Default build runs only
// unit tests so a Docker-less environment still passes `./gradlew build`.
tasks.named<Test>("test") {
    val runIntegration = providers.gradleProperty("integrationTests").getOrElse("false") == "true"
    useJUnitPlatform {
        if (!runIntegration) {
            excludeTags("integration")
        }
    }
}
