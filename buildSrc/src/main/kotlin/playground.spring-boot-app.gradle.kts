// Convention for runnable Spring Boot apps (gateway today; every BC's *-api from M1).
// Per ADR-01 v2: bootJar enabled, plain `jar` disabled (fat-jar packaging only),
// Spring Boot + Spring Cloud + Spring AI BOMs imported.

plugins {
    id("playground.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.4")
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.0")
    }
}

tasks.named<Jar>("jar") {
    enabled = false
}
