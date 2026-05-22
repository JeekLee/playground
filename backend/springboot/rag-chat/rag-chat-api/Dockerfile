# Multi-stage build for the rag-chat-api Spring Boot fat jar per ADR-14 §A.
# Build context: `backend/` (the Gradle root). Invoked via infra/docker-compose.yml.

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the wrapper and settings first to leverage Docker layer caching for the
# Gradle distribution + buildSrc compilation.
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY buildSrc buildSrc

# Copy the modules required to build :rag-chat:rag-chat-api (the quadruplet +
# shared-kernel).
COPY shared-kernel shared-kernel
COPY rag-chat rag-chat

RUN chmod +x gradlew && ./gradlew :rag-chat:rag-chat-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/rag-chat/rag-chat-api/build/libs/*.jar app.jar
EXPOSE 18084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
