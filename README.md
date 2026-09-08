# uni-realtime

Nền tảng game học tập thời gian thực: giáo viên mở phiên, học sinh vào phòng từ thiết bị
riêng, trả lời câu hỏi có đếm giờ, bảng điểm cập nhật trực tiếp cho cả phòng.
Giai đoạn 1 nhắm 2–3k học sinh đồng thời; thiết kế viết cho 50k+.

> Repo này trước đây chứa project học DDD bán vé (Spring/JPA). Toàn bộ code đó đã được
> thay bằng hệ thống hiện tại; **chỉ phần observability được giữ lại**. Lịch sử git trước
> nhánh `feat/uni-realtime-p1-scaffold` nói về project cũ.

**Thiết kế là nguồn sự thật, không phải code:**
`docs/specs/tech-design/EdTech_Game_Realtime_Architecture_v3.0.md`.
Điểm vào của mọi task: `docs/work/NOJIRA-uni-p1-realtime-core/_context.md`.

## Stack

| Concern | Công nghệ |
|---------|-----------|
| Language | Java 25 |
| Bootstrap / actuator | Spring Boot 4.1.1 (không nằm trên đường đi của gói tin) |
| Biên WebSocket | Netty (pipeline tự dựng) |
| Game engine | Apache Pekko 1.1.3 — mỗi phòng một actor đơn luồng |
| Giao thức | Protobuf 4.29.3, một schema cho cả hai chặng (ADR-1) |
| Metrics | Micrometer + Prometheus → Grafana |
| Tracing | Micrometer OTel bridge + OTLP → Tempo |
| Logging | Logback JSON + Promtail → Loki |
| Alerting | Alertmanager → Telegram / Google Chat |

Giai đoạn 1 **không có datastore trên hot path** — không MySQL, không Valkey, không Kafka.

## Prerequisites

- JDK 25
- Maven 3.9+ (chưa commit wrapper; Maven bundled của IntelliJ dùng được:
  `%LOCALAPPDATA%\Programs\IntelliJ IDEA Ultimate\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`)
- Docker Desktop (chỉ cần cho observability stack)
- Node.js 18+ (cho `scripts/daily-report.mjs`)

## Module

Mọi module Maven nằm dưới `modules/` với prefix `uni-`. Thư mục còn lại ở root **không phải**
module: `docs/`, `observability/` (compose stack Grafana), `scripts/`.

```
modules/
  uni-protocol/             game_message.proto + code sinh ra. Cả hai service cùng phụ thuộc
  uni-observability/        observability dùng chung: Prometheus/OTLP, log JSON, Kafka log
                             appender, relay webhook Alertmanager
  uni-websocket-gateway/    biên WebSocket: handshake, join-token auth, rate limit, fan-out,
                             backpressure, định tuyến học được từ engine
  uni-game-engine/          RoomActor FSM, chấm điểm, dedupe, tick coalescing, sở hữu phòng
docs/
observability/         <- compose stack Grafana/Prometheus/Loki/Tempo
scripts/
pom.xml                <- aggregator
```

Chỉ `uni-websocket-gateway` và `uni-game-engine` deploy được; hai module còn lại là thư viện.
Chọn module bằng artifactId (`-pl :uni-game-engine`) chứ không bằng đường dẫn — lệnh chạy được
từ root bất kể thư mục nằm đâu.

## Ports

| Service | Port | Ghi chú |
|---------|------|---------|
| Gateway — actuator | 8080 | `/actuator/prometheus`, `/actuator/health` |
| Gateway — WebSocket | 9000 | plaintext; TLS terminate ở LB/ingress |
| Engine — actuator | 8090 | |
| Engine — internal frame channel | 9100 | TCP length-prefixed protobuf |
| Prometheus | 9090 | `observability/docker-compose.yml` |
| Alertmanager | 9093 | `observability/docker-compose.yml` |
| Grafana | 3000 | `observability/docker-compose.yml` |
| Loki | 3100 | `observability/docker-compose.yml` |
| Tempo HTTP / gRPC | 4318 / 4317 | `observability/docker-compose.yml` |

