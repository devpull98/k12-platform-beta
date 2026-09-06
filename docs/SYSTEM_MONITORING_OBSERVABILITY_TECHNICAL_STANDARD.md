# SYSTEM MONITORING & OBSERVABILITY TECHNICAL STANDARD
## Tài liệu Quy chuẩn Kỹ thuật & Lộ trình Triển khai Vận hành Hệ thống

**Phiên bản:** 2.6 (Enterprise Master Edition — Full Service-to-Service & 3rd Party API Specification)  
**Ngày cập nhật:** 24/08/2026  
**Đối tượng áp dụng:** Team DevOps, System Administrators, SRE, Tech Leads, Developers  
**Phạm vi:** Multi-language Microservices (Node.js, Java Spring Boot, PHP Laravel/FPM), Databases (MySQL, MongoDB, Redis, Aerospike), Kafka, Kubernetes & Observability Infrastructure  
**Cấu trúc Tài liệu:**  
- **PHẦN I: TẬP QUY CHUẨN KỸ THUẬT & ONBOARDING (Technical Specification & Onboarding Standard)**  
- **PHẦN II: LỘ TRÌNH TRIỂN KHAI THEO THỜI GIAN & KHUNG ƯU TIÊN (Implementation Roadmap & Prioritization Framework)**

---

### Quy ước Đọc Tài liệu (Terminology Convention)

Để tránh hiểu nhầm giữa "quy định bắt buộc" và "gợi ý tham khảo", tài liệu này sử dụng 4 nhãn chuẩn (theo tinh thần RFC 2119):

| Nhãn | Ý nghĩa |
| :--- | :--- |
| **`[MUST]`** | **Bắt buộc tuân thủ** — Vi phạm gây rủi ro vận hành nghiêm trọng (ví dụ: Cascading Failure). Không có ngoại lệ. |
| **`[SHOULD]`** | **Khuyến nghị mạnh** — Nên áp dụng trừ khi có lý do kỹ thuật/nghiệp vụ cụ thể để làm khác và phải có ghi chú đánh giá rủi ro. |
| **`[DEFAULT]`** | **Giá trị khởi điểm** (ngưỡng, retention, cấu hình...) — Dùng làm baseline ban đầu, **cần hiệu chỉnh** theo đặc tính thực tế của từng service trước khi áp dụng cứng vào Alerting. |
| **`[EXAMPLE]`** | **Ví dụ minh họa** để làm rõ khái niệm — Không phải số liệu/tên gọi chuẩn để copy nguyên văn vào hệ thống thật. |

---

# =========================================================
# PHẦN I: TẬP QUY CHUẨN KỸ THUẬT & ONBOARDING
# =========================================================

# I. EXECUTIVE SUMMARY & TECHNICAL OBJECTIVES

## 1.1. Thực trạng Vận hành Kỹ thuật
Hệ thống hiện tại đang tồn tại một số vấn đề vận hành kỹ thuật cốt lõi cần giải quyết triệt để:
- **Service/Pod bị treo nhưng không có cảnh báo:** Ứng dụng vẫn được Kubernetes xác định là đang chạy (`Running`) trong khi luồng xử lý chính (như Kafka Consumer Worker) đã bị treo dừng hẳn.
- **Thông tin giám sát bị phân mảnh:** Dev và DevOps phải kiểm tra thủ công nhiều màn hình riêng lẻ cho từng Application, Database, Kafka và Kubernetes.
- **Khả năng quan sát khi xảy ra sự cố chưa ổn định:** Khi cần điều tra Production Incident, hệ thống Log có thể gặp lỗi ngắt kết nối (`503 Service Unavailable`).
- **Chưa có một mô hình Health thống nhất:** Trạng thái *"Application còn sống"*, *"Application sẵn sàng nhận traffic"* và *"Application xử lý nghiệp vụ tốt"* chưa được phân định ranh giới rõ ràng.

## 1.2. Mục tiêu Kỹ thuật
1. **Single Dashboard:** Tập trung Metrics của Apps, DBs, Kafka và K8s trên Grafana với 3 trạng thái chuẩn (`HEALTHY`, `DEGRADED`, `DOWN`).
2. **Health Model & Service Stability:** Phân biệt rõ Liveness Probe, Readiness Probe, Business Health Metrics và bộ chỉ số đo lường độ ổn định dịch vụ & Consumer.
3. **Metrics & Health First Roadmap:** Tuân thủ triệt để nguyên tắc *Thu thập Metrics & Health Checks trước, Notification & Alerting sau* để đảm bảo dữ liệu giám sát tin cậy trước khi thiết lập cảnh báo.
4. **Automatic Alerting & Deduplication:** Tự động phát cảnh báo và kích hoạt quy trình xử lý sự cố, khử trùng lặp đi kèm Context và Runbook Link (kích hoạt ở giai đoạn sau khi đã có baseline metrics chuẩn).
5. **Centralized Observability:** Tập trung Metrics, Logs (có bảo mật PII) và Distributed Tracing (`TraceId`, `CorrelationId`).
6. **Periodic Reporting:** Tự động tạo Daily Report (08:00 AM), Monthly Report và Capacity Planning Hàng năm có hiển thị xu hướng biến động (Trend).
7. **Escalation Matrix:** Quy định rõ từng cấp Severity -> Ai nhận tin nhắn -> Thời gian phản hồi SLA -> Kênh liên lạc.

---

# II. OBSERVABILITY ARCHITECTURE & RETENTION POLICY

## 2.1. Tổng quan Kiến trúc Dòng Dữ liệu Observability

```text
                         USERS
                           │
                           ▼
                    ┌──────────────┐
                    │   INGRESS    │
                    └──────┬───────┘
                           │
                           ▼
              ┌─────────────────────────┐
              │    APPLICATION LAYER    │
              │ Node.js / Java / PHP    │
              └──┬───────────────────┬──┘
                 │                   │
      (đọc/ghi trực tiếp)     (produce message,
                 │              không chờ đồng bộ)
     ┌───────────┼───────────┐        │
     ▼           ▼           ▼        ▼
  ┌───────┐  ┌────────┐  ┌───────┐  ┌────────────────┐
  │ MySQL │  │ MongoDB│  │ Redis │  │ Kafka (Broker) │
  └───────┘  └────────┘  └───────┘  └────────┬───────┘
                                              │ (consume độc lập,
                                              │  async, không phụ
                                              │  thuộc nhánh DB)
                                              ▼
                                     Worker / Consumer


  Metrics & Structured Logs                   Distributed Traces
  (Prometheus format & JSON Logs)             (W3C Trace Context)
              │                                       │
              ▼                                       ▼
       OBSERVABILITY LAYER                     OTel Collector
       ┌──────────────┬──────────────┐         (Batching, Adaptive Sampling,
       │              │              │          filter PII before export)
       ▼              ▼              ▼                │
    Metrics         Logs          Tracing             ▼
  (Prometheus)     (Loki)      (Tempo / Jaeger) ──────┘
       │              │              │
       └──────────────┼──────────────┘
                      │
                      ▼
                   Grafana
                      │
               ┌──────┴──────┐
               │             │
               ▼             ▼
            Alerting       Reports
               │
               ▼
    Slack / Teams / Telegram / PagerDuty
```

