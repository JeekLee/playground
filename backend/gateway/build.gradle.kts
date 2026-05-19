plugins {
    id("playground.spring-boot-app")
}

// Pin Spring Cloud Gateway to 4.1.5 — Spring Cloud BOM 2023.0.4 ships SCG 4.1.6,
// whose ForwardedHeadersFilter calls HttpHeaders.headerSet() which was added in
// Spring Framework 6.2. Spring Boot 3.3.5 (matched by our BOM) ships Spring
// Framework 6.1.14, so the call sites throw NoSuchMethodError at runtime. 4.1.5
// is the last SCG release in the 2023.0 line that stays on the 6.1 API surface.
dependencyManagement {
    dependencies {
        dependencySet("org.springframework.cloud:4.1.5") {
            entry("spring-cloud-starter-gateway")
            entry("spring-cloud-gateway-server")
            entry("spring-cloud-gateway-mvc")
        }
    }
}

dependencies {
    implementation(libs.spring.cloud.starter.gateway)
    implementation(libs.spring.boot.starter.actuator)
    // Wires /actuator/prometheus for the M5 dashboard's Alloy scrape (per
    // ADR-15). The bc-api convention plugin pulls this in for every BC -api
    // module; gateway uses spring-boot-app directly so we add it here.
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.data.redis.reactive)
    implementation(libs.spring.session.data.redis)

    // shared-kernel provides AbstractException / ErrorResponse / EventEnvelope.
    implementation(project(":shared-kernel"))

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.wiremock.standalone)
    testImplementation("io.projectreactor:reactor-test")
}
