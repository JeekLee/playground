plugins {
    id("playground.bc-domain")
}

dependencies {
    // ADR-13 §1 — JTokkit (cl100k-base) tokenizer for the markdown chunker.
    // Pure-Java, zero transitive deps; allowed in -domain per ADR-13 §4 since
    // it is a leaf algorithm library (no Spring / JPA / Kafka coupling).
    implementation("com.knuddels:jtokkit:1.1.0")
}
