plugins {
    id("playground.bc-api")
}

// ADR-15 §1 + §2 — WebFlux end-to-end (no servlet stack imported). The bc-api
// convention plugin defaults to spring-boot-starter-web (Tomcat / servlet);
// strip it and the servlet-only OpenAPI starter so Netty + WebFlux own the
// runtime. Spring Security's reactive auto-config picks up SecurityWebFilterChain
// once it sees WebFlux on the classpath and Tomcat absent. M5 has no Kafka
// surface either (ADR-15 §2).
configurations.all {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    exclude(group = "org.springdoc", module = "springdoc-openapi-starter-webmvc-ui")
    exclude(group = "org.springframework.kafka")
    exclude(group = "org.springframework.modulith", module = "spring-modulith-events-kafka")
}

dependencies {
    implementation(project(":metrics:metrics-app"))
    implementation(project(":metrics:metrics-domain"))
    runtimeOnly(project(":metrics:metrics-infra"))

    // WebFlux replaces servlet starter-web. Netty is the default reactive
    // engine; jackson + validation already in the BOM.
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // WebFlux-aware Springdoc OpenAPI surface.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.6.0")

    // Reactor test support for the controller WebTestClient tests.
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
}
