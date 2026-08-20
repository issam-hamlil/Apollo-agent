# ─────────────────────────────────────────────────────────────────────────────
# Apollo Agent — Multi-Stage Dockerfile
#
# Stage 1 (builder): Gradle build → produces fat JAR
# Stage 2 (runtime): Minimal JRE image → runs the fat JAR
#
# Build:  docker build -t apollo-local-test .
# Run:    docker run -p 8080:8080 --env-file .env apollo-local-test
#         (add --args="--server" override via CMD or run entrypoint variant)
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /app

# Copy Gradle wrapper and dependency declarations first (layer-cache friendly)
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./

# Pre-fetch all dependencies so this layer is cached when only source changes
RUN ./gradlew dependencies --no-daemon -q || true

# Copy source tree and build the fat JAR
COPY src/ src/

# installDist produces a standalone directory with all deps and a launch script,
# which is more reliable than a shadow/uber-jar across Kotlin toolchain versions.
RUN ./gradlew installDist --no-daemon -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL org.opencontainers.image.title="Apollo Agent"
LABEL org.opencontainers.image.description="Autonomous Java-to-Kotlin migration engine with MCP server"
LABEL org.opencontainers.image.version="1.0.0"

WORKDIR /app

# Copy the installDist output from the builder stage
COPY --from=builder /app/build/install/apollo-agent/ ./

# Copy static assets the pipeline reads at runtime
COPY knowledge-base/ knowledge-base/
COPY sample-legacy/  sample-legacy/

# Expose MCP / HTTP port
EXPOSE 8080

# Default: run in CLI mode.
# Override at `docker run` time with:
#   docker run ... apollo-local-test bin/apollo-agent --server
ENTRYPOINT ["bin/apollo-agent"]
CMD ["sample-legacy"]
