# Multi-stage build for smaller image size
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Copy gradle wrapper from root
COPY gradlew .
COPY gradle/ gradle/
RUN chmod +x gradlew

# Copy only the signaling server module (standalone build)
COPY signaling-server/ signaling-server/

# Build standalone — settings.gradle.kts inside signaling-server/ scopes the build
WORKDIR /app/signaling-server
RUN ../gradlew shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=builder /app/signaling-server/build/libs/signaling-server-1.0.0-all.jar app.jar

EXPOSE 8090

ENV PORT=8090
# Security: Set these at deploy time (docker run -e or Cloud Run env vars):
#   SIGNALING_AUTH_TOKEN=<token>        — required for WebSocket authentication
#   CORS_ALLOWED_ORIGINS=<origins>      — comma-separated allowed origins (e.g. "https://sharex.teachmint.com")

ENTRYPOINT ["java", "-jar", "app.jar"]
