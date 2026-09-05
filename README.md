# spring-ticket-ddd

Học Domain-Driven Design với Spring Boot: hệ thống bán vé (ticket booking) thiết kế để chịu tải ~50k lượt mua vé đồng thời, không oversell, dùng Java virtual threads.

## Stack

| Concern | Công nghệ |
|---------|-----------|
| Language | Java 25 (virtual threads) |
| Framework | Spring Boot 4.1.1 / Spring 7, Maven |
| Relational DB | MySQL 8.4 + Flyway migrations |
| Cache / Lock | Redis 7.4 (maxmemory 256 MB, allkeys-lru) |
| Messaging | Kafka (KRaft mode, no ZooKeeper) |
| Document DB | MongoDB 7.0 |
| Resilience | Resilience4j 2.4.0 (`@RateLimiter`, `@CircuitBreaker`) |
| Metrics | Micrometer + Prometheus → Grafana |
| Tracing | Micrometer OTel bridge + OTLP → Tempo |
| Logging | Logback (JSON) + Promtail → Loki |
| Alerting | Alertmanager → Telegram / Google Chat |

## Prerequisites

- JDK 25
- Maven 3.9+
- Docker Desktop (để chạy infra và observability stack)
- Node.js 18+ (để chạy scripts/)

## Ports

| Service | Port | Compose file |
|---------|------|--------------|
| App | 8080 | — |
| MySQL | 3306 | `docker-compose.yml` |
| Redis | 6379 | `docker-compose.yml` |
| Kafka | 9092 | `docker-compose.yml` |
| Kafka UI | 8090 | `docker-compose.yml` |
| MongoDB | 27017 | `docker-compose.yml` |
| mysqld-exporter | 9104 | `docker-compose.yml` |
| redis-exporter | 9121 | `docker-compose.yml` |
| kafka-exporter | 9308 | `docker-compose.yml` |
| node-exporter | 9100 | `docker-compose.yml` (host network) |
| mongodb-exporter | 9216 | `docker-compose.yml` |
| Prometheus | 9090 | `observability/docker-compose.yml` |
| Alertmanager | 9093 | `observability/docker-compose.yml` |
| Grafana | 3000 | `observability/docker-compose.yml` |
| Loki | 3100 | `observability/docker-compose.yml` |
| Tempo HTTP | 4318 | `observability/docker-compose.yml` |
| Tempo gRPC | 4317 | `observability/docker-compose.yml` |

## Chạy local

### Bước 1 — Start infra

```bash
docker compose up -d
```

Khởi động: MySQL, Redis, Kafka, MongoDB và tất cả exporter. `spring-boot-docker-compose` trong app sẽ tự start/stop khi chạy `mvn spring-boot:run`, nên bước này là tuỳ chọn cho lần đầu.

### Bước 2 — Start app

```bash
mvn spring-boot:run
```

App chạy tại `http://localhost:8080`. Các endpoint test:

| Endpoint | Mô tả |
|----------|-------|
| `GET /hello` | Ping cơ bản |
| `GET /hello/db` | Test MySQL (`SELECT 1`) |
| `GET /hello/redis` | Test Redis (SET/GET) |
| `GET /hello/redis/stress` | Redis stress: String + Hash + List + INCR |
| `GET /hello/kafka` | Gửi message lên `test.ping` |
| `GET /hello/mongo` | Insert doc vào MongoDB, trả về count |
| `GET /hello/slow` | Giả lập latency phân phối (70% fast / 20% medium / 10% slow) |
| `GET /hello/stats` | Kafka produced/consumed/lag counter |
| `GET /actuator/prometheus` | Prometheus metrics |
| `GET /actuator/health` | Health check (MySQL, Redis, Mongo, Kafka, Circuit breaker) |

`HelloController` có scheduler tự ping 10–20 request ngẫu nhiên mỗi 500ms (virtual threads) để có đủ sample cho histogram p95/p99.

### Bước 3 — Start observability (tuỳ chọn)

```bash
cd observability
docker compose up -d
```

