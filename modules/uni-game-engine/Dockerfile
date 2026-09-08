# Local Docker test infrastructure & Production-ready container template
#
# Build context MUST be the repo root:
#   mvn clean package -DskipTests
#   docker compose -f docker-compose.dev.yml up -d --build

FROM eclipse-temurin:25-jre-alpine

LABEL maintainer="K12 Realtime Engine Team"
LABEL description="Optimized Pekko Game Engine Service Container"

# Create non-root user for container security hardening
RUN addgroup -g 10001 appgroup && \
    adduser -u 10001 -G appgroup -h /app -D appuser

WORKDIR /app
RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

# Copy pre-built artifact from host build
COPY --chown=appuser:appgroup modules/uni-game-engine/target/uni-game-engine-0.0.1-SNAPSHOT-exec.jar /app/app.jar

USER appuser:appgroup

# JVM Container Tuning Options:
# - UseG1GC for low-latency GC
# - MaxRAMPercentage=75.0 to respect container cgroup memory limits
# - ExitOnOutOfMemoryError to ensure container orchestrator restarts failed pods
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8090 9100

# Health check against frame channel port or Spring actuator
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=3 \
  CMD nc -z localhost 9100 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
