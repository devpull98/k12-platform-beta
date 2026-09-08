# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A realtime classroom game platform: a teacher starts a session, students join rooms from
their own devices, answer timed questions, and see a shared scoreboard update live.
Target for Phase 1 is 2–3k concurrent students; the design is written for 50k+.

The repo previously held a Spring/JPA ticket-selling DDD exercise. That code was removed
on branch `feat/uni-realtime-p1-scaffold` and replaced by this system. Only the
observability work survived the switch — see below. Git history before that point is about
the old project and is not a guide to this one.

**The design is already written and is the source of truth**, not the code:
`docs/specs/tech-design/EdTech_Game_Realtime_Architecture_v3.0.md` (1528 lines). Do not
read it whole — the work package names the sections that matter.

**Entry point for any task:** `docs/work/NOJIRA-uni-p1-realtime-core/_context.md`, then
`plan.md` in the same folder. Read those before touching code. Do not glob `docs/` looking
for context.

## Stack

- **Java 25**, Maven multi-module (no wrapper committed; IntelliJ's bundled Maven works —
  `%LOCALAPPDATA%\Programs\IntelliJ IDEA Ultimate\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`)
- **Spring Boot 4.1.1** — boots the process and serves `/actuator/*`. It is **not** on the
  packet path in either service.
- **Netty** — the WebSocket edge (gateway) and the internal frame channel (both sides),
  hand-built pipelines
- **Apache Pekko 1.1.3** (typed actors) — one single-threaded actor per room owns that
  room's state
- **Protobuf 4.29.3** — one schema (`modules/uni-protocol/`) for both the WebSocket hop and the
  internal hop, generated once (ADR-1)
- **Observability** — `observability/` holds a portable Prometheus + Loki + Tempo +
  Promtail + Grafana + Alertmanager stack (separate compose project). Both services export
  `/actuator/prometheus`, send OTLP traces to `localhost:4318`, and write JSON logs to
  `logs/<spring.application.name>.log` for Promtail. See `observability/README.md`.

Phase 1 has **no datastore on the hot path** — no MySQL. Valkey Cluster (one-time join token
guard via `SET join-token:{jti} 1 EX 30 NX`, Hot Snapshot < 5 KB) and Kafka Cluster
(`game.events.v1` event streaming after scoring) **are** in scope per the 2026-09-05 decision
in `_context.md` — both live in the async backplane, never inside a Netty EventLoop or on
the synchronous `RoomActor` message path. If a task reaches for a *synchronous* DB/Valkey/Kafka
call on the hot path, it has left Phase 1 scope; stop and check `_context.md`.

Valkey, not Redis: a BSD-3/Linux Foundation fork of Redis 7.2.4, RESP-protocol-compatible, no
client or command-level differences for anything this repo does (2026-09-07 decision). Lettuce
(the client) never renamed its own API for it, so code still imports `io.lettuce.core.RedisClient`
etc. and Lua scripts still call the `redis.call(...)` global — that is Valkey's own scripting API
surface, not a leftover from before the switch.

Code identifiers deliberately don't name Valkey (2026-09-07 decision, same day as the switch
above): the class is `DistributedRoomLeaseStore`/`DistributedRoomSnapshotStore`
(`LeaseBasedRoomOwnership` is the `RoomOwnership` built on top), config is
`uni.engine.room-store.*` / `ENGINE_ROOM_STORE_*`, and the Compose service is `room-store`. Named
by role so swapping the backing product again later is a config/ops change, not a repo-wide
rename — see `DistributedRoomLeaseStore`'s javadoc. `application.yml`, `docker-compose.dev.yml`,
and this file's own "Stack"/infra sections still say "Valkey" where the point is which real
product is deployed — that distinction (identifier vs. infra description) is intentional, not
inconsistency.

## Commands

```
mvn clean install                            # build everything + run tests
mvn -pl :uni-protocol test                   # protobuf round-trip
mvn -pl :uni-game-engine test -Dtest=RoomActorTest
mvn -pl :uni-websocket-gateway spring-boot:run         # gateway: actuator 8080, WebSocket 9000
mvn -pl :uni-game-engine spring-boot:run          # engine:  actuator 8090, frame channel 9100
mvn -pl :uni-websocket-gateway spring-boot:run -Dspring-boot.run.arguments=--server.port=8081  # 2nd instance
cd observability && docker compose up -d     # Grafana on :3000
```

Select modules by artifactId (`-pl :uni-game-engine`), not by path — the selector then works from
the repo root regardless of where the module directory sits.

Surefire already passes `-Dio.netty.leakDetection.level=paranoid` for every module — a
leak in a fan-out test is a build failure, not a log line to skim past.

There is no linter/formatter configured.

## Module layout

Every Maven module lives under `modules/` with a `uni-` prefix. Everything else at the repo
root is not a module: `docs/`, `observability/` (the Grafana compose stack), `scripts/`.

```
modules/uni-protocol/            game_message.proto + generated Java. Depended on by BOTH
                                  services — never copy the .proto into a second module.
modules/uni-observability/       Plumbing shared by both services: Prometheus/OTLP wiring, JSON
                                  logging, Kafka log appender (prod profile only), Alertmanager
                                  webhook relay. Inherited from the removed project.
modules/uni-websocket-gateway/   WebSocket edge: handshake, join-token auth, rate limiting, room
                                  registry, zero-copy fan-out, backpressure, learned routing.
modules/uni-game-engine/         Game engine: RoomActor FSM, scoring, dedupe, tick coalescing,
                                  room ownership, internal frame channel server.
```

