# syntax=docker/dockerfile:1

# ---- Build stage -----------------------------------------------------------
# Pinned Maven + JDK 21 image; verified with `docker manifest inspect`.
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace

# Copy only the POM first so the dependency layer is reused when source changes.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

# Now copy the source and build the executable Spring Boot jar.
COPY src ./src
RUN mvn -B -ntp -DskipTests package

# ---- Runtime stage ---------------------------------------------------------
# Slim JRE 21 on Ubuntu Jammy; verified with `docker manifest inspect`.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# The JVM and the container both use UTC. Timestamps are stored as TIMESTAMPTZ.
ENV TZ=UTC

# Run as a non-root user. No source, Maven cache or secrets exist in this stage.
RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /workspace/target/*.jar /app/app.jar

USER app

# Documentation only; Render routes to the PORT it injects (usually 10000).
EXPOSE 8080

# -Duser.timezone=UTC is explicit even if the host does not set TZ.
# Optional on small hosts: set JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 at runtime.
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
