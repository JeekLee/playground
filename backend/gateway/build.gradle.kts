plugins {
    id("playground.spring-boot-app")
}

dependencies {
    implementation(libs.spring.cloud.starter.gateway)
    implementation(libs.spring.boot.starter.actuator)

    // M0 ships a route-less gateway shell. OAuth2 Client + filters land in M1
    // (per ADR-07 + ADR-10).
}