### Quy chuẩn 3 Trụ cột Observability (`[MUST]`):
1. **Metrics:** Expose định dạng Prometheus scrape từ endpoint `/metrics` hoặc `/actuator/prometheus`.
2. **Logs:** In định dạng JSON Structured Logs ra `STDOUT`, đính kèm `TraceId` và `CorrelationId` xuyên suốt, bắt buộc **PII Masking** trước khi đẩy về Loki.
3. **Traces:** Gửi span chứa `W3C Trace Context` (`traceparent` header) qua **OpenTelemetry Collector** tập trung trước khi lưu vào Grafana Tempo / Jaeger.

## 2.2. Chính sách Lưu trữ Dữ liệu (Retention Policy & Storage Cost Control)

| Loại Dữ liệu | Công cụ Lưu trữ | Retention Period | Chính sách Nén / Sampling (Tối ưu Chi phí) |
| :--- | :--- | :--- | :--- |
| **Metrics** | Prometheus (raw) + **VictoriaMetrics / Thanos / Mimir** | **`[DEFAULT]` 30 ngày** | **Prometheus mặc định không hỗ trợ Downsampling.** Để downsample (giảm độ phân giải dữ liệu cũ xuống 5m sau 7 ngày), **`[MUST]`** dùng VictoriaMetrics hoặc Thanos/Mimir làm long-term storage. |
| **Standard Logs** | Grafana Loki | **`[DEFAULT]` 14 ngày** | Nén log dạng zstd block. Nới rộng **90 ngày** đối với Error Logs (5xx / Unhandled Exception). |
| **Distributed Traces** | OTel Collector -> Grafana Tempo / Jaeger | **`[DEFAULT]` 7 ngày** | **Adaptive Sampling** tại OTel Collector: sample 100% Trace lỗi (HTTP 5xx / Exception), sample 1–5% Trace thành công (HTTP 200 OK). |

---

# III. REQUIRED MONITORING DATA SPECIFICATION

> **QUY ĐỊNH BẮT BUỘC:** Mỗi Service khi được đăng ký Onboard vào hệ thống Monitoring **`[MUST]`** cung cấp đầy đủ thông tin định danh và tài nguyên theo template dưới đây.

## 3.1. Application Service Information

| Field | Requirement | Description & Example |
| :--- | :--- | :--- |
| **Service Name** | **`[MUST]`** | Tên định danh duy nhất của dịch vụ (ví dụ: `k12-api`, `k12-lms-service`, `k12-lms-scheduler`) |
| **Language** | **`[MUST]`** | Ngôn ngữ lập trình (ví dụ: `Node.js`, `Java`, `PHP`) |
| **Framework** | **`[MUST]`** | Framework sử dụng (ví dụ: `Express`, `Spring Boot 3.x`, `Laravel 10`) |
| **Environment** | **`[MUST]`** | Môi trường triển khai (ví dụ: `Production`, `Staging`) |
| **Namespace** | **`[MUST]`** | Kubernetes Namespace (ví dụ: `k12-prod`, `k12-staging`) |
| **Deployment** | **`[MUST]`** | Tên K8s Deployment object (ví dụ: `k12-api-deployment`) |
| **Owner Team** | **`[MUST]`** | Đội ngũ chịu trách nhiệm trực tiếp (ví dụ: `Backend-Team-A`) |
| **Criticality** | **`[MUST]`** | Mức độ quan trọng nghiệp vụ (`Critical` / `High` / `Medium`) |
| **Replica Min / Max**| **`[MUST]`** | Số lượng Pod tối thiểu và tối đa triển khai (ví dụ: Min `2`, Max `8`) |

## 3.2. Resource Information

| Metric | Requirement | Description |
| :--- | :--- | :--- |
| **CPU Request** | **`[MUST]`** | Định mức CPU cam kết khởi tạo (ví dụ: `500m`) |
| **CPU Limit** | **`[MUST]`** | Ngưỡng CPU tối đa cho phép Pod tiêu thụ (ví dụ: `1000m` / `1 Core`) |
| **Memory Request** | **`[MUST]`** | Định mức RAM cam kết khởi tạo (ví dụ: `512Mi`) |
| **Memory Limit** | **`[MUST]`** | Ngưỡng RAM tối đa cho phép trước khi bị OOMKilled (ví dụ: `1Gi`) |
| **Current CPU** | **`[SHOULD]`** | Mức CPU tiêu thụ trung bình thực tế |
| **Peak CPU** | **`[SHOULD]`** | Mức CPU đỉnh ghi nhận lúc cao điểm |
| **Current Memory** | **`[SHOULD]`** | Mức RAM tiêu thụ trung bình thực tế |
| **Peak Memory** | **`[SHOULD]`** | Mức RAM đỉnh ghi nhận lúc cao điểm |
| **CPU Throttling** | **`[MUST]`** | Tỷ lệ % thời gian tiến trình bị OS bóp CPU do vượt Limit |
| **Restart Count** | **`[MUST]`** | Số lần Pod bị khởi động lại trong chu kỳ quan sát |
| **OOMKilled** | **`[MUST]`** | Số sự kiện Pod bị Kernel diệt do vượt Memory Limit |

---

# IV. SERVICE DEPENDENCY MATRIX

### Ma trận Khai báo Chi tiết (Dependency Matrix Template):

| Source Service | Target Service / Infra | Connection Type | Host / Address | Port | Timeout (ms/s) | Connection Pool (Min/Max) | Retry Policy | Circuit Breaker? | Criticality (Hard/Soft) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `k12-api` (Node) | `java-lms-service` | HTTP / REST | `java-service.k12-prod` | `8080` | `3s` | Max 100 conns | 3 retries (exponential) | Yes (Resilience4j) | **Hard** |
| `k12-api` (Node) | `php-report-service`| HTTP / REST | `php-service.k12-prod`  | `8080` | `3s` | Max 20 conns  | No retry | No | **Soft** (Fallback local) |
| `k12-api` (Node) | `MySQL Main DB` | TCP / Driver | `mysql-master.db-prod`  | `3306` | `1s` | Min 5 / Max 50 | 2 retries | No | **Hard** |
| `k12-api` (Node) | `MongoDB CMS` | TCP / Driver | `mongo-primary.db-prod` | `27017`| `1s` | Min 10 / Max 100| 2 retries | No | **Soft** |
| `k12-api` (Node) | `Redis Cache` | TCP / Driver | `redis-cluster.db-prod` | `6379` | `500ms`| Min 5 / Max 30  | No retry | Yes | **Soft** (Read Direct DB)|

---

# V. SERVICE & ASYNC CONSUMER STABILITY STANDARD

```text
                                APPLICATION & CONSUMER HEALTH
                                              │
      ┌─────────────────────────┬─────────────┴───────────┬─────────────────────────┐
      ▼                         ▼                         ▼                         ▼
 (1) SYSTEM & RUNTIME     (2) SERVICE / CONSUMER    (3) DEPENDENCY &          (4) HEALTH PROBES
     HEALTH                   LEVEL PERFORMANCE         OUTBOUND HEALTH           STATUS
      │                         │                         │                         │
 CPU/RAM Saturation       RPS, Latency (P95/P99),   DB Connection Pools,       Liveness Probe,
 GC/Event Loop Lag        Error Rate (5xx %),       Outbound API/CircuitBrk,   Readiness Probe,
 Restarts/OOMKilled       Consumer Lag & Age        Rebalance Rate & DLT       Startup Probe
```

