plugins {
    id("playground.bc-infra")
}

// ADR-14 §B — Spring AI BOM for Qwen3-32B chat streaming + BGE-M3 query
// embedding. Same 1.0.0 GA line as M3 (ADR-13 §C).
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.0")
    }
}

// M4 has no Kafka surface (ADR-14 §1.5). The bc-infra convention plugin pulls
// in spring-kafka + spring-modulith-events-kafka for every BC; exclude them
// here so Spring Boot autoconfig doesn't try to wire a KafkaTemplate /
// ProducerFactory / Modulith Kafka externalizer at startup (which would fail
// without a kafka bootstrap configured).
configurations.all {
    exclude(group = "org.springframework.kafka")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-kafka")
}

dependencies {
    implementation(project(":rag-chat:rag-chat-domain"))
    implementation(project(":rag-chat:rag-chat-app"))

    // ADR-14 §B — Spring AI OpenAI-compatible chat + embedding client backed
    // by spark-inference-gateway (Qwen3-32B chat at /v1/chat/completions and
    // BGE-M3 embeddings at /v1/embeddings). The starter brings ChatClient,
    // ChatModel and EmbeddingModel beans plus OpenAI auto-configuration.
    // Note: Spring AI 1.0.0 GA renamed the starter to spring-ai-starter-model-openai.
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // ADR-14 §4 — Resilience4j 2.2.x circuit breaker shared by the
    // ChatGenerationPort + EmbeddingPort adapters (breaker name spark-gateway).
    // resilience4j-reactor is needed for CircuitBreakerOperator on Flux.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.2.0")

    // ADR-14 §5 + §D — Redisson for RRateLimiter (token bucket) + RLock
    // (per-user concurrent stream cap). Same 3.34.x line as M3 per ADR-13 §C.
    implementation("org.redisson:redisson-spring-boot-starter:3.34.1")

    // ADR-14 §B — pgvector type binding for the retrieval JDBC adapter's
    // parameter binding (`?::vector` with PGvector.toString()), same coordinate
    // M3 uses for chunk inserts.
    implementation("com.pgvector:pgvector:0.1.6")

    // Micrometer metrics surface — Resilience4j circuit breaker metrics
    // (`resilience4j_circuitbreaker_*`) per ADR-14 §4 register here.
    implementation("io.micrometer:micrometer-core")

    // M4 integration tests need a fully assembled -api artifact.
    testImplementation(project(":rag-chat:rag-chat-api"))

    // WireMock for spark-inference-gateway stubs (chat streaming + embeddings).
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
}

// Integration tests opt in via -PintegrationTests, matching the rag-ingestion
// convention so a Docker-less build of the rag-chat quadruplet still passes
// `./gradlew build`.
tasks.named<Test>("test") {
    val runIntegration = providers.gradleProperty("integrationTests").getOrElse("false") == "true"
    useJUnitPlatform {
        if (!runIntegration) {
            excludeTags("integration")
        }
    }
}
