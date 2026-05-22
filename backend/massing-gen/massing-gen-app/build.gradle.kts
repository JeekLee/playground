plugins {
    id("playground.bc-app")
}

dependencies {
    "api"(project(":massing-gen:massing-gen-domain"))

    // M8 (ADR-18 §9) — JSON Schema validator for the LLM-produced programJson.
    // networknt:json-schema-validator is Jackson-native (no separate org.json
    // dependency) and supports JSON Schema Draft 2020-12. Pulled into -app so
    // the BriefProgramExtractor port can validate before returning to the
    // use case. Jackson databind is transitively available via the Spring Boot
    // BOM imported by the bc-app convention plugin.
    "implementation"("com.networknt:json-schema-validator:1.5.3")
    "implementation"("com.fasterxml.jackson.core:jackson-databind")
}
