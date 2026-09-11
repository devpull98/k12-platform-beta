# Multi-stage Dockerfile for uni-websocket-gateway
# Build context MUST be the repo root:
#   docker build -t uni-websocket-gateway:latest -f modules/uni-websocket-gateway/Dockerfile .

# ==========================================
# STAGE 1: Build stage with JDK 25 & Maven
# ==========================================
FROM eclipse-temurin:25-jdk-alpine AS builder
RUN apk add --no-cache maven

WORKDIR /build

# Copy root pom and module poms for dependency caching
COPY pom.xml .
COPY modules/uni-protocol/pom.xml modules/uni-protocol/
COPY modules/uni-observability/pom.xml modules/uni-observability/
COPY modules/uni-game-engine/pom.xml modules/uni-game-engine/
COPY modules/uni-websocket-gateway/pom.xml modules/uni-websocket-gateway/
COPY modules/uni-e2e/pom.xml modules/uni-e2e/

# Copy source modules required by uni-websocket-gateway
COPY modules/uni-protocol modules/uni-protocol
COPY modules/uni-observability modules/uni-observability
COPY modules/uni-websocket-gateway modules/uni-websocket-gateway

# Build executable jar for uni-websocket-gateway module
RUN mvn clean package -pl modules/uni-websocket-gateway -am -DskipTests

# ==========================================
# STAGE 2: Production JRE Runtime Image
# ==========================================
FROM eclipse-temurin:25-jre-alpine

LABEL maintainer="K12 Realtime Gateway Team"
LABEL description="Optimized Netty WebSocket Gateway Service Container"

# Create non-root user for container security hardening
RUN addgroup -g 10001 appgroup && \
    adduser -u 10001 -G appgroup -h /app -D appuser

WORKDIR /app
RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

# Copy built artifact from builder stage
COPY --from=builder --chown=appuser:appgroup /build/modules/uni-websocket-gateway/target/uni-websocket-gateway-0.0.1-SNAPSHOT-exec.jar /app/app.jar

USER appuser:appgroup

# JVM Container Tuning Options
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080 9000

HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=3 \
  CMD nc -z localhost 9000 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
