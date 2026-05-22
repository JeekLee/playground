plugins {
    id("playground.bc-infra")
}

dependencies {
    implementation(project(":identity:identity-domain"))
    implementation(project(":identity:identity-app"))

    // For the Testcontainers-driven integration test we need the -api class as
    // the @SpringBootTest entrypoint.
    testImplementation(project(":identity:identity-api"))
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
