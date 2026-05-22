plugins {
    id("playground.bc-app")
}

dependencies {
    "api"(project(":rag-chat:rag-chat-domain"))

    // Reactor Core for the streaming use-case shape (Flux<ServerSentEvent>
    // returned from the SSE controller; the orchestrator's ports return
    // Flux / Mono). The bc-app convention plugin excludes spring-webflux but
    // reactor-core is its own coordinate, not a Spring module, so it is
    // independently importable. Version is managed by the Spring Boot BOM
    // imported in the convention plugin.
    "implementation"("io.projectreactor:reactor-core")

    // ADR-17 §1 + §8 — Jackson databind for the tool-dispatch port surface.
    // The port carries LLM-produced `args` and tool-BC `result` as JsonNode so
    // the wire shape is structure-preserving across the controller/service/
    // adapter boundary. Jackson is not a Spring module so bc-app's exclusion
    // list does not block it. Version comes from the Spring Boot BOM.
    "implementation"("com.fasterxml.jackson.core:jackson-databind")
}
