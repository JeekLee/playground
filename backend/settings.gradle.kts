rootProject.name = "playground"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Foundation modules — present from M0.
include(":gateway")
include(":shared-kernel")

// BC modules — included per milestone as quadruplets per ADR-01 v2.
// M1 (identity):
// include(":identity:identity-api")
// include(":identity:identity-app")
// include(":identity:identity-domain")
// include(":identity:identity-infra")
//
// M2 (docs), M3 (rag-ingestion), M4 (rag-chat), M5 (metrics): same pattern.
