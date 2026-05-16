rootProject.name = "playground"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions JDK 21 if the launcher JVM doesn't satisfy the toolchain.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Foundation modules — present from M0.
include(":gateway")
include(":shared-kernel")

// M1 — identity quadruplet (ADR-01 v2 + ADR-10).
include(":identity:identity-api")
include(":identity:identity-app")
include(":identity:identity-domain")
include(":identity:identity-infra")

// M2 (docs), M3 (rag-ingestion), M4 (rag-chat), M5 (metrics): same pattern.
