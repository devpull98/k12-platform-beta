# Project Overview — spring-ticket-ddd

> Cập nhật: 2026-08-25

## Mục tiêu

Learning project thực hành DDD (tactical + strategic patterns) với Spring Boot.  
Bài toán cụ thể: hệ thống bán vé chịu tải **50k concurrent buyers, zero oversell**.

User tự implement domain logic. Infrastructure, observability, và design review là phần Claude hỗ trợ.

---

## Bounded Contexts

```
com.ticketdd
├── booking/    ← Ticket inventory, seat hold, oversell prevention  (HOT PATH)
│   ├── domain/         aggregate, entity, value object, domain event, repository port
│   ├── application/    use-case service, command handler
│   ├── infrastructure/ JPA impl, Redis adapter, Kafka producer/consumer
│   └── interfaces/     REST controller, DTO
└── order/      ← Order lifecycle, payment orchestration            (EVENTUALLY CONSISTENT)
    ├── domain/
    ├── application/
    ├── infrastructure/
    └── interfaces/
```

### Quy tắc dependency (enforced bởi ArchUnit)

| Layer | Được phép depend vào |
|-------|----------------------|
| `domain` | Không có gì — pure Java, không import framework |
| `application` | `domain` |
| `infrastructure` | `domain`, `application` |
| `interfaces` | `application`, `domain` |
| `booking` ↔ `order` | Không gọi nhau trực tiếp — chỉ qua Kafka event |

Vi phạm → `ArchitectureTests` fail → fix dependency direction, không nới rule.

---

## Stack

| Concern | Công nghệ | Ghi chú |
|---------|-----------|---------|
| Language | Java 25 | Virtual threads enabled (`spring.threads.virtual.enabled: true`) |
| Framework | Spring Boot 4.1.1 (Spring 7) | Maven, no wrapper |
| Relational DB | MySQL 8.4 | JPA/Hibernate, Flyway migrations, `ddl-auto: validate` |
| Cache / Lock | Redis 7.4 | Seat hold, distributed lock, rate limit — maxmemory 256 MB allkeys-lru |
| Messaging | Kafka (KRaft) | Cross-context domain events, no ZooKeeper |
| Document DB | MongoDB 7.0 | Event log, read-model projection, analytics |
| Resilience | Resilience4j 2.4.0 | `@RateLimiter(seatHold)` trên hot path, `@CircuitBreaker(paymentGateway)` trên outbound |
| Metrics | Micrometer + Prometheus | `/actuator/prometheus` |
| Tracing | Micrometer OTel bridge + OTLP | → Tempo `localhost:4318` |
| Logging | Logback + Logstash encoder | `logs/app.log` (JSON), Promtail → Loki |
| Observability UI | Grafana 11 | `observability/` compose project, port 3000 |
| Alerting | Alertmanager | Telegram (critical) + Google Chat (warning) |
| Test infra | Testcontainers | MySQL + Redis + Kafka spin up per test run |
| Boundary test | ArchUnit | `ArchitectureTests.java` |

---

## Concurrency Design (core challenge)

```
Request (50k concurrent)
  → interfaces/  @RateLimiter(seatHold, 100 req/s)
  → application/ BookingService
  → Redis: SETNX seat-hold key (TTL 10 phút)    ← FAST PATH, ~1ms
  → Kafka: publish booking.seat-held event
           → order/ consume event
           → MySQL: INSERT order (optimistic lock @Version)
           → Kafka: publish payment.requested
```

**Nguyên tắc:**
- Redis giải quyết race condition ở bước hold — không để 50k request cùng hit MySQL một lúc
- MySQL là source of truth sau khi order confirmed — dùng `@Version` optimistic lock (không pessimistic lock)
- Virtual threads: tránh `synchronized` bao quanh blocking I/O — dùng `ReentrantLock` để không pin carrier thread
- Connection pool: để ở mức vừa đủ (~50), không size cho platform threads nữa

---

## Infrastructure layout

