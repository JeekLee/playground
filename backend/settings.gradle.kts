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
// search projector / events / engagement counters land in M2 S2+. M6.1
// (ADR-12 amendment §A12.1 + ADR-01 §A01.1) absorbs the retired rag-ingestion
// BC's domain/app/infra packages into the docs-* quadruplet under the
// `ingestion` subpackage — no new modules, just heavier docs ones.
include(":docs:docs-api")
include(":docs:docs-app")
include(":docs:docs-domain")
include(":docs:docs-infra")

// M3 — rag-ingestion quadruplet RETIRED by M6.1 (ADR-01 §A01.1 +
// ADR-12 §A12.1). The four modules collapsed into docs-* (port 18083
// returned to the reservation pool). The packages live under
// `com.playground.docs.ingestion.*` (domain/app) and
// `com.playground.docs.infrastructure.ingestion.*` (infra) inside docs-api's
// single JVM.

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

// M8 — massing-gen quadruplet (ADR-01 §A01.6 + ADR-18). Spring MVC -api on
// port 18083 (reclaimed from the retired rag-ingestion-api per ADR-18 §2).
// Owns the `arch` schema (single table arch.outputs holding generated .3dm
// binaries as BYTEA). Two endpoints: POST /internal/tools/generate-massing
// (rag-chat-api → Exception 4 sub-row per ADR-08 §A08.11) and
// GET /api/arch/outputs/{id} (owner-only download via gateway routing).
// Talks to docs-api over HTTP (ADR-08 §A08.12 Exception 5) and to the
// rhino3dm-bridge sidecar over HTTP. No Kafka surface.
include(":massing-gen:massing-gen-api")
include(":massing-gen:massing-gen-app")
include(":massing-gen:massing-gen-domain")
include(":massing-gen:massing-gen-infra")
