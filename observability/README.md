# observability/

Portable monitoring stack: Prometheus · Loki · Tempo · Promtail · Grafana · Alertmanager.  
Copy folder này vào bất kỳ Spring Boot project nào và hoạt động ngay.

---

## Tổng quan kiến trúc

```
App (Spring Boot)
 ├─ /actuator/prometheus  ──────────────►  Prometheus  ──► Grafana (dashboards)
 │                                              │
 │                                         Alertmanager ──► Telegram / Google Chat
 │
 ├─ logs/app.log (JSON)  ──► Promtail  ──►  Loki  ──────► Grafana (Explore / Logs panel)
 │                                              │
 └─ OTLP HTTP :4318      ────────────────► Tempo  ──────► Grafana (Explore / Trace panel)
                                                              │
                                          Loki ◄─ traceId link ─┘  (log ↔ trace correlation)
```

3 loại tín hiệu, 3 tool riêng biệt:

| Tín hiệu | Tool | Câu hỏi trả lời |
|----------|------|-----------------|
| **Metrics** | Prometheus | *Bao nhiêu?* — request/s, error rate, latency p95, heap, pool size |
| **Logs** | Loki | *Chuyện gì xảy ra?* — stack trace, business event, context của từng request |
| **Traces** | Tempo | *Tại sao chậm?* — request đi qua những service/layer nào, tốn bao lâu ở đâu |

---

## Services & ports

| Service | Port | Vai trò |
|---------|------|---------|
| Prometheus | 9090 | Scrape và lưu metrics |
| Alertmanager | 9093 | Route alert → Telegram / Google Chat |
| Loki | 3100 | Nhận và index log |
| Tempo | 4318 (HTTP) / 4317 (gRPC) | Nhận trace (OTLP) |
| Promtail | — | Agent: đọc `logs/app.log`, ship lên Loki |
| Grafana | 3000 | UI: dashboards, Explore, alert |

---

## Loki — Log Aggregation

**Loki là gì:** Database lưu log, tương tự Elasticsearch nhưng chỉ index label (không full-text index nội dung log). Cho phép query log bằng **LogQL**.

**Data flow:**
```
App viết log JSON → logs/app.log → Promtail đọc file → parse traceId → push lên Loki
```

**Promtail** là agent chạy sidecar, theo dõi file `logs/app.log`, tự extract các field và gắn label:
```yaml
labels:
  job: spring-ticket-ddd
  level: {level từ JSON log}
```

**Xem log trong Grafana:**
- Explore → chọn datasource **Loki** → query bằng LogQL:

```logql
# Toàn bộ log của app
{job="spring-ticket-ddd"}

# Chỉ ERROR
{job="spring-ticket-ddd"} | json | level="ERROR"

# Log chứa "kafka"
{job="spring-ticket-ddd"} |= "kafka"

# Log theo traceId cụ thể
{job="spring-ticket-ddd"} | json | traceId="abc123"

# Rate lỗi theo thời gian
rate({job="spring-ticket-ddd"} | json | level="ERROR" [1m])
```

**App config cần có:**
```yaml
logging:
  file:
    name: logs/app.log   # Promtail đọc file này
```

`logback-spring.xml` đã config output JSON (Logstash encoder) để Promtail parse được `traceId`, `level`, `logger` từ mỗi dòng log.

---

## Tempo — Distributed Tracing

**Tempo là gì:** Backend lưu distributed trace theo chuẩn OpenTelemetry. Mỗi HTTP request vào app tạo ra 1 **trace** gồm nhiều **span** (mỗi operation = 1 span). Tempo lưu trace theo `traceId` và cho phép query bằng **TraceQL**.

**Data flow:**
```
HTTP request vào app
  → Micrometer OTel bridge tạo trace/span
  → OpenTelemetry SDK serialize
  → gửi qua OTLP HTTP đến localhost:4318
  → Tempo lưu trace
  → Grafana query và render trace timeline
```

**Span tự động được tạo cho:**
- Mỗi HTTP request/response (Spring MVC)
- Mỗi query JPA/JDBC (MySQL)
- Mỗi lệnh Redis
- Mỗi message Kafka produce/consume

**Xem trace trong Grafana:**
- Explore → chọn datasource **Tempo**
- Tìm theo `traceId` (copy từ log hoặc response header `traceparent`)
- Hoặc dùng TraceQL:

```traceql
# Tất cả trace của service này
{ resource.service.name = "spring-ticket-ddd" }

# Trace chậm hơn 500ms
{ resource.service.name = "spring-ticket-ddd" } | duration > 500ms

# Trace có lỗi
{ resource.service.name = "spring-ticket-ddd" && status = error }

# Trace có span gọi MySQL
{ span.db.system = "mysql" }
```

**App config cần có:**
```yaml
management:
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces   # gửi trace về Tempo
  tracing:
    sampling:
      probability: 1.0   # sample 100% request (production nên dùng 0.1)
```

---

## Log ↔ Trace correlation

Đây là tính năng quan trọng nhất: **từ log nhảy thẳng sang trace tương ứng**.

Mỗi log line JSON có field `traceId`:
```json
{"level":"INFO","traceId":"4bf92f3577b34da6","spanId":"00f067aa0ba902b7","message":"seat held"}
```

Grafana Loki datasource được config với **derived field**:
- Pattern: `"traceId":"([a-f0-9]+)"`
- Link: mở Tempo trace tương ứng

Khi xem log trong Explore, mỗi dòng có link **TraceID** → click → mở trace trong Tempo → thấy toàn bộ stack: HTTP handler → JPA query → Redis → thời gian từng bước.

---

## Alertmanager

Nhận alert từ Prometheus và route đến channel phù hợp:

```
prometheus/alerts.yml  →  Alertmanager  →  Telegram (critical)
                                        →  Google Chat (warning + critical)
```

Config channel: copy `observability/.env.example` → `observability/.env`:
```bash
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...
GOOGLE_CHAT_WEBHOOK_URL=...
```

Alert rules trong `prometheus/alerts.yml` — các threshold:

| Alert | Condition | Severity |
|-------|-----------|----------|
| AppDown | target unreachable 1m | critical |
| HighErrorRate | error rate > 5% | critical |
| HighLatencyP95 | p95 > 1s | warning |
| JvmHeapHigh | heap > 85% | warning |
| JvmHeapCritical | heap > 95% | critical |
| HikariPoolExhausted | pending threads > 0 | critical |
| MySQLDown / RedisDown / KafkaDown / MongoDBDown | target down | critical |
| KafkaConsumerLagHigh | lag > 1000 | warning |

---

## Start

```bash
cd observability
docker compose up -d
```

Grafana: http://localhost:3000 (admin / admin)

---

## Reuse trong project khác

1. Copy folder `observability/` vào project root mới.
2. Edit `.env` — set `APP_JOB_NAME` = `spring.application.name` của project đó.
3. Đảm bảo app ghi log JSON ra `logs/app.log`.
4. Đảm bảo app export trace về `http://localhost:4318/v1/traces`.
5. `docker compose up -d`.

App config tối thiểu:
```yaml
management:
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
  tracing:
    sampling:
      probability: 1.0
  metrics:
    tags:
      application: ${spring.application.name}

logging:
  file:
    name: logs/app.log
```
