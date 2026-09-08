# ADR: decouple the root POM from Spring Boot version — foundation for hosting future migrated services

**Status:** Accepted (2026-09-07)
**Context owner:** platform/build structure, not the uni-realtime feature itself — see
`docs/specs/tech-design/...` for the game engine's own architecture, unaffected by this ADR.

## Context

This repo (`k12-platform-beta`, module prefix `uni-`) is meant to become the long-term host that
a separate, much older monorepo — `k12-backend-java` (Java 11, Spring Boot 2.5.4, 79 `pom.xml`
files, ~50 leaf modules) — gets migrated into gradually, service by service, over time. No k12
business code moves in this change; this ADR only fixes the structural precondition that would
otherwise make that migration impossible.

**The problem observed in `k12-backend-java`:** every one of its 79 POMs is pinned to
`spring-boot-starter-parent:2.5.4`, either by inheriting the root aggregator (which itself has
that as `<parent>`) or, in ~15 modules, by declaring it directly. There is no Bill of Materials —
version alignment happens entirely through parent inheritance. The practical consequence: nobody
has ever gotten a single service onto Java 17+ / Spring Boot 3.x, because:

1. Spring Boot 3 moved `javax.*` → `jakarta.*`. Shared library modules (`k12-core/*`,
   `k12-mongo-core/*`, `k12-service/*`) are compiled against Boot 2.5.4's `javax.*` APIs. Any
   service that upgrades its own parent to Boot 3 can no longer depend on those libraries as
   plain Maven artifacts — the classes don't exist on a Jakarta classpath.
2. Because every module's `<parent>` chain terminates at the same `spring-boot-starter-parent`
   declaration (whether via the root aggregator or hard-coded per module), there is no way for
   one deployable to sit on Boot 3 while thirty others stay on Boot 2 in the same reactor. The
   parent chain is a single version knob for everyone downstream of it, not fifty independent
   ones.

**This repo already has the same latent defect.** Root `pom.xml` declares
`<parent>spring-boot-starter-parent:4.1.1</parent>` and `<properties><java.version>25</java.version>`
directly. Every module (`uni-protocol`, `uni-observability`, `uni-websocket-gateway`, `uni-game-engine`,
`uni-e2e`) has `com.uni:uni-realtime` (this root) as its own `<parent>`, so all five inherit Boot
4.1.1 / Java 25 transitively through that one link. It hasn't bitten yet only because every
module in the reactor today happens to want the same stack. It will bite the moment a k12 service
— which cannot jump straight to Boot 4 / Java 25 — needs to join this same reactor.

## Decision

Stop using `spring-boot-starter-parent` as a *transitively inherited* parent. Each deployable
Spring Boot module manages its own Boot version by importing the **`spring-boot-dependencies`
BOM** (import-scope `dependencyManagement`) instead of inheriting the opinionated starter parent.
The root POM keeps its role as reactor aggregator and as the place to manage versions that are
genuinely shared across this product's own modules (`protobuf-bom`, `pekko-bom`, internal
`com.uni:*` artifact versions) — but it carries no opinion about Boot version or Java version for
anyone else.

Concretely:

- Root `pom.xml`: no `<parent>` on `org.springframework.boot:spring-boot-starter-parent`. No
  root-level `<properties><java.version>`. Stays `packaging=pom`, keeps the module list and the
  BOM imports that are genuinely about *this* product (protobuf, pekko, internal artifacts).
- `uni-websocket-gateway` and `uni-game-engine` (the two `@SpringBootApplication` deployables): each declares its
  own `<properties><java.version>25</java.version>`, imports `spring-boot-dependencies:4.1.1` in
  its own `<dependencyManagement>`, and explicitly configures `spring-boot-maven-plugin` (no
  longer inherited from the starter parent's plugin management).
- `uni-protocol`, `uni-observability`, `uni-e2e`: same per-module `java.version` +
  `spring-boot-dependencies` import where they rely on Boot-managed versions (logback, jackson,
  junit, etc. via transitive deps) — verified by a full `mvn clean install` after the change, not
  assumed.
- A future migrated k12 service arriving in this reactor does the same thing with **its own**
  Boot version (2.7.x, 3.x, whatever it actually needs at the time it moves) — it never has to
  touch this repo's root POM or any other module's POM to do so, and nothing it does can force a
  version change on `uni-websocket-gateway`/`uni-game-engine`.

## Package/module layout convention for future migrated services

`chore/init-skeleton` (this repo's prior, unrelated ticket-selling exercise, removed from this
branch) organized code as **bounded context first, DDD layer second**:
`com.ticketdd.<context>.{domain,application,infrastructure,interfaces}`. That per-context layering
is the right unit to reuse — not the single-module packaging it used. For a multi-module reactor
meant to host many independently-versioned services, the layering happens **inside each module's
own package tree**, one module per bounded context (or per deployable), e.g. a future
`modules/k12-cms/` would have
`com.educa.k12cms.{domain,application,infrastructure,interfaces}` internally, exactly like
`uni-game-engine`'s own packages are organized by concern today. This keeps the two concerns properly
separated: **module boundary = independent build/version boundary**, **package layout inside a
module = DDD layering**. Conflating them (one giant module, internally layered, like
`chore/init-skeleton` did) is what makes independent version upgrades impossible in the first
place — see the k12 finding above.

## Consequences

- **Gained:** a future k12 service can be dropped into this reactor's module list on whatever
  Boot/Java version it currently runs, with zero changes to root or to `uni-websocket-gateway`/`uni-game-engine`.
  Upgrading `uni-game-engine` to a newer Boot/Java later is similarly a one-module, one-file change.
- **Cost:** `spring-boot-maven-plugin` and other build-behavior defaults that
  `spring-boot-starter-parent` used to supply for free (resource filtering, plugin version
  pinning, etc.) must now be declared explicitly per Boot-based module. This is intentional —
  it's the same trade every real Spring multi-Boot-version monorepo makes, and it's what actually
  buys the independence.
- **Not done here:** no k12 code moves. No new bounded-context module is created yet — this ADR
  only removes the structural blocker. The first real migrated service is a separate, later
  decision.
