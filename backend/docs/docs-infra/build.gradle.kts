plugins {
    id("playground.bc-infra")
}

// M6 (ADR-16) — Spring AI BOM for the Vision OCR fallback path. Same 1.0.0 GA
// line that rag-chat (M4) and rag-ingestion (M3) pin per ADR-04 — keeps the
// OpenAI-compatible spark-inference-gateway client coherent across the stack.
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
    // fallback. Same starter rag-chat (M4) uses — wires a `ChatClient.Builder`
    // bean backed by spark-inference-gateway's `/v1/chat/completions` endpoint.
    // The Vision call uses `Media`-attached UserMessage (multimodal).
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // For the Testcontainers-driven integration test we need the -api class as
    // the @SpringBootTest entrypoint.
    testImplementation(project(":docs:docs-api"))
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
