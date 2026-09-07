# Local Docker test infrastructure only (see docker-compose.dev.yml at the repo root).
#
# No Maven-in-Docker multi-stage build on purpose: this repo's poms pin java.version=25, and a
# JDK-25-capable Maven Docker image was not something to gamble on existing/being reproducible
# while writing this. Instead: build the jar on the host first (`mvn clean package -DskipTests`
# from the repo root), then this Dockerfile only copies the already-built, already-tested
# artifact. Build context MUST be the repo root (docker-compose.dev.yml sets it that way) --
# this COPY path only resolves from there.
FROM eclipse-temurin:25-jre

WORKDIR /app
RUN mkdir -p /app/logs

COPY modules/uni-websocket-gateway/target/uni-websocket-gateway-0.0.1-SNAPSHOT-exec.jar /app/app.jar

# WORKDIR is already /app, so logback-spring.xml's relative `logs/${appName}.log` lands at
# /app/logs/uni-websocket-gateway.log with no extra flag -- this does not need the host's
# session.executionRootDirectory pom trick, which exists only for `mvn spring-boot:run`.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