```
docker-compose.yml                    ← Infra stack (tất cả có resource limit)
  mysql:8.4           (3306)  1g/1cpu  ← custom build với utf8mb4, max 300 conn
  redis:7.4           (6379) 384m/0.5  ← maxmemory 256mb allkeys-lru
  kafka KRaft         (9092)  1g/1cpu  ← single broker, no ZooKeeper
  kafka-ui            (8090) 512m/0.5  ← Provectus UI, xem topic/message/consumer
  mongodb:7.0        (27017)  1g/1cpu  ← document store
  mysqld-exporter    (9104)  64m/0.1   ← Prometheus metrics cho MySQL
  redis-exporter     (9121)  64m/0.1   ← Prometheus metrics cho Redis
  kafka-exporter     (9308)  64m/0.1   ← Prometheus metrics cho Kafka
  node-exporter      (9100) 128m/0.2   ← Host OS: CPU, RAM, disk, network (host network)
  mongodb-exporter   (9216) 128m/0.2   ← Prometheus metrics cho MongoDB (--collect-all)

docker/mysql/
  Dockerfile                          ← extends mysql:8.4
  conf.d/mysql.cnf                    ← utf8mb4, max_connections=300, wait_timeout=300

observability/                        ← Portable monitoring stack (compose project riêng)
  docker-compose.yml                  ← Prometheus, Loki, Tempo, Promtail, Grafana, Alertmanager
  .env                                ← Knobs: ports, job name, log dir, webhook tokens
  prometheus/
    prometheus.yml                    ← 6 scrape targets: app(8080), mysql(9104), redis(9121),
                                         kafka(9308), node(9100), mongodb(9216)
    alerts.yml                        ← Alert rules: HEALTHY/DEGRADED/DOWN cho mọi service
  alertmanager/
    alertmanager.yml                  ← Routing: critical→Telegram+GGChat, warning→GGChat
  grafana/
    provisioning/
      datasources/datasources.yml     ← Prometheus(uid=prometheus), Loki(uid=loki), Tempo(uid=tempo)
      dashboards/dashboard.yml        ← 5 providers: app, mysql, redis, kafka, mongodb
    dashboards/
      app/spring-boot-app.json        ← HTTP, JVM, HikariCP, Resilience4j, Logs
      mysql/mysql-overview.json       ← Dashboard 7362 (Percona): connections, QPS, InnoDB
      mysql/mysql-detail.json         ← Dashboard 14057 (Grafana Labs): slow queries, locks
      redis/redis-763.json            ← Dashboard 763: memory, commands, hit/miss, keyspace
      redis/redis-11835.json          ← Dashboard 11835: connections, evictions, blocked clients
      kafka/kafka-7589.json           ← Dashboard 7589: message rate, consumer lag, partitions
      mongodb/mongodb-overview.json   ← Dashboard 20867: connections, opcounters, dbstats

src/main/resources/
  application.yml                     ← Config gốc (virtual threads, datasource, Redis, Mongo, Kafka,
                                         Resilience4j, Actuator, OTLP tracing)
  application-dev.yml                 ← SQL debug + domain DEBUG logs
  application-prod.yml                ← INFO only
  logback-spring.xml                  ← Console (text dev / JSON prod), traceId injection
  db/migration/
    V1__baseline.sql                  ← Empty baseline. Domain tables từ V2 trở đi.

scripts/
  kafka-setup.mjs                     ← Tạo 5 domain topics (Node.js ESM, kafkajs)
  daily-report.mjs                    ← Health report từ Prometheus API → reports/ (Markdown)
```

---

## Cách dùng từng module

### MySQL

Kết nối qua JPA/Hibernate. Schema quản lý bởi Flyway.

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ticket_dd?useSSL=false&...
    username: ticket_user
    password: ticket_pass
  jpa:
    hibernate:
      ddl-auto: validate    # KHÔNG để create/update — Flyway quản lý schema
  flyway:
    enabled: true
    locations: classpath:db/migration
```

Thêm aggregate mới:
1. Tạo `src/main/resources/db/migration/V{N}__<tên>.sql`
2. Tạo JPA entity trong `infrastructure/`
3. Chạy `mvn spring-boot:run` — Hibernate validate báo lỗi nếu không khớp

### Redis

Dùng `StringRedisTemplate` (inject từ Spring context):

```java
// String
redis.opsForValue().set("key", value, Duration.ofMinutes(10));
String val = redis.opsForValue().get("key");

// Hash
redis.opsForHash().put("hash-key", "field", value);

// List
redis.opsForList().leftPush("list-key", value);
redis.opsForList().trim("list-key", 0, 99);   // giới hạn size

// Atomic counter
Long count = redis.opsForValue().increment("counter-key");

// Distributed lock (pattern)
Boolean locked = redis.opsForValue().setIfAbsent("lock:seat:123", "owner", Duration.ofSeconds(30));
```

Config (`application.yml`):
```yaml
spring.data.redis:
  host: localhost
  port: 6379
