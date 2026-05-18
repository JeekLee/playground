plugins {
    id("playground.bc-api")
}

dependencies {
    implementation(project(":rag-ingestion:rag-ingestion-app"))
    implementation(project(":rag-ingestion:rag-ingestion-domain"))
    runtimeOnly(project(":rag-ingestion:rag-ingestion-infra"))

    // ADR-13 §4 — Kafka @KafkaListener entry points live in -api (the BC's
    // outermost adapter, analogous to controllers). The container factory +
    // consumer config + DLQ recoverer are in -infra; this dep is just for
    // the listener annotations on the entry-point methods.
    implementation("org.springframework.kafka:spring-kafka")
}
