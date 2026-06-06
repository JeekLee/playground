plugins {
    id("playground.bc-api")
}

// ADR-14 §1 — M4 is the first WebFlux-end-to-end BC. The bc-api convention
// plugin defaults to spring-boot-starter-web (Tomcat / servlet) so the SSE
// streaming path would block a request thread per stream. Strip the servlet
// stack here and add the reactive starters. Spring Security still ships via
// the bc-api defaults but the auto-config picks the reactive flavour once it
// sees WebFlux on the classpath and Tomcat absent.
configurations.all {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    exclude(group = "org.springdoc", module = "springdoc-openapi-starter-webmvc-ui")
    // M4 has no Kafka surface — see chat-infra/build.gradle.kts for the
    // same exclusion. Kept here so the runtime classpath is symmetric.
    exclude(group = "org.springframework.kafka")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-kafka")
}

dependencies {
    implementation(project(":chat:chat-app"))
    implementation(project(":chat:chat-domain"))
    runtimeOnly(project(":chat:chat-infra"))

    // WebFlux replaces servlet starter-web. Netty is the default reactive
    // engine; jackson + validation already in the BOM.
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // WebFlux-aware Springdoc OpenAPI surface.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.6.0")

    // Reactor test support for the SSE controller WebTestClient tests.
    testImplementation("io.projectreactor:reactor-test")
}
