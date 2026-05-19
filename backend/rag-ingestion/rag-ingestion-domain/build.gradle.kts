plugins {
    id("playground.bc-domain")
}

dependencies {
    // ADR-13 §1 — JTokkit (cl100k-base) tokenizer for the markdown chunker.
    // Pure-Java, zero transitive deps; allowed in -domain per ADR-13 §4 since
    // it is a leaf algorithm library (no Spring / JPA / Kafka coupling).
    implementation("com.knuddels:jtokkit:1.1.0")

    // ADR-13 §1 (M3.1 amendment) — CommonMark AST parser for the markdown-aware
    // chunker. Same "leaf algorithm library" rationale as JTokkit; no Spring
    // / Jackson / framework coupling. GFM tables + strikethrough extensions are
    // the only dialects the M2 corpus uses today.
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
}