- Grafana: http://localhost:3000 (admin / admin)
- Prometheus: http://localhost:9090
- Alertmanager: http://localhost:9093

## Workflow 3 terminal

```bash
# Terminal 1 — infra
docker compose up -d

# Terminal 2 — observability
cd observability && docker compose up -d

# Terminal 3 — app
mvn spring-boot:run
```

Tắt theo thứ tự ngược lại. Hai compose project có network riêng biệt; Prometheus scrape app và exporter qua `host.docker.internal`.

## Observability

Grafana auto-provision **5 folder dashboard**:

| Folder | Dashboard | Metrics chính |
|--------|-----------|--------------|
| Application | Spring Boot App | HTTP rate/latency/errors, JVM heap/GC, HikariCP pool, Resilience4j, Logs |
| MySQL | MySQL Overview (7362) | Connections, QPS, InnoDB buffer |
| MySQL | MySQL Detail (14057) | Slow queries, lock waits, replication |
| Redis | Redis Exporter 1.x (763) | Memory %, commands/s, hit rate, keyspace |
| Redis | Redis HA (11835) | Connections, blocked clients, evictions |
| Kafka | Kafka Exporter Overview (7589) | Message rate, consumer lag, partition offsets |
| MongoDB | MongoDB Dashboard (20867) | Connections, opcounters, dbstats, cursor metrics |

Node Exporter (dashboard 1860 có thể import thêm): CPU, RAM, disk I/O, network của host.

**Alerts** — `observability/prometheus/alerts.yml`:
- App down / error rate high / latency p95 high
- JVM heap > 85% / 95%
- HikariCP pool exhausted
- MySQL / Redis / Kafka / MongoDB down
- Consumer lag > 100 / 1000

Copy `observability/.env.example` → `observability/.env` và điền `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `GOOGLE_CHAT_WEBHOOK_URL` để bật alert.

## Scripts

```bash
# Tạo 5 domain Kafka topics (idempotent — bỏ qua nếu topic đã tồn tại)
node scripts/kafka-setup.mjs

# Sinh Markdown health report từ Prometheus API → reports/
node scripts/daily-report.mjs
```

Env vars cho scripts:
- `KAFKA_BROKERS` — default `localhost:9092`
- `PROMETHEUS_URL` — default `http://localhost:9090`
- `REPORT_DIR` — default `./reports`

## Kafka topics

`scripts/kafka-setup.mjs` tạo 5 domain topics (3 partitions, replication factor 1):

| Topic | Vai trò |
|-------|---------|
| `booking.seat-held` | Booking context → seat hold confirmed |
| `booking.seat-released` | Booking context → seat released (timeout/cancel) |
| `order.placed` | Order context → order created |
| `order.confirmed` | Order context → order confirmed after payment |
| `payment.requested` | Order context → trigger payment |

Topic `test.ping` tự tạo khi app start (dùng cho `HelloController`).

## Schema migrations

Flyway quản lý schema MySQL. `ddl-auto: validate` — Hibernate báo lỗi khi start nếu entity không khớp schema.

Khi thêm aggregate/entity mới:
1. Viết Flyway migration `src/main/resources/db/migration/V{N}__<tên>.sql`
2. Tạo JPA entity trong `infrastructure/`
3. Chạy app — Hibernate validate sẽ xác nhận schema match

## Architecture

Xem `docs/architecture/project-overview.md` để đọc đầy đủ về DDD layering, bounded context boundaries, concurrency design.

## Build & test

```bash
mvn spring-boot:run                         # chạy app
mvn test                                    # tất cả tests (cần Docker)
mvn test -Dtest=ArchitectureTests           # boundary check, không cần Docker
mvn test -Dtest=ClassName#methodName        # chạy 1 test cụ thể
mvn -DskipTests package                     # build jar

docker compose up -d                        # start infra
docker compose down -v                      # stop và xoá volumes

cd observability && docker compose up -d    # start monitoring
cd observability && docker compose down -v  # stop monitoring
```