---

## 5.1. Khung Chỉ Số Đánh Giá Độ Ổn Định Dịch Vụ HTTP API (API Service Stability Metrics Framework)

Để xác định một Service (Application / Microservice) nhận HTTP API request có đang **chạy ổn định hay không**, hệ thống giám sát **`[MUST]`** thu thập và theo dõi bộ chỉ số theo 4 nhóm chiều đo cốt lõi sau:

### 1. Hạ tầng & Tiến trình Runtime (System & Runtime Health)
* **CPU Throttling Rate (`container_cpu_cfs_throttled_periods_total`):** Tỷ lệ % thời gian tiến trình bị OS bóp CPU do chạm Limit. Nếu Throttling > 15%, ứng dụng bị nghẽn xử lý dẫn đến latency tăng vọt.
* **Memory Saturation & Heap (`jvm_memory_used_bytes` / `process_resident_memory_bytes`):** Mức RAM tiêu thụ so với Memory Limit. Nếu RAM > 85% kéo dài hoặc Old Gen Heap tăng liên tục không giảm sau GC là dấu hiệu Memory Leak.
* **Runtime Lag / Stop-the-world (`nodejs_eventloop_lag_seconds` / `jvm_gc_pause_seconds_sum`):** Độ trễ Event Loop (Node.js) hoặc thời gian tạm dừng tiến trình do Garbage Collection (Java). Lag > 100ms thể hiện CPU/Runtime đang bị khựng.
* **Process Restarts & OOMKilled (`kube_pod_container_status_restarts_total`):** Số lần Pod bị khởi động lại hoặc bị Kernel tiêu diệt do tràn RAM. Service ổn định **bắt buộc Restart Count = 0** trong chu kỳ vận hành bình thường.

### 2. Hiệu năng & Khả năng Phục vụ (Service Level Performance — RED / 4 Golden Signals)
* **Traffic Rate (Requests Per Second - RPS):** Tổng khối lượng request nã vào Service. Giúp nhận biết spike lưu lượng hoặc rớt traffic bất thường.
* **Response Latency (P50, P95, P99 Duration):** Thời gian phản hồi request. P95 là thước đo cam kết chất lượng dịch vụ (SLA). Nếu P95 > 1s hoặc P99 spike > 3s, dịch vụ đang mất ổn định.
* **Error Rate (HTTP 5xx % / Unhandled Exception Rate):** Tỷ lệ request gặp lỗi server trên tổng số request. Service chạy ổn định phải duy trì **Error Rate < 1%** (High Alert nếu > 2%, Critical nếu > 5%).

### 3. Kết nối & Tương tác Phụ thuộc (Dependency & Outbound Health)
* **Database Connection Pools (HikariCP / Mongo / Redis Pools):** Active Pool %, Pending Queue Count (**Pending > 0 kéo dài > 10s là dấu hiệu bế tắc kết nối**), Pool Timeout Rate.
* **Outbound API Calls & Circuit Breaker (Inter-Service / 3rd Party):** Outbound Latency P95, Outbound Error/Timeout Rate, Circuit Breaker State (`0=CLOSED`, `1=OPEN`).

### 4. Trạng thái Nhịp tim & Khả năng Nhận Traffic (K8s Health Probes)
* **Liveness Probe Status (`/health/live`):** Xác nhận tiến trình ứng dụng và thread pool còn sống (Alive).
* **Readiness Probe Status (`/health/ready`):** Xác nhận ứng dụng sẵn sàng nhận traffic (các kết nối CSDL bắt buộc / Hard Dependencies hoạt động tốt).

---

## 5.2. Ma Trận Ngưỡng Đánh Giá Mức Độ Ổn Định Dịch Vụ API (API Service Stability Metric Matrix)

| Nhóm Chỉ Số | Metric / Khái niệm Đo | Trạng thái 🟢 ỔN ĐỊNH (Healthy) | Trạng thái 🟡 CẢNH BÁO (Degraded) | Trạng thái 🔴 KHÔNG ỔN ĐỊNH (Critical / Down) |
| :--- | :--- | :--- | :--- | :--- |
| **System Runtime** | CPU CFS Throttling Rate | `< 5%` | `5% - 15%` | `> 15%` (Khựng tiến trình) |
| | Memory Saturation (% Limit) | `< 75%` | `75% - 85%` | `> 85%` hoặc OOMKilled |
| | Event Loop Lag / GC Pause | Lag `< 50ms` | Lag `50ms - 200ms` | Lag `> 500ms` (Treo app) |
| | Pod Restart Count | `0` restarts / 24h | `1 - 2` restarts / 24h | `> 3` restarts / 24h |
| **Service Level** | HTTP 5xx Error Rate | `< 0.5%` | `0.5% - 2%` | `> 2%` (High) / `> 5%` (Critical) |
| | Latency P95 (Web API) | `< 500ms` | `500ms - 1000ms` | `> 1000ms` (Trì trệ) |
| | Latency P99 Spike | `< 1500ms` | `1500ms - 3000ms` | `> 3000ms` (Nghẽn cổ chai) |
| **Dependency** | DB Connection Pool Active | `< 70%` Pool | `70% - 85%` Pool | `> 85%` Pool |
| | DB Connection Pending Queue| `0` | `1 - 5` threads | `> 5` threads kéo dài `> 10s` |
| | Circuit Breaker State | `0` (CLOSED) | `2` (HALF_OPEN) | `1` (OPEN - Ngắt mạch) |
| **K8s Probes** | Liveness & Readiness Status | 200 OK | Readiness Failed (503) | Liveness Failed (Pod Restart) |

---

## 5.3. Tiêu Chuẩn Đánh Giá Độ Ổn Định Cho Consumer Trong Service (Async Consumer Stability Specification)

> **QUY ĐỊNH BẮT BUỘC (`[MUST]`):** Một Service đóng vai trò Async Consumer (như Kafka Consumer Worker, RabbitMQ Listener, SQS Queue Consumer) được định nghĩa là **chạy ổn định (Healthy & Stable)** khi thỏa mãn đồng thời 6 tiêu chuẩn vận hành và duy trì các thông số đo lường (Metrics) trong ngưỡng an toàn dưới đây.

### 5.3.1. 6 Tiêu Chí Định Nghĩa Một Consumer "Chạy Ổn Định" (Consumer Stability Criteria)

1. **Trạng thái Group Ổn Định & Không Rebalance Storm (Group State & Rebalance Stability):**
   * Consumer Group **`[MUST]`** ở trạng thái `STABLE`.
   * Tần suất Rebalance = `0` trong vận hành bình thường. Tránh tuyệt đối hiện tượng "Rebalance Storm" (thành viên gia nhập/ngắt kết nối liên tục làm ngừng trệ toàn bộ tiến trình consume).

