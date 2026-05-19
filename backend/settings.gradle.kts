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

// M2 — docs quadruplet (ADR-01 v2 + ADR-12). S1 ships single-author CRUD only;
// search projector / events / engagement counters land in M2 S2+.
include(":docs:docs-api")
include(":docs:docs-app")
include(":docs:docs-domain")
include(":docs:docs-infra")

// M3 — rag-ingestion quadruplet (ADR-01 v2 + ADR-13). Backend-only BC: no
// public HTTP surface (ADR-13 §A — actuator-only on 18083). Consumes the
// three docs.document.* topics, writes pgvector chunks, emits
// rag.document.ingested.
include(":rag-ingestion:rag-ingestion-api")
include(":rag-ingestion:rag-ingestion-app")
include(":rag-ingestion:rag-ingestion-domain")
include(":rag-ingestion:rag-ingestion-infra")

// M4 — rag-chat quadruplet (ADR-01 v2 + ADR-14). WebFlux SSE controller on
// port 18084 (gateway-routable). Cross-schema SELECT into rag/docs/identity
// for retrieval + citation enrichment + display-name; no Kafka surface.
include(":rag-chat:rag-chat-api")
include(":rag-chat:rag-chat-app")
include(":rag-chat:rag-chat-domain")
include(":rag-chat:rag-chat-infra")

// M5 — metrics quadruplet (ADR-01 v2 + ADR-15). WebFlux end-to-end on port
// 18085 (gateway-routable). Stateless BC: no Postgres schema, no Kafka surface.
// Talks to Prometheus + Loki + spark-inference-gateway over HTTP via WebClient.
include(":metrics:metrics-api")
include(":metrics:metrics-app")
include(":metrics:metrics-domain")
include(":metrics:metrics-infra")
