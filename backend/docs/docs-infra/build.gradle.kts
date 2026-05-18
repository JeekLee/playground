plugins {
    id("playground.bc-infra")
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