2. **Cân Bằng Tốc Độ Tiêu Thụ & Không Tích Tụ Lag (Throughput & Lag Control):**
   * Tốc độ tiêu thụ ($R_{\text{consume}}$) **`[MUST]`** lớn hơn hoặc bằng Tốc độ nạp ($R_{\text{produce}}$).
   * Tốc độ tăng trưởng Lag (`Lag Growth Rate`) $\le 0$. Tổng số Unconsumed Message Lag luôn được giải tỏa và duy trì dưới ngưỡng baseline an toàn.

3. **Đảm Bảo Độ Tươi Của Tin Nhắn (Message Age Freshness):**
   * Chỉ số **`consumer_last_success_age`** (thời gian tính từ lúc message cuối cùng được xử lý thành công tới hiện tại) **`[MUST]`** nằm trong ngưỡng SLA nghiệp vụ (ví dụ: `< 30s` đối với luồng xử lý realtime, `< 5m` cho luồng xử lý batch).
   * Nếu Message Age tăng liên tục kéo dài, Consumer đã rơi vào trạng thái **treo/đóng băng (Stalled Worker)** dù tiến trình Pod vẫn báo `Running` trên K8s.

4. **An Toàn Độ Trễ Xử Lý Batch (Poll Interval Safety Margin):**
   * Thời gian poll và xử lý xong 1 batch message (`fetch_latency` / `batch_processing_duration` P95) **`[MUST]`** luôn **$< 50\%$ của `max.poll.interval.ms`** (ngưỡng timeout tối đa của Kafka Broker trước khi coi Consumer là dead node).

5. **Kiểm Soát Tỷ Lệ Lỗi & Dead Letter Topic (Error & DLT Management):**
   * Tỷ lệ lỗi ném Exception trong luồng xử lý nghiệp vụ **`[MUST] < 0.5%`**.
   * Tỷ lệ message thất bại sau retries bị đẩy vào Dead Letter Topic (DLT / DLQ) **`[MUST] < 0.1%`**.
   * Số lần commit offset thất bại (`commit_failed_total`) = `0` để tránh xử lý trùng lặp message.

6. **Tối Ưu Sức Khỏe Worker Thread Pool (Concurrent Worker Saturation):**
   * Mức độ chiếm dụng worker threads xử lý song song **`[MUST] < 80%`** kích thước Thread Pool tối đa.

---

### 5.3.2. Bảng Chi Tiết Thông Số Đo Cần Thiết Cho Consumer (Consumer Metrics Specification)

| Nhóm Metric | Prometheus Metric Name | Loại Metric | Mô Tả Ý Nghĩa Vận Hành | Ngưỡng 🟢 ỔN ĐỊNH (Healthy) | Ngưỡng 🟡 CẢNH BÁO (Degraded) | Ngưỡng 🔴 KHÔNG ỔN ĐỊNH (Critical / Down) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Group State** | `kafka_consumergroup_state` | Gauge | Trạng thái Consumer Group (`1=Stable`, `2=PreparingRebalance`, `3=CompletingRebalance`) | `1` (Stable) | State `2` / `3` kéo dài `< 30s` | State `2`/`3` kéo dài `> 1m` hoặc Rebalance Storm |
| **Rebalance Frequency** | `kafka_consumer_rebalance_latency_seconds_count` | Counter | Tần suất Consumer bị Rebalance trong chu kỳ 1h | `0` rebalances / 1h | `1 - 2` rebalances / 1h | `> 3` rebalances / 1h |
| **Rebalance Duration**| `kafka_consumer_rebalance_latency_seconds_sum` | Summary | Thời gian tiến trình bị tạm dừng để Rebalance | `< 2s` | `2s - 10s` | `> 10s` (Stop-the-world Consumer) |
| **Consume Rate** | `kafka_consumer_records_consumed_total` | Counter | Tốc độ tiêu thụ message (records/sec) | Consume Rate $\ge$ Produce Rate | Consume Rate < Produce Rate tạm thời | Consume Rate = 0 bất thường (Treo worker) |
| **Consumer Lag** | `kafka_consumergroup_lag` | Gauge | Số message chưa tiêu thụ trong Partition | Lag `< 1,000` msgs (hoặc baseline) | Lag `1,000 - 10,000` msgs | Lag `> 10,000` msgs & tăng tịnh tiến |
| **Message Age** | `consumer_last_success_age` | Gauge | **[CRITICAL]** Tuổi của message chưa/vừa xử lý thành công (Độ trễ xử lý) | `< 30s` | `30s - 2m` | `> 5m` (Worker bị đóng băng / Stalled) |
| **Batch Latency** | `kafka_consumer_fetch_latency_seconds_bucket` | Histogram | Thời gian Worker poll & xử lý 1 batch message (P95) | `< 30%` `max.poll.interval.ms` | `30% - 50%` `max.poll.interval.ms` | `> 50%` `max.poll.interval.ms` (Nguy cơ bị Kick Out) |
| **Processing Errors**| `kafka_consumer_processing_errors_total` | Counter | Tỷ lệ message bị Exception khi thực thi logic nghiệp vụ | `< 0.1%` total msgs | `0.1% - 0.5%` total msgs | `> 0.5%` (Lỗi nghiệp vụ hàng loạt) |
| **DLT / DLQ Rate** | `kafka_consumer_dlt_records_total` | Counter | Số message thất bại bị đẩy vào Dead Letter Topic | `< 0.05%` total msgs | `0.05% - 0.2%` total msgs | `> 0.2%` (Rủi ro mất dữ liệu luồng chính) |
| **Commit Failures** | `kafka_consumer_commit_failed_total` | Counter | Số lần offset commit bị thất bại do timeout / expired | `0` | `1 - 3` commits failed / 1h | `> 3` failures (Xử lý trùng lặp lặp đi lặp lại) |
| **Worker Saturation**| `consumer_worker_threads_active` | Gauge | % Concurrent Worker Threads đang xử lý task | `< 75%` Thread Pool | `75% - 85%` Thread Pool | `> 85%` Thread Pool (Cạn kiệt worker) |

---

# VI. KUBERNETES PROBE STANDARD

| Probe Type | Required Endpoint | Scope / Dependency Check | K8s Action when FAILED |
| :--- | :--- | :--- | :--- |
| **Startup Probe** | `/health/startup` (`/actuator/health/liveness`) | Kiểm tra quá trình khởi tạo app (load context, warm-up cache). | Chờ app boot hoàn tất. Quá timeout -> Restart Pod. |
| **Liveness Probe** | `/health/live` (`/actuator/health/liveness`) | **NONE** (Chỉ check internal runtime process & thread pool). | **RESTART POD** |
| **Readiness Probe** | `/health/ready` (`/actuator/health/readiness`) | **Hard Dependencies ONLY** (MySQL DB chính). | **UNROUTE TRAFFIC (Gỡ khỏi K8s Service)** |

---

# VII. APPLICATION MONITORING & SPECIFIC CLIENT METRICS

## 7.1. Node.js Monitoring Specification
**`[MUST]` Monitor các chỉ số:** CPU Usage & Throttling Rate, Memory RSS & Heap Used, Event Loop Lag (`nodejs_eventloop_lag_seconds`), Active Handles, Requests Per Second (RPS), P95/P99 Latency, 4xx/5xx Errors, Restarts, OOMKilled.

