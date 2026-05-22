plugins {
    id("playground.bc-domain")
}

dependencies {
    // M6.1 (ADR-12 §A12.1) — chunker dependencies inherited from the retired
    // rag-ingestion BC's -domain module. JTokkit (cl100k-base) is the
    // tokenizer the MarkdownAwareChunker uses; CommonMark + the GFM table
    // and strikethrough extensions are the only Markdown dialects M2's
    // corpus uses. All three are leaf algorithm libraries (zero Spring /
    // JPA / Kafka coupling) so they remain ADR-13 §4 compliant for a
    // -domain module.
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
}