Only `uni-websocket-gateway` and `uni-game-engine` are deployable; the other two are libraries.

Both services scan `com.uni.realtime` so `uni-observability`'s beans are picked up.
Narrowing that scan silently disables alerting while everything still starts.

Both service poms pin `spring-boot:run` to the repo root
(`<workingDirectory>${session.executionRootDirectory}</workingDirectory>`). Logback writes a
relative `logs/` path and Promtail mounts the root one — without that pin the log file lands
under `modules/` and nothing reaches Loki.

## Rules that matter more than they look

These are the failure modes the design doc spends its length on. Violating one produces
code that passes a naive test and breaks in production.

- **`client_timestamp_ms` must never touch the scoring path** (§9.4). It is client wall
  clock, forgeable. Response time is `server_received_at − server_question_started_at`,
  both stamped by the engine off an **injected `Clock`** — never
  `System.currentTimeMillis()` called inline, or nothing is deterministically testable.
- **Fan-out uses `retainedDuplicate()`, never `retain()`** (§10.3). With `retain()` the
  first client gets the bytes and every other client gets zero. A one-client test passes
  anyway — fan-out tests need ≥ 2 clients, and the plan mandates 12.
- **`room_id` comes from the channel attributes bound at handshake, never from the
  payload** (§10.6). A payload whose `room_id` disagrees is a security event: close the
  channel.
- **One backpressure mechanism, end to end** (§10.2): actor mailbox → engine stops reading
  → TCP window → gateway sees `!isWritable()` → `autoRead(false)` on the client socket. No
  app-level queue anywhere between the mailbox and the socket. Adding one destroys the
  main reason gRPC was rejected.
- **A silent room broadcasts zero packets** (ADR-4, §6.2). 200ms is a ceiling on
  frequency, not a tick. Critical messages (`ANSWER_ACK`, `GAME_OVER`, `QUESTION_STARTED`,
  `TEACHER_COMMAND`, `CONNECTION_DEGRADED`) bypass coalescing entirely.
- **Room ownership lives behind `RoomOwnership` and nowhere else** (decision B2). Phase 2
  replaces that one class with Cluster Sharding. If `% N` appears anywhere else, that
  swap becomes a rewrite.
- **The gateway never computes where a room lives.** It learns from
  `InternalHeader.owner_pod_id` on responses (§8.2). This is why Phase 2 will not need to
  touch the gateway.
- **Losing an engine pod must not close client WebSockets** (§9.7). Send
  `CONNECTION_DEGRADED` and hold the socket open; mass reconnect turns one pod's failure
  into the whole system's.
- **No DB/store/HTTP call inside a Netty EventLoop** (§13.2), and no `synchronized` around
  blocking I/O anywhere.

## Known Phase 1 trade-offs — deliberate, documented, not bugs to fix

- No Cluster Sharding: losing an engine pod kills its rooms until the pod returns. Accepted
  at 2–3k CCU, **must go before Phase 2**, and the UI has to show it.
- No `RESYNCING` state, no dashboard fan-in, no LZ4. Valkey Cluster (join-token dedup + Hot
  Snapshot) and Kafka Cluster (event streaming) **are** in Phase 1 scope, off the hot path —
  see the note above; this used to say "no Redis, no Kafka" before the 2026-09-05 decision
  (and "Redis Cluster" rather than "Valkey Cluster" before the 2026-09-07 Valkey switch).
- PH-3: the client-side contract (ring buffer, `sequence`, RESYNC) does not exist yet, so
  the "zero data loss" SLA has no basis regardless of server correctness. Do not publish it.

## Governance

The framework kit is installed (by the `onboarding` skill, 2026-09-06):
`project-context.yaml`, `rules/spring/`, `scripts/governance-check.sh` and the five
`validate-*.sh` gates. Run `bash scripts/governance-check.sh` before merge — all five
currently pass.

Two things about that install are worth knowing before you trust a green run:

- **`rules/spring/` is not the kit's stock Spring rules.** The kit ships Java 11 / Boot 2.x /
  JPA / `@WebMvcTest` / `@EmbeddedKafka` conventions, none of which exist here. Those were
  discarded and the files rewritten from this document's rules and the v3.0 design sections
  they cite. `stack: spring` only means "boots with Spring Boot".
- **Two gates are green because they have nothing to check yet**, not because the work is
  covered: `validate-trace.sh` skips entirely (`docs/specs/bdd/` does not exist — no
  `.feature` files, so no `@trace` tags are demanded of the code), and the ship gate only
  observes `phase=dev` in `_context.md`. Neither is evidence of test coverage.
  `validate-skill-graph.sh` was patched to skip here — it checks kit-repo integrity
  (`router.yaml`, `skills/`), which a target project does not have.

`.git/hooks/` holds **copies**, not symlinks (`ln -s` degrades to copy on Windows) — re-run
`bash scripts/hooks/install-hooks.sh` after the kit updates. `pre-commit` blocks commits to
`main`/`master`/`test` and now actually runs `validate-stack.sh`.

`plan.md` still carries the real build and test commands; the gates do not replace them.

Never commit directly to `main`. Work on a feature branch and open a PR.
