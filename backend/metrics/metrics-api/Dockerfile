# Multi-stage build for the metrics-api Spring Boot fat jar per ADR-15 §1.
# Build context: `backend/` (the Gradle root). Invoked via infra/docker-compose.yml.

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the wrapper and settings first to leverage Docker layer caching for the
# Gradle distribution + buildSrc compilation.
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY buildSrc buildSrc

# Copy the modules required to build :metrics:metrics-api (the quadruplet +
# shared-kernel).
COPY shared-kernel shared-kernel
COPY metrics metrics

RUN chmod +x gradlew && ./gradlew :metrics:metrics-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/metrics/metrics-api/build/libs/*.jar app.jar
EXPOSE 18085
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
