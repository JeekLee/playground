plugins {
    id("playground.bc-api")
}

dependencies {
    implementation(project(":docs:docs-app"))
    implementation(project(":docs:docs-domain"))
    runtimeOnly(project(":docs:docs-infra"))

    // M6.1 (ADR-12 §A12.1) — absorbed from rag-ingestion-api. The
    // DocumentEventListener (Kafka entry point for the in-BC ingestion
    // pipeline) lives in -api (analogous to controllers); the
    // @KafkaListener annotations need spring-kafka on the compile
    // classpath. The container factory + consumer config + DLQ recoverer
    // are in -infra.
    implementation("org.springframework.kafka:spring-kafka")
}