## 7.2. Java Spring Boot Monitoring Specification
**`[MUST]` Monitor các chỉ số:** JVM Heap Old Gen / Eden, Non-Heap (Metaspace), GC Pause Time & Frequency (`jvm_gc_pause_seconds_sum`), Thread Count & Thread Pool States, CPU Usage, HTTP RPS, P95/P99 Response Duration, HTTP 5xx Error Rate, HikariCP Pool (Active, Idle, Pending Threads, Timeout Total).

## 7.3. PHP (Laravel / PHP-FPM) Monitoring Specification
**`[MUST]` Monitor các chỉ số:** PHP-FPM Active Processes, Idle Processes, Listen Queue (`phpfpm_listen_queue` > 0 alert quá tải), Max Children Reached Count, HTTP Request Duration (P95/P99), CPU & RAM Saturation, HTTP 5xx Error Rate, DB PDO Connections.

## 7.4. METRICS CỤ THỂ CHO SERVICE KHI TƯƠNG TÁC VỚI HẠ TẦNG & EXTERNAL APIS (`[MUST]`)

> Khi một Application Service tương tác với CSDL, Message Queue HOẶC **gửi HTTP Request gọi API sang Service khác / Third-party API**, Service đó **`[MUST]`** expose đầy đủ các chỉ số phía Client-Side theo 7 bảng dưới đây:

### A. App System & Runtime Metrics (JVM / Node / PHP)
| Nhóm Metric | Prometheus Metric Name | Loại Metric | Mô tả Ý nghĩa Vận hành | Ngưỡng Warning / Alert |
| :--- | :--- | :--- | :--- | :--- |
| **CPU Saturation** | `process_cpu_seconds_total`<br>`container_cpu_cfs_throttled_periods_total` | Counter | CPU Usage & Tỷ lệ bị K8s Throttling do vượt limit | Throttling > 15% trong 5m |
| **Memory Saturation**| `jvm_memory_used_bytes{id="G1 Old Gen"}`<br>`process_resident_memory_bytes` | Gauge | RAM RSS / Old Gen heap đang sử dụng | RAM > 85% Limit |
| **Runtime Lag** | `nodejs_eventloop_lag_seconds`<br>`jvm_gc_pause_seconds_sum` | Summary / Counter | Event loop lag (Node) hoặc thời gian GC Stop-the-world (Java) | Lag > 100ms (Warn), > 500ms (Crit) |
| **HTTP Traffic** | `http_server_requests_seconds_count` | Counter | Requests Per Second (RPS) nã vào Service | Spikes bất thường |
| **HTTP Latency** | `http_server_requests_seconds_bucket` | Histogram | API Response Duration P50, P95, P99 | P95 > 1s, P99 > 3s |
| **HTTP Errors** | `http_server_requests_seconds_count{status=~"5.."}` | Counter | Số lượng và Tỷ lệ lỗi HTTP 5xx | Error Rate > 2% (High), > 5% (Critical) |

### B. MySQL Client Metrics (HikariCP / PDO / Node MySQL)
| Nhóm Metric | Prometheus Metric Name | Loại Metric | Mô tả Ý nghĩa Vận hành | Ngưỡng Warning / Alert |
| :--- | :--- | :--- | :--- | :--- |
| **Active Pool** | `hikaricp_connections_active` | Gauge | Số kết nối MySQL đang bận xử lý SQL query | > 80% Pool Size |
| **Idle Pool** | `hikaricp_connections_idle` | Gauge | Số kết nối MySQL đang rảnh rỗi trong Pool | Pool rảnh < 2 conns |
| **Pending Queue** | `hikaricp_connections_pending` | Gauge | **[CRITICAL]** Số thread phải XẾP HÀNG XIN CONNECTION | Pending > 0 kéo dài > 10s |
| **Pool Timeout** | `hikaricp_connections_timeout_total` | Counter | Tổng số lần thread xin connection thất bại do timeout | Rate > 0 (Cạn kiệt Pool) |
| **Query Latency** | `jdbc_query_execution_seconds_bucket` | Histogram | Thời gian thực thi SQL query nhìn từ phía Client | P95 > 500ms, P99 > 2s |

### C. MongoDB Client Metrics (Spring Data Mongo / Mongo Driver Node.js)
| Nhóm Metric | Prometheus Metric Name | Loại Metric | Mô tả Ý nghĩa Vận hành | Ngưỡng Warning / Alert |
| :--- | :--- | :--- | :--- | :--- |
| **Driver Conns** | `mongodb_driver_pool_checked_out_connections` | Gauge | Số kết nối Mongo đang được mượn ra xử lý query | > 80% Client Max Pool |
| **Wait Queue** | `mongodb_driver_pool_wait_queue_size` | Gauge | Số request đang chờ xin Mongo connection | Wait Queue > 0 trong 10s |
| **Command Time** | `mongodb_driver_commands_seconds_bucket` | Histogram | Latency từng command Mongo (find, update, aggregate) | P95 > 300ms |
| **Command Errors**| `mongodb_driver_commands_failed_total` | Counter | Số command Mongo bị Exception | Rate > 5 errors/min |

### D. Redis Client Metrics (Lettuce Java / ioredis Node.js / Predis PHP)
| Nhóm Metric | Prometheus Metric Name | Loại Metric | Mô tả Ý nghĩa Vận hành | Ngưỡng Warning / Alert |
| :--- | :--- | :--- | :--- | :--- |
| **Command Time** | `lettuce_command_completion_seconds_bucket` | Histogram | Latency đọc/ghi Redis từ Client | P95 > 50ms, P99 > 200ms |
| **Cache Hit/Miss**| `redis_cache_gets_total{result="hit"}` / `{result="miss"}` | Counter | Đếm số lần Cache Hit vs Cache Miss | Hit Rate < 70% |
| **Client Errors** | `redis_client_errors_total` | Counter | Lỗi kết nối Redis (Timeout, Connection refused) | Rate > 0 (Soft Dep Fallback) |

### E. Kafka Client Metrics (Producer & Consumer trong Service)
| Nhóm Metric | Prometheus Metric Name | Loại Metric | Mô tả Ý nghĩa Vận hành | Ngưỡng Warning / Alert |
| :--- | :--- | :--- | :--- | :--- |
| **Producer Send** | `kafka_producer_record_send_total` | Counter | Tổng số message Produce thành công ra Kafka Broker | Drop đột ngột về 0 |
| **Producer Error**| `kafka_producer_record_error_total` | Counter | Số message Produce thất bại | Error Rate > 1% |
| **Consumer Lag** | `kafka_consumergroup_lag` | Gauge | Số message chưa xử lý trong Topic Partition | Lag Growth Rate > 0 (Xem chi tiết Mục 5.3) |
| **Message Age** | `consumer_last_success_age` | Gauge | **[CRITICAL]** Thời gian từ message xử lý thành công tới now | Age > 2m (Warn), > 5m (Crit - Xem chi tiết Mục 5.3) |
| **Poll Duration** | `kafka_consumer_fetch_latency_seconds_bucket` | Histogram | Thời gian Worker poll & process 1 batch message | P95 > max.poll.interval.ms |

