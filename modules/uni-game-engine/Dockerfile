# Local Docker test infrastructure only (see docker-compose.dev.yml at the repo root).
#
# No Maven-in-Docker multi-stage build on purpose -- see the identical note in
# modules/uni-websocket-gateway/Dockerfile. Build the jar on the host first
# (`mvn clean package -DskipTests` from the repo root); this Dockerfile only copies the
# already-built artifact. Build context MUST be the repo root -- this COPY path only resolves
# from there.
FROM eclipse-temurin:25-jre

WORKDIR /app
RUN mkdir -p /app/logs

COPY modules/uni-game-engine/target/uni-game-engine-0.0.1-SNAPSHOT-exec.jar /app/app.jar

# WORKDIR is already /app, so logback-spring.xml's relative `logs/${appName}.log` lands at
# /app/logs/uni-game-engine.log with no extra flag.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