## Chạy local

```bash
mvn clean install                  # build + test toàn bộ

mvn -pl :uni-websocket-gateway spring-boot:run    # terminal 1
mvn -pl :uni-game-engine spring-boot:run     # terminal 2

cd observability && docker compose up -d   # terminal 3 (tuỳ chọn)
```

Chạy instance thứ hai để thấy route cache học `owner_pod_id` (§8.2):

```bash
mvn -pl :uni-websocket-gateway spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
ENGINE_POD_ID=engine-1 ENGINE_FRAME_PORT=9101 mvn -pl :uni-game-engine spring-boot:run \
  -Dspring-boot.run.arguments=--server.port=8091
```

Prometheus đã cấu hình sẵn 4 job (`gateway-1`, `gateway-2`, `engine-1`, `engine-2`) scrape
qua `host.docker.internal`.

- Grafana: http://localhost:3000 (admin / admin)
- Prometheus: http://localhost:9090
- Alertmanager: http://localhost:9093

## Build & test

```bash
mvn clean install                              # tất cả module + test
mvn -pl :uni-protocol test                     # round-trip protobuf
mvn -pl :uni-game-engine test -Dtest=RoomActorTest  # 1 test class
mvn -DskipTests package                        # build jar
```

Surefire đã bật `-Dio.netty.leakDetection.level=paranoid` cho mọi module — rò rỉ buffer
trong test fan-out là **build fail**, không phải dòng log lướt qua.

## Observability

Stack ở `observability/` chạy như một compose project riêng, không phụ thuộc app.
Xem `observability/README.md`.

Cả hai service pin `spring-boot:run` về root repo, nên log JSON ghi vào `logs/` ở root
(`logs/uni-websocket-gateway.log`, `logs/uni-game-engine.log`) chứ không rơi vào `modules/`.
Promtail tail `logs/*.log` và promote `application` + `traceId` thành label để nối
Loki ↔ Tempo.

Bật alert: copy `observability/.env.example` → `observability/.env`, điền
`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `GOOGLE_CHAT_WEBHOOK_URL`.
Thử tay: `GET /internal/test-alert/oom`, `/internal/test-alert/down`,
`/internal/test-alert/test?container=...&status=...`.

> Dashboard và alert rule của MySQL / Redis / Kafka / MongoDB vẫn còn trong
> `observability/` nhưng **đang nằm im**: Giai đoạn 1 không chạy exporter nào cho chúng, mà
> một series vắng mặt thì không bao giờ khớp `== 0`. Tên "Redis" ở các dashboard/alert này
> khớp với `redis_exporter` thật (metric `redis_up`...) — không đổi thành "Valkey" ở đây vì
> chưa có `valkey_exporter` nào được nối; xem `docs/architecture` cho hạ tầng Valkey thật của
> Giai đoạn 1. Chúng sống lại (dưới tên exporter thật được nối lúc đó) khi Giai đoạn 2 thêm
> datastore/Kafka — nên giữ chứ không xoá.

## Scripts

```bash
node scripts/daily-report.mjs      # sinh Markdown health report từ Prometheus API → reports/
```

Env: `PROMETHEUS_URL` (mặc định `http://localhost:9090`), `REPORT_DIR` (mặc định `./reports`).

## Tài liệu

| File | Nội dung |
|------|----------|
| `docs/specs/tech-design/EdTech_Game_Realtime_Architecture_v3.0.md` | Thiết kế hợp nhất — nguồn sự thật |
| `docs/work/NOJIRA-uni-p1-realtime-core/_context.md` | Phạm vi Giai đoạn 1, quyết định đã chốt, rủi ro đã biết |
| `docs/work/NOJIRA-uni-p1-realtime-core/plan.md` | 13 task + 1 spike, kèm acceptance criteria |
| `CLAUDE.md` | Ràng buộc bất biến khi sửa code trong repo này |
