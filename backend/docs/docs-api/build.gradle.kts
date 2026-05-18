plugins {
    id("playground.bc-api")
}

dependencies {
    implementation(project(":docs:docs-app"))
    implementation(project(":docs:docs-domain"))
    runtimeOnly(project(":docs:docs-infra"))
}