### F. Aerospike Client Metrics (Aerospike Java/Node Driver)
| Nhóm Metric | Prometheus Metric Name | Loại Metric | Mô tả Ý nghĩa Vận hành | Ngưỡng Warning / Alert |
| :--- | :--- | :--- | :--- | :--- |
| **Client Read Time** | `aerospike_client_read_latency_seconds_bucket` | Histogram | Độ trễ thao tác đọc Aerospike từ Client | P95 > 10ms, P99 > 50ms |
| **Client Write Time**| `aerospike_client_write_latency_seconds_bucket` | Histogram | Độ trễ thao tác ghi Aerospike từ Client | P95 > 20ms, P99 > 100ms |
| **Client Errors** | `aerospike_client_errors_total` | Counter | Tổng số lỗi giao tiếp Aerospike | Rate > 1% total reqs |

### G. Inter-Service & External API Client Metrics (RestTemplate / WebClient / Axios / Guzzle / Resilience4j)
| Nhóm Metric | Prometheus Metric Name | Loại Metric | Mô tả Ý nghĩa Vận hành | Ngưỡng Warning / Alert |
| :--- | :--- | :--- | :--- | :--- |
| **Outbound RPS** | `http_client_requests_seconds_count` | Counter | Tốc độ gửi HTTP Request gọi sang Service khác / 3rd Party API | Spikes bất thường |
| **Outbound Latency**| `http_client_requests_seconds_bucket` | Histogram | Độ trễ thực thi HTTP Client Call sang Service target (P95/P99) | P95 > Timeout Target (e.g., > 3s) |
| **Outbound 5xx/4xx**| `http_client_requests_seconds_count{status=~"5.."}` | Counter | Số lượt gọi API bị Target Service phản hồi lỗi 5xx hoặc 4xx | Error Rate > 2% |
| **Timeout Exception**| `http_client_exceptions_total{exception=~".*TimeoutException"}` | Counter | **[CRITICAL]** Số lần bị Connect Timeout / Read Timeout ngắt kết nối | Rate > 5 errors/min |
| **Circuit Breaker** | `resilience4j_circuitbreaker_state` | Gauge | **[CRITICAL]** Trạng thái Circuit Breaker (`0=CLOSED`, `1=OPEN`, `2=HALF_OPEN`) | State == 1 (OPEN - Ngắt mạch) |
| **CB Slow Calls** | `resilience4j_circuitbreaker_slow_calls_total` | Counter | Số cuộc gọi bị đánh dấu là Slow Call làm kích hoạt ngắt mạch | Rate > 10 slow calls/min |

---

# =========================================================
# PHẦN II: CHI TIẾT LỘ TRÌNH TRIỂN KHAI THEO THỜI GIAN & KHUNG ƯU TIÊN
# =========================================================

> **TƯ DUY & NGUYÊN TẮC CỐT LÕI: THU THẬP METRICS & HEALTH CHECKS TRƯỚC, NOTIFICATION & ALERTING SAU (`[MUST]`)**
> 
> * **Lý do Kỹ thuật:** Hệ thống cảnh báo (Notification / Alerting) chỉ phát huy hiệu quả khi dữ liệu đo lường (Metrics) và cơ chế kiểm tra nhịp tim (Health Probes) đã được thu thập đầy đủ, chuẩn hóa và có baseline ổn định. Việc phát cảnh báo quá sớm khi chưa hoàn thành thu thập Metrics & Health Checks sẽ gây ra **Alert Fatigue** (bão cảnh báo rác), thông báo sai lệch và khiến đội ngũ vận hành không thể xác định đúng nguyên nhân gốc (Root Cause).
> * **Thứ tự Ưu tiên Triển khai:** 
>   1. **Giai đoạn 1 & 2:** Chuẩn hóa Health Probes + Thu thập toàn bộ Baseline Metrics (Runtime, Performance, Dependency, Consumer Lag/Age).
>   2. **Giai đoạn 3:** Centralized Dashboards, Centralized Logs (Loki) & Distributed Tracing (Hoàn thiện 3 trụ cột Observability).
>   3. **Giai đoạn 4:** Mới tiến hành Cấu hình Alerting Rules, Tích hợp Kênh Thông báo Notification (Slack, Telegram, Teams, PagerDuty), Khử trùng lặp & Escalation Matrix.

---

# XVII. DANH MỤC CÁC CHỈ SỐ BẮT BUỘC PHẢI CÓ TRƯỚC (PHASE 1 - MUST-HAVE BASELINE METRICS)

> **QUY ĐỊNH BẮT BUỘC (`[MUST]`):** Trước khi thiết lập hệ thống cảnh báo (Alerting) hoặc phân tích nâng cao, mọi Microservice khi được onboard vào hệ thống giám sát **BẮT BUỘC PHẢI THU THẬP VÀ HIỂN THỊ ĐỦ 5 NHÓM CHỈ SỐ BASELINE BAN ĐẦU** dưới đây.

| STT | Nhóm Chỉ Số Baseline | Prometheus Metric Name / Target Endpoint | Ý Nghĩa Kỹ Thuật & Lý Do Phải Thu Thập Trước | Ngưỡng Baseline An Toàn 🟢 |
| :--- | :--- | :--- | :--- | :--- |
| **1** | **K8s Health Probes Standard** | `GET /health/live`<br>`GET /health/ready` | **[CRITICAL]** Kiểm tra nhịp tim Pod. `Liveness` chỉ check runtime app (không check DB). `Readiness` check kết nối DB chính có Timeout $\le 2\text{s}$. Ngăn chặn sập dây chuyền. | 200 OK |
| **2** | **System & Runtime Baseline** | `container_cpu_cfs_throttled_periods_total`<br>`jvm_memory_used_bytes`<br>`nodejs_eventloop_lag_seconds`<br>`kube_pod_container_status_restarts_total` | Giám sát mức bão hòa CPU/RAM, thời gian khựng tiến trình (GC Pause / Event Loop Lag) và số lần Pod restart / bị OOMKilled. | Throttling $< 5\%$<br>RAM $< 75\%$<br>Lag $< 50\text{ms}$<br>Restarts $= 0$ |
| **3** | **Application Performance (RED Metrics)** | `http_server_requests_seconds_count`<br>`http_server_requests_seconds_bucket`<br>`http_server_requests_seconds_count{status=~"5.."}` | **4 Golden Signals Core**: Đo tổng lưu lượng (RPS), độ trễ phản hồi P95/P99 và tỷ lệ lỗi server HTTP 5xx %. Làm cơ sở tính toán SLO. | RPS Baseline<br>P95 $< 500\text{ms}$<br>5xx Rate $< 0.5\%$ |
| **4** | **Core Dependency Pool Status** | `hikaricp_connections_active`<br>`hikaricp_connections_pending`<br>`redis_client_errors_total` | **[CRITICAL]** Giám sát tình trạng cạn kiệt Connection Pool. **Pending Queue > 0 kéo dài > 10s** là dấu hiệu bế tắc kết nối DB làm treo app. | Active $< 70\%$<br>Pending $= 0$<br>Errors $= 0$ |
| **5** | **Async Consumer Priority Baseline** | `kafka_consumergroup_lag`<br>`consumer_last_success_age`<br>`kafka_consumergroup_state` | **[CRITICAL]** Giám sát tồn đọng message (Lag), thời gian kẹt luồng xử lý (`Message Age > 5m` $\rightarrow$ Stalled Worker) và trạng thái Consumer Group (`1=STABLE`). | Lag $< 1,000$<br>Age $< 30\text{s}$<br>State $= 1$ |

