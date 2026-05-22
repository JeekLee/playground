plugins {
    id("playground.bc-infra")
}

// M8 (ADR-18 §4) — Spring AI BOM for the BriefProgramExtractor LLM call.
// Same 1.0.0 GA line as M4 / M6 / M7. The starter brings ChatClient backed
// by spark-inference-gateway via the OpenAI-compatible endpoint.
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.0")
    }
}

// M8 has no Kafka surface (ADR-18 §Context — synchronous HTTP only). Strip
// the Kafka coordinates the bc-infra convention plugin pulls in so Spring
// Boot auto-config does not try to wire a KafkaTemplate at startup.
configurations.all {
    exclude(group = "org.springframework.kafka")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-kafka")
    // ADR-18 §A05.7 — massing-gen does NOT use Spring Modulith JPA outbox;
    // there are no domain events to externalize. Exclude both the JPA event
    // store and the events-api transitive.
    exclude(group = "org.springframework.modulith", module = "spring-modulith-starter-jpa")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-jpa")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-api")
}

dependencies {
    implementation(project(":massing-gen:massing-gen-domain"))
    implementation(project(":massing-gen:massing-gen-app"))

    // ADR-18 §4 — Spring AI OpenAI-compatible chat client. Same starter the
    // rag-chat (M4) and docs (M6 Vision) services use. Used by
    // SpringAiBriefExtractorAdapter for the brief→programJson extraction.
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // ADR-18 §17 — Resilience4j circuit breaker (rhino3dm-bridge instance)
    // wrapping the Rhino3dmAdapter sidecar HTTP call. resilience4j-reactor
    // gives CircuitBreakerOperator for WebClient Mono. Reuses the shared
    // spark-gateway breaker (auto-registered by the spring-boot3 starter
    // reading application.yml).
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.2.0")

    // ADR-18 §9 — JSON Schema validator (also referenced by -app; pulled in
    // here for the SpringAiBriefExtractorAdapter's parse-and-validate path).
    implementation("com.networknt:json-schema-validator:1.5.3")

    // Micrometer for breaker metric publication on /actuator/prometheus.
    implementation("io.micrometer:micrometer-core")

    // M8 integration tests need a fully assembled -api artifact.
    testImplementation(project(":massing-gen:massing-gen-api"))

    // WireMock stubs for docs-api, spark-inference-gateway, rhino3dm-bridge
    // sidecar — mirrors M4/M6/M7's integration test scaffolding.
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
}

// Integration tests opt in via -PintegrationTests, matching the rag-chat /
// docs convention so a Docker-less build still passes `./gradlew build`.
tasks.named<Test>("test") {
    val runIntegration = providers.gradleProperty("integrationTests").getOrElse("false") == "true"
    useJUnitPlatform {
        if (!runIntegration) {
            excludeTags("integration")
        }
    }
}
