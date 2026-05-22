plugins {
    id("playground.bc-api")
}

// M8 (ADR-18 §1) — massing-gen-api is a synchronous Spring MVC app. No
// reactive end-to-end requirement: the tool endpoint takes one request,
// makes 2 LLM/sidecar WebClient calls, persists a row, and returns. The
// /api/arch/outputs/{id} download streams BYTEA from Postgres — also
// synchronous-friendly.
//
// M8 has no Kafka surface; strip the Modulith event coordinates that
// bc-infra would otherwise drag onto -api via the runtime classpath
// (massing-gen-infra also excludes them so the runtime classpath stays
// symmetric — see massing-gen-infra/build.gradle.kts).
configurations.all {
    exclude(group = "org.springframework.kafka")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-kafka")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-starter-jpa")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-jpa")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-api")
}

dependencies {
    implementation(project(":massing-gen:massing-gen-app"))
    implementation(project(":massing-gen:massing-gen-domain"))
    runtimeOnly(project(":massing-gen:massing-gen-infra"))

    // M8 issues outbound WebClient calls to docs-api + rhino3dm-bridge from
    // -infra. WebClient is part of WebFlux — the reactive starter is pulled
    // in here so the bean is available at runtime without making the BC
    // reactive end-to-end (Spring MVC is still the request transport).
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
