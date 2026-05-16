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
include(":api:gateway")
include(":api:shared-kernel")

// BC modules — included per milestone as quadruplets per ADR-01 v2.
// M1 (identity):
// include(":api:identity:identity-api")
// include(":api:identity:identity-app")
// include(":api:identity:identity-domain")
// include(":api:identity:identity-infra")
//
// M2 (docs), M3 (rag-ingestion), M4 (rag-chat), M5 (metrics): same pattern.