### Tại Sao Phải Có Bộ Chỉ Số Này Trước Khi Làm Notification & Alerting?
1. **Thiết lập Baseline Chuẩn:** Nếu không có dữ liệu RPS, P95 Latency và Error Rate bình thường, sẽ không thể thiết lập ngưỡng Alerting chính xác, dẫn đến bão cảnh báo giả (Alert Fatigue).
2. **Khóa Nguy Cơ Treo Ẩn (Stalled Service):** Metrics về `hikaricp_connections_pending` và `consumer_last_success_age` giúp phát hiện ngay các sự cố treo im lặng (Silent Stalls) mà K8s Liveness Probe thông thường không thể phát hiện.
3. **Cơ Sở Cho Multi-Window Burn Rate:** Thuật toán tính toán tốc độ tiêu hao Error Budget (Burn Rate) bắt buộc phải dựa trên tỷ lệ lỗi 5xx trên tổng RPS thu thập liên tục qua Prometheus.

---

# XVIII. KHUNG PHÂN LOẠI ƯU TIÊN THỰC HIỆN (PRIORITIZATION FRAMEWORK)

| Cấp độ | Ý nghĩa | Tiêu chí Chọn lựa | Ví dụ Hạng mục |
| :--- | :--- | :--- | :--- |
| 🔴 **P0 (Critical Immediate)** | **Khẩn cấp — Làm ngay (Tuần 1–2)** | Chuẩn hóa Health Checks & Thu thập Baseline System Metrics | Fix Liveness/Readiness Probes (loại bỏ DB check rủi ro), Expose Core Runtime Metrics |
| 🟠 **P1 (High Foundation)** | **Ưu tiên Cao (Tuần 3–7)** | **ƯU TIÊN THU THẬP METRICS, HEALTH & CENTRALIZED OBSERVABILITY** | Thu thập Full Application Metrics, DB Pools, Kafka Age, Centralized Dashboards, Loki Logs & OTel Tracing |
| 🟡 **P2 (Medium Enhancement)** | **Ưu tiên Vừa (Tuần 8–10)** | **NOTIFICATION & ALERTING (SAU METRICS & HEALTH)** + Chaos Validation | Multi-Window Burn Rate Alerts, Notification Channels Integration (Slack/Teams/PagerDuty), Chaos Testing |
| 🔵 **P3 (Advanced Reporting)** | **Nâng cao (Tuần 11–12)** | Tự động hóa báo cáo, capacity planning & tối ưu chi phí | Daily/Monthly Automated Reports, Yearly Capacity Review |

---

# XIX. CHI TIẾT LỘ TRÌNH TRIỂN KHAI 12 TUẦN (WEEK-BY-WEEK ROADMAP)

```text
TUẦN 1-2 (P0)      TUẦN 3-4 (P1)      TUẦN 5-7 (P1)      TUẦN 8-9 (P2)      TUẦN 10-11 (P2)    TUẦN 12 (P3)
┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  PHASE 1:    │   │  PHASE 2:    │   │  PHASE 3:    │   │  PHASE 4:    │   │  PHASE 5:    │   │  PHASE 6:    │
│ Health Probe │──>│ Data Form &  │──>│ Full Metrics │──>│ Alerting &   │──>│ Chaos Test   │──>│ Business &   │
│ & Base Metric│   │ Matrix (P1)  │   │ Dashboards   │   │ Notification │   │ Validation   │   │ Reports (P3) │
│     (P0)     │   │              │   │ Logs/Traces  │   │  (P2 SAU)    │   │     (P2)     │   │              │
└──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘
```

## 19.1. Phase 1 — Foundation Health Probes & Baseline Metrics (Tuần 1–2) `[ƯU TIÊN P0]`
* **Mục tiêu:** Loại bỏ rủi ro sập dây chuyền (Cascading Failure), chuẩn hóa nhịp tim Pod và thu thập bộ chỉ số baseline ban đầu (Phần XVII).
* **Phân công nhiệm vụ cụ thể:**
  * **DevOps / SysAdmin:** 
    1. Audit 100% K8s Deployment YAML của tất cả microservices.
    2. Edit Liveness Probe: Loại bỏ hoàn toàn kiểm tra CSDL/Kafka ra khỏi `/health/live`.
    3. Edit Readiness Probe: Cấu hình `/health/ready` check kết nối DB chính có Timeout Guard $\le 2\text{s}$.
    4. Tách biệt Management Port `8081` trên Spring Boot Actuator, chặn truy cập ngoài Internet.
  * **Developers:**
    1. Bổ sung dependency Micrometer Prometheus Exporter trong dự án Java/Node.js/PHP.
    2. Expose các chỉ số CPU, RAM, HTTP RPS, HTTP 5xx Error Rate và HikariCP Pool metrics.
* **Sản phẩm đầu ra (Deliverables):** 100% Pod Microservices expose core metrics + K8s Probes chuẩn hóa trên môi trường Staging/Prod.
* **Tiêu chí nghiệm thu (DoD):** Khi ngắt kết nối MySQL DB, Pod **KHÔNG bị K8s Restart** (Liveness pass), chỉ ngắt traffic (Readiness fail 503).

## 19.2. Phase 2 — Data Specification & Client-Side Dependency Matrix (Tuần 3–4) `[ƯU TIÊN P1 - METRICS & HEALTH]`
* **Mục tiêu:** Chuẩn hóa 100% dữ liệu đầu vào và ma trận phụ thuộc (Dependency Matrix) của các Service trước khi mở rộng thu thập.
* **Phân công nhiệm vụ cụ thể:**
  * **Developers:**
    1. Đăng ký thông số dịch vụ theo mẫu **Required Monitoring Data Specification** (Mục III).
    2. Đăng ký ma trận phụ thuộc **Service Dependency Matrix** (Mục IV) cho từng API & Worker (Connection Pool Min/Max, Timeout, Retry, Circuit Breaker).
    3. Expose chi tiết Client-Side Metrics: Mongo Driver Pool, Redis Client Errors, Kafka Producer/Consumer Metrics, Resilience4j Circuit Breaker State.
  * **DevOps:**
    1. Rà soát định mức CPU/Memory Request & Limit của từng Pod, phát hiện các Pod thiếu Limit hoặc đặt Quota sai lệch.
* **Sản phẩm đầu ra (Deliverables):** Ma trận phụ thuộc hoàn chỉnh của hệ thống Microservices + Full Client-Side Metrics Exporters.
* **Tiêu chí nghiệm thu (DoD):** Mọi cuộc gọi DB/Outbound API đều có thông số Latency P95, Error Rate và Active Pool được Prometheus scrape thành công.

