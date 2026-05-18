# Multi-stage build for the rag-ingestion-api Spring Boot fat jar per ADR-13 §B.
# Build context: `backend/` (the Gradle root). Invoked via infra/docker-compose.yml.

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the wrapper and settings first to leverage Docker layer caching for the
# Gradle distribution + buildSrc compilation.
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY buildSrc buildSrc

# Copy the modules required to build :rag-ingestion:rag-ingestion-api (the
# quadruplet + shared-kernel).
COPY shared-kernel shared-kernel
COPY rag-ingestion rag-ingestion

RUN chmod +x gradlew && ./gradlew :rag-ingestion:rag-ingestion-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/rag-ingestion/rag-ingestion-api/build/libs/*.jar app.jar
EXPOSE 18083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
