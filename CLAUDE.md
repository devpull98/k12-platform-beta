# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project purpose

This is a learning project: practicing Domain-Driven Design (tactical + strategic patterns) with Spring Boot, using Java virtual threads, in the shape of a ticket-selling system designed to handle ~50k concurrent purchase attempts without overselling. The user is implementing the domain code themselves — Claude's role here is to pair on design, review, and infrastructure, not to author bounded-context business logic unless explicitly asked.

The repository currently contains only the project skeleton (build config, package structure, base config, docker-compose for local infra, testcontainers-backed test scaffold). No domain logic exists yet.

## Stack

- **Java 25**, Maven build (`pom.xml`, no wrapper committed — install Maven/JDK 25 locally or use an IDE's bundled toolchain)
- **Spring Boot 4.1.1** (Spring Framework 7)
- **MySQL** — primary datastore (JPA/Hibernate)
- **Redis** — seat-hold / distributed locking / rate-limiting during the purchase flow
- **Kafka** — async domain events between bounded contexts (e.g. order placed → inventory/payment reactions)
- **Resilience4j** (`resilience4j-spring-boot4`, pinned to `2.4.0` explicitly — not yet in `resilience4j-bom`) — `@RateLimiter` on ingress (booking `interfaces/` hot endpoints, e.g. seat-hold) and `@CircuitBreaker` around outbound `infrastructure/` adapters (payment gateway, any synchronous cross-context call)
- **Virtual threads are enabled** (`spring.threads.virtual.enabled: true` in `src/main/resources/application.yml`) — blocking JDBC/Redis/Kafka clients are fine on the request path; avoid `synchronized` blocks around blocking I/O (they pin virtual threads to carrier threads) and prefer `ReentrantLock` where locking is needed in that path.

## Commands

```
mvn spring-boot:run          # run the app (auto-starts docker-compose.yml services via spring-boot-docker-compose)
mvn test                     # run tests (spins up MySQL/Redis/Kafka via Testcontainers — requires Docker running)
mvn test -Dtest=ArchitectureTests      # boundary check only, no Docker needed
mvn test -Dtest=ClassName#methodName   # run a single test method
mvn -DskipTests package      # build the jar without running tests
docker compose up -d         # start MySQL/Redis/Kafka manually
docker compose down -v       # tear down infra containers and volumes
```

There is no linter/formatter configured yet.

## Architecture

DDD-oriented layering, one package tree per bounded context under `com.ticketdd`:

```
com.ticketdd
├── order/              # Order bounded context (order lifecycle, payment orchestration)
│   ├── domain/          # aggregates, entities, value objects, domain events, repository ports — no framework types
│   ├── application/     # use-case services / command handlers, orchestrate domain + ports
│   ├── infrastructure/  # JPA repo impls, Kafka producers/consumers, Redis adapters — implements domain ports
│   └── interfaces/      # REST controllers, request/response DTOs
└── booking/             # Ticket/Booking bounded context (ticket inventory, seat/ticket hold, oversell prevention)
    ├── domain/
    ├── application/
    ├── infrastructure/
    └── interfaces/
```

Each context's `domain/` package must stay free of Spring/JPA/Kafka/Redis imports — those live in `infrastructure/` behind ports declared in `domain/`. This boundary is the main thing to enforce when reviewing code in this repo.

Cross-context communication is expected to go through Kafka domain events (`infrastructure/`), not direct calls between `booking` and `order` application services — this is the concurrency-critical seam: booking/seat-hold must resolve fast (Redis) while order/payment can be eventually consistent.

These boundaries are enforced at test time by `src/test/java/com/ticketdd/ArchitectureTests.java` (ArchUnit): `domain` can't depend on frameworks or on `application`/`infrastructure`/`interfaces`, `application` can't depend on `infrastructure`/`interfaces`, and `order`/`booking` can't depend on each other. A failing `ArchitectureTests` run means a boundary was crossed, not that the test is wrong — fix the dependency direction rather than loosening the rule, unless the user explicitly decides to change the architecture.

## Concurrency design intent

The core hard problem this project exists to practice is: 50k concurrent buyers, finite seats, zero oversell.
- **Redis** is the intended mechanism for short-lived seat holds / distributed locks during the "reserve" step (fast path, avoids hammering MySQL under load).
- **MySQL** is the system of record for confirmed bookings/orders — expect optimistic locking (version column) on the ticket-inventory aggregate rather than long-held pessimistic locks.
- **Kafka** decouples booking confirmation from downstream effects (payment, notifications) so the hot path stays short.
- **Virtual threads** are the concurrency model for handling many simultaneous in-flight requests cheaply — this only pays off if I/O on the request path is virtual-thread-friendly (no thread-pinning `synchronized`, no artificially small blocking connection pools sized for platform threads).

When the user adds entities, they'll need a schema migration story (`spring.jpa.hibernate.ddl-auto` is currently `none`); flag this rather than assuming a tool.
