# Multi-stage build for the docs-api Spring Boot fat jar.
# Build context: `backend/` (the Gradle root). Invoked via infra/docker-compose.yml.

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the wrapper and settings first to leverage Docker layer caching for the
# Gradle distribution + buildSrc compilation.
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY buildSrc buildSrc

# Copy the modules required to build :docs:docs-api (the quadruplet +
# shared-kernel).
COPY shared-kernel shared-kernel
COPY docs docs

RUN chmod +x gradlew && ./gradlew :docs:docs-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/docs/docs-api/build/libs/*.jar app.jar
EXPOSE 18082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
