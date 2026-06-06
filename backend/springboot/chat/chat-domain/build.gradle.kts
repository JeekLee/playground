plugins {
    id("playground.bc-domain")
}

dependencies {
    // JTokkit for token-budget bookkeeping (cl100k-base), per ADR-14 §B.
    // Mirrors M3's rag-ingestion-domain dependency on the same coordinate.
    implementation("com.knuddels:jtokkit:1.1.0")
}
