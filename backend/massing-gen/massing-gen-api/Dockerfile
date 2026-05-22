# Multi-stage build for the massing-gen-api Spring Boot fat jar.
# Build context: `backend/` (the Gradle root). Invoked via infra/docker-compose.yml.

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the wrapper and settings first to leverage Docker layer caching for the
# Gradle distribution + buildSrc compilation.
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY buildSrc buildSrc

# Copy the modules required to build :massing-gen:massing-gen-api (the M8
# quadruplet + shared-kernel).
COPY shared-kernel shared-kernel
COPY massing-gen massing-gen

RUN chmod +x gradlew && ./gradlew :massing-gen:massing-gen-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /workspace/massing-gen/massing-gen-api/build/libs/*.jar app.jar
EXPOSE 18083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
