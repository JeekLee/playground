plugins {
    id("playground.bc-infra")
}

// M6 (ADR-16) — Spring AI BOM for the Vision OCR fallback path. Same 1.0.0 GA
// line that chat (M4) and the (M6.1-absorbed) ingestion pipeline pin per
// ADR-04 — keeps the OpenAI-compatible spark-inference-gateway client coherent
// across the stack.
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.0")
    }
}

dependencies {
    implementation(project(":docs:docs-domain"))
    implementation(project(":docs:docs-app"))

    // M2 S2: OpenSearch projection (ADR-12 §5). Native client uses the Apache
    // HttpClient 5 transport — pulled in explicitly because the BOM-managed
    // version that lands on runtimeClasspath does not surface on the
    // compileClasspath we need for OpenSearchClientConfig.
    implementation("org.opensearch.client:opensearch-java:2.10.4")
    implementation("org.apache.httpcomponents.core5:httpcore5:5.2.5")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")

    // M2 S2: Caffeine-backed cache for identity lookups + owner resolution.
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // M2 S3 (ADR-12 §10): Redis-backed view dedup. The Servlet-stack starter
    // (non-reactive) matches docs-api's WebMvc transport — the reactive
    // starter the gateway uses pulls in Netty and conflicts with Tomcat's
    // servlet container.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // M6 (ADR-16): Apache PDFBox 3.x for PDF text-layer extraction
    // (PDFTextStripper) and image rendering (PDFRenderer) for the Vision OCR
    // fallback path. The `pdfbox-tools` artifact is intentionally excluded
    // since the CLI helpers are not used at runtime.
    implementation(libs.pdfbox)

    // M6 (ADR-16): Spring AI OpenAI-compatible client for the Vision OCR
    // fallback. Same starter chat (M4) uses — wires a `ChatClient.Builder`
    // bean backed by spark-inference-gateway's `/v1/chat/completions` endpoint.
    // The Vision call uses `Media`-attached UserMessage (multimodal).
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // M6.1 (ADR-12 §A12.4): MinIO Java SDK for the docs source-blob store.
    // Streams uploads/downloads via the multipart S3 API — no in-heap
    // byte[] materialization.
    implementation(libs.minio)

    // M6.1 (ADR-12 §A12.1) — absorbed from the retired rag-ingestion-infra
    // module. Redisson Spring Boot starter wires a Spring-managed
    // RedissonClient bean from application.yml properties; the
    // RedissonDistributedLockAdapter uses it for the docs:lock:* namespace
    // (ADR-08 §A08.2 — renamed from rag-ingestion:lock:*).
    implementation("org.redisson:redisson-spring-boot-starter:3.34.1")

    // M6.1 (ADR-12 §A12.1) — absorbed from rag-ingestion-infra. pgvector
    // type bindings for the JPA adapter's native-SQL inserts into
    // docs.document_chunks.
    implementation("com.pgvector:pgvector:0.1.6")

    // M6.1 (ADR-12 §A12.1) — absorbed from rag-ingestion-infra. micrometer-core
    // types are referenced in -infra to construct Timer / Counter beans for the
    // chunker metrics adapter; the actuator pulls in the registry separately.
    implementation("io.micrometer:micrometer-core")

    // For the Testcontainers-driven integration test we need the -api class as
    // the @SpringBootTest entrypoint.
    testImplementation(project(":docs:docs-api"))

    // M6.1 — absorbed from rag-ingestion-infra. WireMock pinned per ADR-13
    // §13 stubs spark-inference-gateway during integration tests. The
    // (now retired) docs-api /internal/** stub remains available for the
    // ReembedCommandLineRunner integration test.
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
