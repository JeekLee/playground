# Multi-stage build for the gateway Spring Boot fat jar.
# Build context: monorepo root. Invoked via infra/docker-compose.yml.

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the wrapper and settings first to leverage Docker layer caching for the
# Gradle distribution + buildSrc compilation.
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY buildSrc buildSrc

# Copy the modules needed to build :api:gateway.
COPY api/gateway api/gateway
COPY api/shared-kernel api/shared-kernel

RUN chmod +x gradlew && ./gradlew :api:gateway:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/api/gateway/build/libs/*.jar app.jar
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
