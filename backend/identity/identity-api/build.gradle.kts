plugins {
    id("playground.bc-api")
}

dependencies {
    implementation(project(":identity:identity-app"))
    implementation(project(":identity:identity-domain"))
    runtimeOnly(project(":identity:identity-infra"))
}