```

### Kafka

Publish:
```java
@Autowired KafkaTemplate<String, String> kafka;
kafka.send("booking.seat-held", key, payload);
```

Consume:
```java
@KafkaListener(topics = "booking.seat-held", groupId = "booking-service")
public void onSeatHeld(String message) { ... }
```

Config (`application.yml`):
```yaml
spring.kafka:
  bootstrap-servers: localhost:9092
  consumer:
    group-id: spring-ticket-ddd
    auto-offset-reset: earliest
```

Setup domain topics (chạy 1 lần, idempotent):
```bash
node scripts/kafka-setup.mjs
```

### MongoDB

Dùng `MongoTemplate` (inject từ Spring context):

```java
@Autowired MongoTemplate mongo;

// Insert
Document doc = new Document("field", value).append("ts", Instant.now().toString());
mongo.insert(doc, "collection-name");

// Query
List<Document> docs = mongo.find(
    Query.query(Criteria.where("field").is(value)),
    Document.class, "collection-name"
);

// Count
long count = mongo.getCollection("collection-name").countDocuments();
```

Hoặc dùng `@Document` + `MongoRepository` cho domain aggregate.

Config (`application.yml`):
```yaml
spring.data.mongodb:
  uri: mongodb://ticket_user:ticket_pass@localhost:27017/ticket_dd?authSource=admin
```

### Resilience4j

```java
// Rate limiter trên hot endpoint
@RateLimiter(name = "seatHold")
@PostMapping("/bookings/hold")
public ResponseEntity<?> holdSeat(...) { ... }

// Circuit breaker bọc outbound adapter
@CircuitBreaker(name = "paymentGateway", fallbackMethod = "paymentFallback")
public PaymentResult charge(Order order) { ... }

private PaymentResult paymentFallback(Order order, Exception ex) {
    // return cached/default result
}
```

Config (`application.yml`):
```yaml
resilience4j:
  ratelimiter.instances.seatHold:
    limit-for-period: 100
    limit-refresh-period: 1s
    timeout-duration: 0s      # reject immediately, không queue
  circuitbreaker.instances.paymentGateway:
    sliding-window-size: 20
    failure-rate-threshold: 50
    wait-duration-in-open-state: 10s
```

### Observability

Metrics tự expose qua `/actuator/prometheus`. Prometheus scrape mỗi 15s.

Custom metric trong code:
```java
@Autowired MeterRegistry registry;

// Counter
Counter.builder("booking.seat.held")
    .tag("result", "success")
    .register(registry)
    .increment();

// Timer / histogram (tự có p50/p95/p99)
Timer timer = Timer.builder("booking.processing.time").register(registry);
timer.record(() -> processBooking(cmd));
```

Grafana dashboard mới: tạo JSON trong đúng subfolder của `observability/grafana/dashboards/`, Grafana tự reload sau 30s.

---

## Observability Health Model

| State | Điều kiện |
|-------|-----------|
| 🟢 HEALTHY | All targets UP, error rate < 1%, p95 < 500ms, heap < 85% |
| 🟡 DEGRADED | Error rate 1–5%, p95 500ms–1s, heap 85–95%, consumer lag > 100 |
| 🔴 DOWN | Target unreachable, error rate > 5%, heap > 95%, consumer lag > 1000 |

| Severity | Channel | SLA |
|----------|---------|-----|
| `critical` | Telegram + Google Chat | 15 phút |
| `warning` | Google Chat | 2 giờ |

---

## Ports

| Service | Port |
|---------|------|
| App | 8080 |
| MySQL | 3306 |
| Redis | 6379 |
| Kafka | 9092 |
| Kafka UI | 8090 |
| MongoDB | 27017 |
| mysqld-exporter | 9104 |
| redis-exporter | 9121 |
| kafka-exporter | 9308 |
| node-exporter | 9100 |
| mongodb-exporter | 9216 |
| Prometheus | 9090 |
| Alertmanager | 9093 |
| Grafana | 3000 |
| Loki | 3100 |
| Tempo HTTP | 4318 |
| Tempo gRPC | 4317 |

---

## Checklist khi thêm aggregate mới

1. Tạo domain class trong `booking/domain/` hoặc `order/domain/` (pure Java, không import framework)
2. Khai báo repository port interface trong `domain/`
3. Implement port trong `infrastructure/` (JPA entity + Spring Data repo)
4. **Tạo Flyway migration** `V{N}__<tên>.sql` — bắt buộc vì `ddl-auto: validate`
5. Viết use case trong `application/`
6. Expose endpoint trong `interfaces/`
7. Chạy `mvn test -Dtest=ArchitectureTests` để verify boundary không bị vi phạm
