# Runbook triển khai & cấu hình production

Tài liệu này là hand-off giữa Backend và DevOps cho K12 Platform realtime. Nó mô tả các điều kiện triển khai cần có từ code hiện tại; **không phải** manifest Kubernetes/Ingress hoàn chỉnh. Không tự suy diễn controller, cloud provider, tên namespace, hay giá trị resource limit khi chưa có capacity test.

## 1. Thành phần và bề mặt mạng

| Thành phần | Cổng | Phơi ra ngoài | Mục đích |
| --- | ---: | --- | --- |
| Gateway WebSocket | `9000` | Có, chỉ qua Ingress/LB TLS | Kết nối realtime của client |
| Gateway management | `8080` | Không | Actuator, metrics, health check, relay webhook Alertmanager (`POST /internal/alertmanager-webhook`, xem mục 6) |
| Engine frame server | `9100` | Không | Gateway gửi frame vào game engine |
| Engine management | `8090` | Không | Actuator, metrics, health check, relay webhook Alertmanager (`POST /internal/alertmanager-webhook`, xem mục 6) |
| Valkey | `6379` | Không | Room ownership (bắt buộc, `LeaseBasedRoomOwnership` luôn chạy) + discovery khi được bật |
| Kafka | `9092` | Không | Event bất đồng bộ khi được bật |

Luồng bắt buộc: `Client -> TLS Ingress/LB -> Gateway:9000 -> Engine:9100`.

Chỉ Gateway được phép gọi `Engine:9100`. Actuator, Valkey, Kafka và OTLP collector phải nằm trên private network / NetworkPolicy phù hợp. Không expose trực tiếp cổng 9000 hoặc 9100 ra Internet ngoài lớp Ingress/LB. `/internal/alertmanager-webhook` (module `uni-observability`, chạy trên cả 2 service vì cùng scan `com.uni.realtime`) cũng phải ở private network như actuator — không phải endpoint cho client.

## 2. Cấu hình Gateway

| Biến môi trường | Giá trị/ý nghĩa production |
| --- | --- |
| `SERVER_PORT` | `8080` (management nội bộ) |
| `GATEWAY_WS_PORT` | `9000` |
| `ENGINE_PODS` | Danh sách tĩnh dạng `host:9100`, dùng khi discovery tắt |
| `GATEWAY_ENGINE_POD_DISCOVERY_ENABLED` | Chỉ bật sau khi Valkey-based discovery được kiểm thử staging |
| `GATEWAY_ENGINE_POD_DISCOVERY_ROOM_STORE_URI` | URI Valkey nội bộ khi discovery bật (tên biến thật trong code — không phải `..._VALKEY_URI`) |
| `GATEWAY_ENGINE_POD_DISCOVERY_POLL_INTERVAL_SECONDS` | Chu kỳ refresh discovery, đơn vị **giây** (tên biến thật — không phải `_MS`); dùng giá trị đã load-test, không giảm tuỳ tiện |
| `GATEWAY_DEV_JOIN_TOKEN_ENABLED` | **`false`** |
| `GATEWAY_ALWAYS_ACCEPT_JOIN_TOKEN_ENABLED` | **`false`** |
| `GATEWAY_DEV_JOIN_TOKEN_SECRET` | Không cấp cho production; chỉ phục vụ dev token verifier |
| `GATEWAY_DEV_JOIN_TOKEN_TTL_SECONDS` | Mặc định `300`; chỉ có ý nghĩa khi `GATEWAY_DEV_JOIN_TOKEN_ENABLED=true` — không cấp cho production |
| `GATEWAY_DEV_JOIN_TOKEN_REPLAY_GUARD_ENABLED` | Mặc định `false`; chỉ có ý nghĩa khi `GATEWAY_DEV_JOIN_TOKEN_ENABLED=true` — không cấp cho production |
| `OTLP_ENDPOINT` | Endpoint OTLP nội bộ, ví dụ collector `:4318` |

`docker-compose.dev.yml` có bật dev join token và discovery để phục vụ local/integration testing. Không dùng các giá trị đó làm baseline production.

Ngoài bảng trên, cả Gateway lẫn Engine còn nhận 3 biến môi trường của module dùng chung `uni-observability` (relay cảnh báo) — xem mục 6.