## 19.3. Phase 3 — Full Metrics Collection, Centralized Dashboards, Logs & Tracing (Tuần 5–7) `[ƯU TIÊN P1 - METRICS & HEALTH]`
* **Mục tiêu:** Hoàn thiện 100% việc thu thập Metrics, Health Checks và tập trung Logs (Loki), Distributed Tracing (Tempo/Jaeger) — Hoàn thành 3 trụ cột Observability.
* **Phân công nhiệm vụ cụ thể:**
  * **DevOps / SRE:**
    1. Triển khai Grafana Single Dashboard tập trung theo 4 Golden Signals cho toàn bộ Microservices.
    2. Triển khai Dashboard Kafka 4 tầng (Cluster $\rightarrow$ Topic $\rightarrow$ Consumer Group $\rightarrow$ Partition) hiển thị **Message Age** (`consumer_last_success_age`).
    3. Cấu hình Promtail / Vector đẩy JSON Structured Logs từ `STDOUT` về Grafana Loki (có bảo mật PII Masking).
    4. Triển khai **OpenTelemetry Collector** và đính kèm `TraceId`, `CorrelationId` (W3C `traceparent` Header) xuyên suốt luồng gọi API và Async Worker.
* **Sản phẩm đầu ra (Deliverables):** Grafana Centralized Observability Hub với đầy đủ Metrics, Logs và Distributed Tracing.
* **Tiêu chí nghiệm thu (DoD):** Từ 1 lỗi 5xx trên Grafana Dashboard, SRE có thể click nhảy trực tiếp sang Log tương ứng trong Loki và xem Trace tree trong Tempo.

## 19.4. Phase 4 — Alerting Rules, Notification Integrations & Escalation Matrix (Tuần 8–9) `[ƯU TIÊN P2 - NOTIFY SAU METRICS & HEALTH]`
* **Mục tiêu:** Kích hoạt cảnh báo tự động, tích hợp kênh thông báo (Notification) và quy trình xử lý sự cố dựa trên dữ liệu Metrics & Health đã ổn định.
* **Phân công nhiệm vụ cụ thể:**
  * **SRE / DevOps:**
    1. Xây dựng Alertmanager Rules dựa trên baseline Metrics thu thập từ Phase 3 (Multi-Window Multi-Burn-Rate Alerting).
    2. Triển khai tích hợp kênh thông báo **Notification** (Slack, Telegram, Teams, PagerDuty).
    3. Cấu hình khử trùng lặp cảnh báo (Deduplication) và routing tin nhắn theo **Escalation Matrix** (Mục XIV).
    4. Gắn trực tiếp link **Runbook xử lý** vào mọi tin nhắn cảnh báo Critical.
* **Sản phẩm đầu ra (Deliverables):** Hệ thống Cảnh báo tự động chuẩn SRE, không bão alert rác, gửi đúng kênh/đúng người.
* **Tiêu chí nghiệm thu (DoD):** Khi giả lập sự cố, tin nhắn cảnh báo gửi về Slack/PagerDuty trong vòng $< 3$ phút có đầy đủ thông tin Context và Runbook URL.

## 19.5. Phase 5 — Failure Scenario Testing (Chaos Validation) (Tuần 10–11) `[ƯU TIÊN P2]`
* **Mục tiêu:** Kiểm thử khả năng chịu lỗi của hạ tầng và xác nhận độ chính xác của Health Probes, Metrics và Notifications.
* **Phân công nhiệm vụ cụ thể:**
  * **QA / SRE / DevOps:**
    1. Chạy đầy đủ **6 Kịch bản Chaos Testing** trên môi trường Staging (Mục XV).
    2. Kiểm tra xem khi ngắt DB, Readiness Probe có gỡ Pod khỏi Service traffic đúng cách hay không.
    3. Xác nhận Notification Alerting có gửi cảnh báo đúng severity và đính kèm Runbook hay không.
* **Sản phẩm đầu ra (Deliverables):** Báo cáo Đánh giá Kháng lỗi & Nghiệm thu Hệ thống Giám sát (Chaos Validation Report).
* **Tiêu chí nghiệm thu (DoD):** Pass 100% 6 kịch bản Chaos Test không gây sập dây chuyền hạ tầng.

## 19.6. Phase 6 — Business Monitoring & Automated Reporting (Tuần 12) `[ƯU TIÊN P3]`
* **Mục tiêu:** Đảm bảo sức khỏe nghiệp vụ và tự động hóa báo cáo định kỳ cho Ban quản lý.
* **Phân công nhiệm vụ cụ thể:**
  * **Developers & DevOps:**
    1. Bổ sung các chỉ số Business Health SLI: Login Success Rate, Submission Rate, Notification Rate.
    2. Tự động hóa gửi **Daily Report (08:00 AM)** qua Slack/Email tổng hợp Uptime, Top Slow Queries, Top Lag Groups.
    3. Thiết lập **Monthly SLA/SLO Compliance Report** & **Yearly Capacity Review**.
* **Sản phẩm đầu ra (Deliverables):** Hệ thống Báo cáo Tự động cho Ban Quản Lý (Executive Automated Reporting System).
* **Tiêu chí nghiệm thu (DoD):** Ban quản lý nhận báo cáo Uptime & Performance tự động mỗi 08:00 AM qua kênh Slack/Email.

---

# XX. MA TRẬN PHỤ THUỘC KỸ THUẬT VÀ ĐIỀU KIỆN TIÊN QUYẾT (PREREQUISITE MATRIX)

```text
[Phase 1: Baseline Health & Metrics] ──> [Phase 2: Data Spec & Matrix] ──> [Phase 3: Full Metrics, Logs & Traces]
                                                                                            │
                                                                                            ▼
[Phase 6: Business Reports] <───────── [Phase 5: Chaos Validation] <────── [Phase 4: Alerting & Notification]
```

| Hạng mục Mục tiêu | Điều kiện Bắt buộc Phải Làm Trước | Lý do Kỹ thuật |
| :--- | :--- | :--- |
| **Cấu hình Alerting & Notification (Phase 4)** | **Hoàn thành Full Metrics & Health Collection (Phase 1, 2, 3)** | **Alert Burn Rate & Notification cần dữ liệu Error Rate & RPS ổn định từ Prometheus mới tính được tốc độ tiêu hao Error Budget và tránh bão cảnh báo rác.** |
| **Distributed Tracing Tempo (Phase 3)** | Hoàn thành OTel Collector & W3C Header propagation | Nếu App chưa truyền `traceparent` header thì Tempo không thể nối chuỗi các span thành luồng trace hoàn chỉnh. |
| **Chaos Testing Validation (Phase 5)** | Hoàn thành Fix K8s Probes (Phase 1) & Notification (Phase 4) | Nếu chưa fix Liveness Probe, chạy Chaos Test (ngắt DB) sẽ làm Pod restart ngay lập tức -> Fail test. |
| **Automated Daily Report (Phase 6)** | Hoàn thành Centralized Metrics & Loki Logs (Phase 3) | Báo cáo cần trích xuất Top Slow Query từ Loki và Availability % từ Prometheus. |
