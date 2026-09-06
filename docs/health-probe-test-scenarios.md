# Kịch bản Test Liveness / Readiness Probe (Manual Test Guide)

> [!WARNING]
> **Chưa cập nhật cho uni-realtime.** Các kịch bản dưới đây viết cho service bán vé cũ:
> chúng giả định readiness group có `db` (MySQL) và một app duy nhất ở port 8080. Gateway
> Giai đoạn 1 **không có hard dependency nào** để gate readiness (§9.7 — mất engine thì trả
> `CONNECTION_DEGRADED`, không rút khỏi rotation). Giữ lại vì phần đối chiếu với
> `SYSTEM_MONITORING_OBSERVABILITY_TECHNICAL_STANDARD.md` vẫn đúng; viết lại kịch bản khi
> probe được nối dây ở Giai đoạn 1.


Tài liệu này mô tả cách giả lập thủ công 3 case Liveness/Readiness cho service
`spring-ticket-ddd`, đối chiếu với chuẩn tại
`docs/SYSTEM_MONITORING_OBSERVABILITY_TECHNICAL_STANDARD.md` — Mục VI
(Kubernetes Probe Standard) và DoD của Phase 1 (Mục 19.1):

> *"Khi ngắt kết nối MySQL DB, Pod KHÔNG bị K8s Restart (Liveness pass), chỉ
> ngắt traffic (Readiness fail 503)."*

Bản test tự động tương ứng nằm ở
`src/test/java/com/ticketdd/HealthProbeTests.java` (dùng Testcontainers, chạy
qua `mvn test -Dtest=HealthProbeTests`). Guide dưới đây dùng cho việc kiểm
tra thủ công trên infra thật (docker-compose) — ví dụ khi onboard người mới,
hoặc trước khi merge thay đổi liên quan tới `management.endpoint.health` trong
`application.yml`.

## Cấu hình liên quan

`src/main/resources/application.yml`:

```yaml
management:
  endpoint:
    health:
      group:
        liveness:
          include: livenessState   # KHÔNG được thêm db/redis/mongo/kafka vào đây
          show-components: always
        readiness:
          include: readinessState,db   # chỉ hard dependency (MySQL)
          show-components: always
```

`liveness` chỉ được include `livenessState` — nếu thiếu dòng `include` này,
Spring Boot sẽ mặc định group thành `include: "*"` và kéo cả `db`/`redis`/
`mongo` vào liveness, khiến Pod bị K8s restart oan khi MySQL down.

## Chuẩn bị môi trường

### 1. Hạ tầng (MySQL/Redis/Kafka/MongoDB)

```bash
docker compose up -d mysql redis kafka mongodb
```

Đợi MySQL healthy trước khi chạy app (`ddl-auto: validate` sẽ fail startup
nếu MySQL chưa nhận connection):

```bash
docker inspect --format '{{.State.Health.Status}}' spring-ticket-ddd-mysql-1
# chờ tới khi in ra "healthy"
```

### 2. Chạy app

**Có, chạy được trên IntelliJ** — không bắt buộc phải chạy bằng `mvn
spring-boot:run` dưới terminal:

1. Mở project trong IntelliJ, để Maven tự import (`pom.xml`).
2. **File → Project Structure → Project SDK**: chọn JDK 25 (project cấu hình
   `java.version: 25`, ví dụ `ms-25.0.4.1` nếu dùng Microsoft Build of
   OpenJDK). Thiếu bước này IntelliJ sẽ báo lỗi compile do target 25.
3. Chạy trực tiếp class `com.ticketdd.SpringTicketDddApplication` (Run ▸
   `SpringTicketDddApplication.main()`).
4. Vì `spring-boot-docker-compose` có trong `pom.xml` (scope mặc định, không
   phải `test`), app sẽ tự `docker compose up` các service khai báo trong
   `docker-compose.yml` khi start nếu chúng chưa chạy — không bắt buộc phải
   `docker compose up` tay trước, nhưng làm trước (bước 1) sẽ thấy log khởi
   động nhanh hơn vì infra đã sẵn sàng.
5. **Lưu ý cổng 8080**: nếu đã có 1 instance khác đang chạy (ví dụ từ
   `mvn spring-boot:run` ngoài terminal), IntelliJ Run sẽ fail với
   `Web server failed to start. Port 8080 was already in use.` — dừng
   instance cũ trước khi Run trong IntelliJ, hoặc đổi `server.port` tạm thời
   cho lần chạy song song.

## 3 Kịch bản Test

### Case 1 — Baseline: mọi dependency healthy

```bash
curl -s -w "\nhttp: %{http_code}\n" http://localhost:8080/actuator/health/liveness
curl -s -w "\nhttp: %{http_code}\n" http://localhost:8080/actuator/health/readiness
```

**Kết quả mong đợi:**

| Endpoint | HTTP | Body |
| :--- | :--- | :--- |
| `/actuator/health/liveness` | `200` | `{"components":{"livenessState":{"status":"UP"}},"status":"UP"}` |
| `/actuator/health/readiness` | `200` | chứa `"db":{"status":"UP"}` và `"readinessState":{"status":"UP"}` |

### Case 2 — MySQL (hard dependency) down

```bash
docker compose stop mysql
```

Poll readiness tới khi thấy 503 (K8s sẽ retry theo `periodSeconds`, ở đây
poll tay):

```bash
for i in $(seq 1 10); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health/readiness)
  echo "readiness http: $code"
  [ "$code" = "503" ] && break
  sleep 2
done

curl -s -w "\nhttp: %{http_code}\n" http://localhost:8080/actuator/health/liveness
curl -s -w "\nhttp: %{http_code}\n" http://localhost:8080/actuator/health/readiness
```

**Kết quả mong đợi (đây là điều kiện DoD bắt buộc phải pass):**

| Endpoint | HTTP | Ý nghĩa |
| :--- | :--- | :--- |
| `/actuator/health/liveness` | **`200`** | Pod vẫn "sống" → K8s **không restart** |
| `/actuator/health/readiness` | **`503`** | `db.status = DOWN` (lỗi `CannotGetJdbcConnectionException`) → K8s **gỡ Pod khỏi Service traffic** |

Nếu liveness cũng rớt xuống 503 ở bước này → group `liveness` đang include
nhầm `db` (regression của bug đã fix trong file này), cần kiểm tra lại
`application.yml`.

### Case 3 — MySQL phục hồi

```bash
docker compose start mysql

for i in $(seq 1 15); do
  status=$(docker inspect --format '{{.State.Health.Status}}' spring-ticket-ddd-mysql-1)
  [ "$status" = "healthy" ] && break
  sleep 3
done

curl -s -w "\nhttp: %{http_code}\n" http://localhost:8080/actuator/health/readiness
```

**Kết quả mong đợi:** `200`, `db.status = UP` — readiness tự phục hồi, không
cần restart app hay Pod.

## Tương ứng với bản test tự động

3 case trên map 1-1 với 3 test method trong `HealthProbeTests.java`:

| Case thủ công | Test method |
| :--- | :--- |
| Case 1 | `readinessIsUpAndReportsDatabaseWhileMysqlIsHealthy` |
| Case 1 (liveness scope) | `livenessIsUpAndDoesNotReportDatabase` |
| Case 2 + 3 | `readinessGoesDownWhenMysqlStopsButLivenessStaysUp` |

Chạy `mvn test -Dtest=HealthProbeTests` để verify tự động (dùng
Testcontainers, không cần `docker compose up` tay).