## 3. Cấu hình Game Engine

Mỗi engine instance phải có `ENGINE_POD_ID` riêng. `ENGINE_ADVERTISED_HOST` phải phân giải được từ Gateway và chỉ chứa host/DNS; ứng dụng tự ghép cổng frame `9100`.

> **Cập nhật 2026-09-09 (Task 23 — `room_id % N` đã bị xoá khỏi code):** `ENGINE_POD_COUNT` và
> `ENGINE_ROOM_STORE_ENABLED` **không còn tồn tại**. `LeaseBasedRoomOwnership` giờ là
> `RoomOwnership` DUY NHẤT, LUÔN chạy — Valkey/room-store là dependency **bắt buộc** để Engine
> khởi động (không còn "bật/tắt", không còn fallback modulo khi Valkey mất kết nối lúc giành
> lease). Xem `CLAUDE.md` mục "Known Phase 1 trade-offs" và
> `docs/work/NOJIRA-uni-p1-realtime-core/plan.md` Task 23.

| Biến môi trường | Giá trị/ý nghĩa production |
| --- | --- |
| `SERVER_PORT` | `8090` (management nội bộ) |
| `ENGINE_FRAME_PORT` | `9100` |
| `ENGINE_POD_ID` | ID duy nhất, ổn định trong vòng đời instance, ví dụ `engine-0` |
| `ENGINE_ADVERTISED_HOST` | DNS/IP nội bộ Gateway có thể gọi tới |
| `ENGINE_ROOM_STORE_URI` | URI Valkey nội bộ — **bắt buộc phải đúng và khả dụng**, Engine không khởi động được nếu thiếu (tên biến thật — không phải `..._VALKEY_URI`) |
| `ENGINE_ROOM_STORE_LEASE_TTL_SECONDS` | TTL lease, mặc định `20s`; chỉ thay đổi sau khi kiểm thử mất node/partition (tên biến thật — không phải `..._LEASE_TTL`) |
| `ENGINE_KAFKA_ENABLED` | Bật khi Kafka async event pipeline sẵn sàng vận hành |
| `ENGINE_KAFKA_BOOTSTRAP_SERVERS` | Bootstrap broker nội bộ |
| `ENGINE_KAFKA_TOPIC` | Mặc định `game.events.v1` |
| `ENGINE_KAFKA_QUEUE_CAPACITY` | Mặc định `1000`; phải có alert khi queue bị đầy/drop |
| `OTLP_ENDPOINT` | Endpoint OTLP nội bộ |

### Quy tắc scale engine

