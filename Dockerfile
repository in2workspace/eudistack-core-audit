# ---------------------------------------------------------------------------
# Multi-stage build of the audit service. One image; the role is AUDIT_ROLE
# (writer | reader | archiver | provisioner), never `all` outside local and CI.
# ---------------------------------------------------------------------------

# --- Stage 1: build --------------------------------------------------------
FROM docker.io/gradle:9.7.1-jdk25 AS build
WORKDIR /workspace
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY config ./config
COPY src ./src
# Tests run in CI (unit + Testcontainers); the image build only packages.
RUN gradle bootJar --no-daemon

# --- Stage 2: runtime ------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S nonroot && adduser -S nonroot -G nonroot
USER nonroot
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/audit.jar
# 8080: API (writer or reader). 8081: actuator, never published on the ingress (SAD-DEC-010).
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/audit.jar"]