**Cập nhật 2026-09-09:** rủi ro gốc mà mục này từng cảnh báo ("đổi `ENGINE_POD_COUNT` làm vỡ hash
`room_id % N`") không còn tồn tại — không còn `N` nào để đổi, số lượng Engine pod tự do thay đổi về
mặt thuật toán ownership. Nhưng **chưa được coi là an toàn để autoscale tự do**: `LeaseBasedRoomOwnership`
+ Valkey vẫn chưa verify ở staging thật (chỉ Docker 1 máy) — xem
[`engine-scaling-freeze.md`](engine-scaling-freeze.md) (vẫn còn hiệu lực, lý do đã đổi) và quy
trình đo cụ thể ở [`staging-valkey-cluster-verification.md`](staging-valkey-cluster-verification.md).
Rủi ro MỚI cần biết: Valkey giờ là single point of failure cho việc TẠO PHÒNG MỚI (không chỉ Hot
Snapshot như trước) — mất Valkey Cluster hoàn toàn nghĩa là không phòng mới nào tạo được tới khi
phục hồi (§2.6 của runbook staging).

## 4. Ingress, TLS và chống flood

Ingress/LB phải terminate TLS, chuyển tiếp WebSocket `Upgrade` nguyên vẹn và có idle timeout lớn hơn 30 giây ở cả hai chiều. Cấu hình cụ thể theo controller được để trong [ingress-websocket-requirements.md](ingress-websocket-requirements.md).

Rate limit trong ứng dụng chỉ là lớp bảo vệ theo session/kết nối; nó không thay thế bảo vệ hạ tầng trước DDoS. DevOps cần cấu hình ở CDN/WAF/LB theo năng lực nền tảng:

- giới hạn concurrent WebSocket connections, new connections và request/byte rate theo IP/ASN;
- connection timeout, connection cap và backpressure ở edge;
- rule chống handshake flood, bot và IP reputation; alert khi reject tăng đột biến;
- rate limit ở edge phải trả mã/lý do phù hợp và không retry loop vô hạn;
- giám sát memory, open file descriptors, active connections, network bandwidth và egress.

Không coi in-memory rate-limit state là lớp DDoS storage. State trong code được giới hạn TTL/kích thước; edge vẫn phải chặn traffic trước khi nó tạo kết nối đến JVM.

## 5. Secrets và cấu hình nhạy cảm

- Inject secrets từ secret manager/Kubernetes Secret; không commit vào image, ConfigMap thường, compose file production hoặc log.
- Tách credential Valkey/Kafka/OTLP khỏi URI public. Chỉ dùng TLS/SASL/auth khi ứng dụng và thư viện đã được Backend xác nhận hỗ trợ end-to-end.
- Không bật `GATEWAY_DEV_JOIN_TOKEN_ENABLED` hoặc `GATEWAY_ALWAYS_ACCEPT_JOIN_TOKEN_ENABLED` ở bất kỳ môi trường có người dùng thật.
- `GOOGLE_CHAT_WEBHOOK_URL` (chứa token trong query string), `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` (mục 6) là secret, cùng loại xử lý như credential Valkey/Kafka — không commit giá trị thật (repo chỉ commit `observability/.env.example` với placeholder `REPLACE_ME`; `observability/.env` chứa giá trị thật bị `.gitignore` chặn).
- Rotate secret và credential theo quy trình nền tảng; rollout phải tránh log giá trị biến môi trường.

## 6. Probes, observability và resource

Hai service expose actuator nội bộ:

| Service | Liveness | Readiness | Metrics |
| --- | --- | --- | --- |
| Gateway | `http://<gateway>:8080/actuator/health/liveness` | `http://<gateway>:8080/actuator/health/readiness` | `http://<gateway>:8080/actuator/prometheus` |
| Engine | `http://<engine>:8090/actuator/health/liveness` | `http://<engine>:8090/actuator/health/readiness` | `http://<engine>:8090/actuator/prometheus` |

Dockerfile hiện kiểm tra TCP (`9000` cho Gateway, `9100` cho Engine). Trong orchestrator, ưu tiên HTTP readiness/liveness actuator nội bộ và chỉ đưa pod vào load balancer khi readiness pass.

Image đã chạy non-root và dùng `-XX:MaxRAMPercentage=75.0`; do đó memory limit của container phải được đặt rõ ràng. CPU/memory request, limit, replica count và HPA target phải xuất phát từ load test đại diện cho số connection, số room, submit rate và message size thực tế — không dùng con số mặc định trong tài liệu này.

Thu thập JSON logs, Prometheus metrics và traces qua `OTLP_ENDPOINT`. Alert tối thiểu: restart/OOM, readiness failure, active connection spike, rate-limit rejection spike, engine unavailable, room-store/lease failure, Kafka queue saturation/drop và p95/p99 latency tăng.

### 6.1 Relay cảnh báo Alertmanager → Google Chat/Telegram

Cả 2 service đều expose `POST /internal/alertmanager-webhook` (từ `uni-observability`, cùng `management`/`server` port ở bảng mục 1 — không có `management.server.port` riêng). Endpoint này nhận đúng payload `webhook_configs` gốc của Alertmanager rồi relay sang Google Chat/Telegram, vì Alertmanager không thể POST thẳng tới webhook của Google Chat (khác schema payload).

| Biến môi trường | Giá trị/ý nghĩa production |
| --- | --- |
| `GOOGLE_CHAT_WEBHOOK_URL` | URL webhook thật của Google Chat space nhận cảnh báo (rỗng = kênh này tắt) |
| `TELEGRAM_BOT_TOKEN` | Token bot Telegram (rỗng = kênh này tắt) |
| `TELEGRAM_CHAT_ID` | Chat/group ID Telegram nhận cảnh báo |

**Giới hạn đã biết (ghi rõ trong javadoc `AlertmanagerWebhookController`, không phải thiếu sót):** relay này chạy trong **cùng JVM** với service đang được giám sát — nếu chính instance đó down thì cảnh báo "AppDown" của instance đó không relay được qua chính nó. Chấp nhận được cho dev/demo, **không phải relay ngoài tiến trình (out-of-process)** cho một triển khai production thật; production nên trỏ Alertmanager `webhook_configs` tới một relay độc lập, không phụ thuộc vào service đang được giám sát còn sống hay không.

`observability/alertmanager/alertmanager.yml` (dùng cho stack Docker Compose observability cục bộ) hiện trỏ webhook tới `http://host.docker.internal:8080` — chỉ đúng khi Alertmanager và service chạy trên cùng máy Docker Desktop; production phải trỏ tới địa chỉ nội bộ thật của Gateway/Engine.

## 7. Trình tự triển khai và rollback

1. Build artifact bất biến, ký/pin image digest và chạy CI test/gate của Backend.
2. Sẵn sàng Valkey (bắt buộc, Engine không khởi động được nếu thiếu), Kafka (nếu được bật) và OTLP/metrics/logging trên private network.
3. Deploy Engine trước; xác nhận readiness và Gateway có thể TCP-connect đến từng `:9100`.
4. Deploy Gateway; xác nhận readiness rồi mới add vào Ingress/LB.
5. Thực hiện smoke test qua **public TLS WebSocket endpoint**.

Rollback theo artifact đã biết tốt. Khi rollback Engine, phải tính session/room đang active: giữ nguyên topology tĩnh hoặc đảm bảo room-store + discovery đã được kiểm thử trước. (Không còn "fallback modulo" để lo — room-store giờ luôn bắt buộc — nhưng vẫn không nên scale Engine tuỳ tiện trong lúc rollback trước khi `LeaseBasedRoomOwnership` được verify staging, xem mục 3.)

## 8. Smoke test bắt buộc trước mở traffic

- `health/liveness`, `health/readiness`, `prometheus` chỉ truy cập được từ mạng quản trị.
- Mở một WebSocket qua hostname TLS thực tế, xác thực bằng token production hợp lệ, join room và gửi/nhận message bình thường.
- Giữ kết nối idle quá 60 giây rồi tiếp tục tương tác để xác nhận timeout/heartbeat đường đi.
- Reconnect client và kiểm tra route đúng room/engine.
- Gửi chuỗi submit vượt ngưỡng rate limit; response phải vẫn là WebSocket binary hợp lệ, không tạo reconnect/retry loop phía client.
- Kiểm tra lease/discovery của Valkey (luôn bật). Nếu bật Kafka: kiểm tra event publish, dashboard và alert tương ứng.
- Thử restart một Gateway và một Engine theo kịch bản staging; không được có cross-room routing hoặc thác lỗi reconnect.

## 9. Release gate Backend ↔ DevOps

DevOps có thể triển khai hạ tầng; Backend vẫn chịu trách nhiệm về correctness của WebSocket protocol, auth, room ownership và game state. Trước prod, cần có xác nhận chung rằng bản release đã qua integration test WebSocket end-to-end (đặc biệt đường rate-limit), auth production, và test scale/failover theo topology được chọn.

**Chính sách join-token theo môi trường (chốt 2026-09-11):** môi trường **dev được phép** deploy/pass với `AlwaysAcceptJoinTokenVerifier` hiện tại (không cần chờ JWT verifier thật). **Production tuyệt đối bắt buộc** phải có JWT verifier thật (RS256/ES256 + `aud`/`iss` + phân phối public key qua JWKS/GitOps, xem `NOJIRA-uni-p1-tech-design.md` mục G1) trước khi mở traffic — `GATEWAY_ALWAYS_ACCEPT_JOIN_TOKEN_ENABLED`/`GATEWAY_DEV_JOIN_TOKEN_ENABLED` phải là `false` (đã ghi ở mục 2/5). Đây là gate cứng, không phải khuyến nghị.

Nếu các điều kiện trên chưa được xác nhận, có thể deploy môi trường staging nhưng chưa nên mở production traffic cho game realtime.
