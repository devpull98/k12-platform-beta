# Kiến trúc hệ thống — uni-realtime (System Architecture Specification)

Nền tảng game học tập thời gian thực: giáo viên mở phiên, học sinh vào phòng 12 người, chơi game giáo dục (quiz, đua tốc độ, thảo luận nhóm, boss toàn phòng).

**Đây là tài liệu đặc tả kiến trúc duy nhất và toàn diện của hệ thống.** Tài liệu hợp nhất toàn bộ các yêu cầu bài toán, thành phần, giao thức, luồng dữ liệu, tính đúng đắn, vận hành, ranh giới giai đoạn và hồ sơ 9 quyết định kiến trúc then chốt (ADR-001 đến ADR-009).

> [!NOTE]
> **Quy ước GĐ1:** Các khối `[!NOTE] GĐ1` đánh dấu điểm Giai đoạn 1 khác kiến trúc đích mô tả ngay phía trên — **đó mới là thứ đang code**.
> Những chỗ **chưa ai chốt** được in đậm (`Cần Product quyết`, `chưa có cơ sở`, `chưa được định nghĩa`) kèm người quyết và việc bị chặn — tuyệt đối **không tự điền giá trị hợp lý** (xem [§7.5](#75-quyết-định-còn-treo)).
> Code lệch tài liệu → một trong hai Dưngsai, phải sửa. Không để tồn tại song song.

---

## Mục lục
1. [Tổng quan & Ràng buộc Hệ thống](#1-tổng-quan--ràng-buộc-hệ-thống)
2. [Kiến trúc Thành phần & Phân tầng Trách nhiệm](#2-kiến-trúc-thành-phần--phân-tầng-trách-nhiệm)
3. [Giao thức & Envelope Thống Nhất](#3-giao-thức--envelope-thống-nhất)
4. [Luồng Dữ Liệu & Kỹ Thuật Tối Ưu Hot Path](#4-luồng-dữ-liệu--kỹ-thuật-tối-ưu-hot-path)
5. [Tính Đúng Đắn & Rào Chắn Runtime](#5-tính-đúng-đắn--rào-chắn-runtime)
6. [Triển Khai, Vận Hành & Khôi Phục Sự Cố](#6-triển-khai-vận-hành--khôi-phục-sự-cố)
7. [Ranh Giới Giai Đoạn 1 & Lộ Trình GĐ2](#7-ranh-giới-giai-đoạn-1--lộ-trình-gđ2)
8. [Hồ Sơ Quyết Định Kiến Trúc (Architecture Decision Records — ADR)](#8-hồ-sơ-quyết-định-kiến-trúc-architecture-decision-records--adr)
9. [Phân Tích Rủi Ro Thực Chiến & Chiến Lược Phòng Ngừa](#9-phân-tích-rủi-ro-thực-chiến--chiến-lược-phòng-ngừa)
10. [Ví Dụ Thực Chiến, Chuyển Đổi Yêu Cầu PO Sang Đặc Tả Kỹ Thuật (Dev Specs) & Kịch Bản BDD](#10-ví-dụ-thực-chiến-chuyển-đổi-yêu-cầu-po-sang-đặc-tả-kỹ-thuật-dev-specs--kịch-bản-bdd)

---

## 1. Tổng quan & Ràng buộc Hệ thống

### 1.1 Bài toán & Đặc thù EdTech
Giáo viên mở phiên; học sinh vào phòng 12 người chơi game giáo dục thời gian thực. **Điểm phụ thuộc tốc độ trả lời** → thời điểm nhận đáp án là đại lượng có giá trị và gian lận được.

| Đặc thù | Hệ quả kiến trúc |
|---|---|
| **Tải không phẳng** — 09:00 bấm Bắt đầu, 54.000 kết nối trong ~15s | Nút thắt lớn nhất là **connection storm**, không phải steady state (xem [§6.5](#65-connection-storm-đầu-giờ)) |
| **Cả trường ra Internet qua một IP NAT** | Rate limit **không được** khoá theo IP làm tầng chính (xem [§5.6](#56-rate-limiting-phân-tầng)) |
| **Sự cố rơi vào giờ học đang diễn ra** | Ưu tiên **blast radius nhỏ** hơn hiệu suất đóng gói pod (xem [§6.2](#62-topology--54000-ccu)) |
| **Điểm theo tốc độ trả lời** | Thời điểm trả lời **bắt buộc** do server đóng dấu (xem [§5.1](#51-server-authoritative-timestamp)) |
| **Ca điểm/thi đấu chỉ 18h50–21h30/ngày, nhưng hệ thống chạy cả ngày** (đã chốt 2026-09-06, Business) | Rủi ro "chỉ chạy 4–6 tiếng/ngày" ở [ADR-002](#adr-002) **không xảy ra** — hệ thống không tắt ngoài khung giờ điểm, quorum Pekko luôn-bật vẫn hợp lý, **không cần đảo ngược ADR-002** |

### 1.2 Chỉ tiêu thiết kế

| Chỉ tiêu | Giá trị |
|---|---|
| Tải hiện tại / Tải thiết kế (3×) | 10.000 – 18.000 CCU → **54.000 CCU** |
| Phòng đồng thời / Số HS mỗi phòng | 4.500 – 5.000 phòng đồng thời · **12 học sinh/phòng** (room 100 ngoài phạm vi) |
| Thời lượng phiên | < 70 phút |
| Submit → ACK latency | **p99 < 100 ms** |
| Mất dữ liệu đáp án khi sập pod | **0** *(chưa có cơ sở công bố vì phụ thuộc client contract PH-3 [§7.4](#74-phụ-thuộc-ngoài-phạm-vi-blockers))* |

### 1.3 Chế độ chơi
- **Solo**: 12 học sinh độc lập cùng phòng (trường hợp cơ sở).
- **Team**: 4 nhóm × 3 học sinh, gõ nháp chung (sinh `UPDATE_DRAFT` theo nhịp phím, bắt buộc debounce client 150ms).
- **Hybrid / Broadcast**: Thi đua toàn phòng, boss chung (ứng viên duy nhất cần `tick_mode: FIXED`, [ADR-004](#adr-004)).

> [!NOTE]
> **GĐ1:** Chỉ hiện thực **quiz Solo**. Team/Hybrid chưa có Game Definition, `tick_mode: FIXED` chưa hiện thực (nạp definition khai FIXED phải fail ngay lúc nạp).

### 1.4 Năm nguyên tắc thiết kế cốt lõi
1. **Authoritative Server**: Server quyết định toàn bộ logic, điểm số, trạng thái; client thuần túy là tầng hiển thị.
2. **1 Room = 1 Actor đơn luồng**: Xử lý tuần tự, triệt tiêu lock contention và race condition ở tầng nghiệp vụ.
3. **Gateway không chứa business logic**: Gateway chỉ biết `room_id` để định tuyến và fan-out, không biết luật chơi hay trạng thái phòng.
4. **Hot path ngắn nhất**: Không có Message Broker, Valkey hay Database chen ngang giữa client và RoomActor.
5. **Không cam kết con số chưa đo**: Mọi chỉ tiêu capacity gắn liền với phép thử cụ thể.

### 1.5 Hình dạng tổng thể
```text
CLIENT (Flutter · WebView · Web)
   │ WSS + Protobuf ─ HOT PATH          │ REST/HTTPS ─ auth · hồ sơ · ghép trận
   ▼                                    ▼
GATEWAY ×10–12 · STATELESS          DỊCH VỤ NỀN TẢNG (stateless)
   handshake · join token · rate limit      auth/one-time join token · matchmaking
   định tuyến học được · fan-out        profile · leaderboard
   ✗ KHÔNG business logic
   │ INTERNAL FRAME CHANNEL (ADR-001)
   │ TCP dài hạn · length-prefixed Protobuf (1 connection/cặp, multiplex room_id)
   ▼
GAME ENGINE ×12–16 · Pekko Typed (ADR-002)
   RoomActor(room_id) — 1 phòng = 1 actor đơn luồng
     FSM · chấm điểm · state RAM · tick coalescing
   SessionAggregator(session_id) — tầng World, dashboard giáo viên
   │ snapshot+epoch      │ kết quả phiên      │ event log
   ▼                     ▼                    ▼
 VALKEY              POSTGRESQL             KAFKA
```

> [!NOTE]
> **Hạ tầng hiện tại:** Valkey Cluster và Kafka **đã sẵn sàng và được tích hợp ngay từ đầu** ở tầng Async Backplane (Valkey cho Join token `SETNX` lúc handshake và Hot Snapshot < 5 KB; Kafka cho Event Log trận đấu). PostgreSQL lưu trữ kết quả thi đấu sau phiên. Toàn bộ Hot Path tiếp tục duy trì 100% qua TCP nội bộ Netty thuần, tuyệt đối không đi qua Valkey hay Kafka.

### 1.6 Ba thứ cố ý không có
- **Valkey Pub/Sub trên hot path**: Không dùng — mỗi phòng có đúng 1 RoomActor chủ sở hữu, không có shared state để phải đồng bộ qua backplane.
- **ClickHouse**: Analytics lưu PostgreSQL partition theo tháng. Chỉ cân nhắc OLAP khi log > 10 GB/ngày.
- **Kafka trong recovery**: **Cấm tuyệt đối** ([ADR-003](#adr-003)) — Kafka chỉ là event log cho analytics/audit.

---

## 2. Kiến trúc Thành phần & Phân tầng Trách nhiệm

### 2.1 Module Maven và Process runtime
Hệ thống gồm 4 module Maven nhưng chỉ đóng gói thành **2 process**:

| Module | Loại | Triển khai | Trách nhiệm |
|---|---|---|---|
| `modules/uni-protocol` | Thư viện | Nhúng vào **cả hai** process | Chứa schema Protobuf duy nhất của kênh giao tiếp; cấm copy `.proto` sang module khác |
| `modules/uni-observability` | Thư viện | Nhúng vào **cả hai** process | Plumbing metrics (Prometheus/OTLP), log JSON, Alertmanager relay |
| `modules/uni-websocket-gateway` | **Process** | Gateway pod (×10–12) | Đón kết nối WebSocket, xác thực join token, rate limit, định tuyến, fan-out |
| `modules/uni-game-engine` | **Process** | Engine pod (×12–16) | Chạy RoomActor, xử lý FSM, chấm điểm, tick coalescing |

### 2.2 Trách nhiệm từng tầng

| Tầng | State? | Trách nhiệm | Tuyệt đối không làm |
|---|---|---|---|
| **Gateway** | Không *(trừ RouteCache tự lành)* | Handshake, join-token auth (`SETNX` Valkey), rate limit, định tuyến học được, fan-out zero-copy | Chấm điểm, đọc luật chơi, gọi DB, giữ state phòng |
| **Engine** | **Có** (in-memory) | Luật chơi, chấm điểm, FSM, tick coalescing, lưu Hot Snapshot async sang Valkey, phát event sang Kafka | Chạm socket client trực tiếp, blocking I/O trên dispatcher actor |
| **Valkey Cluster** | Có (Đã sẵn sàng) | Hot Snapshot (< 5 KB), fencing epoch, chặn replay join token (`SETNX`), session registry | **Nằm trên đường đi của gói tin hot path (Cấm Valkey Pub/Sub)** |
| **PostgreSQL** | Có | Nguồn sự thật lâu dài: tài khoản, câu hỏi, kết quả phiên (ghi async sau phiên) | Bị gọi đồng bộ trong hot path |
| **Kafka Cluster** | Có (Đã sẵn sàng) | Event log async cho Teacher Dashboard, PostgreSQL writer, analytics và audit trail | **Nằm trên hot path hoặc tham gia recovery** ([ADR-003](#adr-003)) |

### 2.3 Bên trong Gateway
Đường đi gói tin là Netty thuần; Spring Boot chỉ khởi động process và cung cấp `/actuator/*`, **không nằm trên hot path** ([ADR-005](#adr-005)).

Pipeline Netty (thứ tự cố định, không hoán đổi):
```text
HttpServerCodec → HttpObjectAggregator(50KB) → WebSocketServerProtocolHandler
  → JoinTokenAuthHandler   ── xác thực MỘT lần, rồi TỰ GỠ khỏi pipeline
  → RateLimitHandler    ── token bucket theo student_id
  → ProtobufDecoder
  → RoomRouteHandler    ── tra RouteCache → Internal Frame Channel
```

> [!NOTE]
> **Trần `HttpObjectAggregator` — ĐÃ CHỐT (2026-09-06, Business): 50KB**, nâng từ 8KB. Đây là
> trần chung cho MỌI gói WS qua Gateway, tách biệt với ràng buộc cứng riêng của
> `RoomStateSnapshot` (< 5KB, [§2.4](#24-bên-trong-engine--mô-hình-roomactor)) — ràng buộc 5KB đó
> **giữ nguyên không đổi**.

Các cấu trúc RAM quan trọng tại Gateway:
- `ChannelAttributes`: Gắn với Channel sau handshake (`student_id`, `room_id`, `session_id`, `roles`).
- `RoomRegistry`: `Map<room_id, Set<Channel>>` để fan-out. Bắt buộc gỡ Channel khỏi **mọi** set ngay khi `channelInactive` để tránh rò rỉ dữ liệu chéo phòng.
- `RouteCache`: `room_id → engine_pod_id`, học tự động từ response của Engine, **không có TTL** ([§4.5](#45-định-tuyến-tự-học-learned-routing)).
- Token bucket: Theo `student_id` trong RAM từng pod.

### 2.4 Bên trong Engine & Mô hình RoomActor
- `FrameChannelServer` (Netty): Nhận frame nội bộ, giải mã, chuyển vào mailbox của actor tương ứng theo `room_id`.
- `RoomOwnership`: Quản lý pod nào sở hữu phòng. Chỉ class này biết thuật toán phân bổ ([ADR-007](#adr-007)).
- `RoomActor(room_id)`: Đơn vị cô lập nghiệp vụ cốt lõi (1 phòng = 1 actor đơn luồng).
- `SessionAggregator(session_id)`: Tầng World, quản lý dashboard giáo viên tổng thể.

#### 2.4.1 Bốn tầng định danh:
1. **World / Session** (`session_id`, `teacher_id`): Ánh xạ tới `SessionAggregator`.
2. **Room** (`room_id`, `game_id`): Ánh xạ tới `RoomActor` (đơn vị cô lập nghiệp vụ cốt lõi).
3. **Team** (`team_id`): Cấu trúc danh sách trong state của `RoomActor`, không tách thành actor riêng.
4. **Member** (`student_id`, `student_index` từ 0–11): `student_index` (1 byte thay UUID 16 byte) được cấp khi vào phòng và **không tái sử dụng trong cùng phiên**.

#### 2.4.2 FSM của RoomActor:
```text
LOBBY ──TEACHER_START──► PLAYING ──hết step cuối / TEACHER_END──► FINISHED
  ▲                       ▲    │                                    │
  │ pre-spawn khi         │    └─► broadcast theo tick coalescing   └─► ghi kết quả (async)
  │ GIÁO VIÊN TẠO PHIÊN   │                                             → Behaviors.stopped()
  │                  RESYNCING ◄── actor được tái tạo ở pod khác
  └───────────────────────┘        (áp pending, CHƯA broadcast) → gia hạn deadline → PLAYING
```
- **Pre-spawn ở `LOBBY`**: Spawn actor ngay khi giáo viên tạo phiên để giải tỏa connection storm 09:00.
- **`RESYNCING`** (kiến trúc đích): Áp dụng pending submissions từ client trước khi broadcast, chống tụt điểm tạm thời.

> [!NOTE]
> **GĐ1:** FSM chỉ có `LOBBY → PLAYING → FINISHED`. `RESYNCING` chưa cần trong hiện trạng GĐ1
> (`ModuloRoomOwnership` — mất pod là mất phòng cho tới khi đúng pod đó lên lại). **Quyết định
> 2026-09-07:** chuyển sang `LeaseBasedRoomOwnership` (xem [ADR-007](#adr-007), [§9.2](#92-rủi-ro-tầng-runtime--chấm-điểm-jvm--actor)
> Rủi ro 4, `plan.md` Task 14 — **chưa triển khai**) để một phòng phục hồi được trên **pod khác**,
> không chỉ đợi đúng pod cũ.

#### 2.4.3 State của một phòng (RAM & Snapshot):
Toàn bộ state nằm trong RAM actor và phải nén được vào snapshot:
- `epoch`: Fencing token chống split-brain.
- `step_index`, `step_deadline_at`: Mốc thời gian server hết hạn câu hỏi.
- `server_question_started_at`: Mốc server tính điểm tốc độ.
- `students[]`: Điểm, trạng thái kết nối, `student_index`.
- `teams[]`: Nhóm và bản nháp chung.
- **`LastSeenSequenceTable`**: Bảng `student_id → last_seq` (bắt buộc có trong snapshot để chống trùng lặp sau recovery).
- `dirty`, `last_flush_at`: Quản lý tick coalescing.
- **Ràng buộc cứng:** Snapshot serialize + nén LZ4 phải **< 5 KB**.

### 2.5 Game Definition & Guardrails
Luật chơi là dữ liệu upload bởi người vận hành, được bảo vệ bởi 4 guardrails:
1. **Validate DAG lúc nạp**: Chống kịch bản tạo vòng lặp vô hạn.
2. **Không script engine**: Công thức điểm chỉ dùng tập toán tử toán học giới hạn, không chạy code động.
3. **`MAX_TRANSITIONS`**: Giới hạn cứng số chuyển trạng thái tối đa mỗi phiên.
4. **Watchdog**: Cảnh báo khi thời gian xử lý của actor > 10ms (không cố ngắt thread, dựa vào K8s liveness probe để xử lý treo).

> [!NOTE]
> **Công thức điểm Quiz GĐ1 — ĐÃ CHỐT (2026-09-06, Product):** trắc nghiệm 1-trong-4 đáp án,
> nhị phân đúng/sai — đúng = **100 điểm**, sai = **0 điểm**, không có bonus theo tốc độ trả lời.
> Biểu diễn đúng bằng tập toán tử giới hạn ở guardrail #2 phía trên (không cần mở rộng), mở
> khoá `ScoreCalculator` thật (Task 2) và `scoring_formula` thật trong Game Definition
> (Task 11) — cả hai hiện đang dùng giá trị tạm (flat, đánh dấu rõ TEMPORARY) chờ đúng quyết
> định này. Đây từng là câu hỏi Product #2 ở [§7.5](#75-quyết-định-còn-treo).

### 2.6 Concurrency Model ở 3 tầng
| Tầng | Mô hình Concurrency | Lưu ý |
|---|---|---|
| **RoomActor** | **Pekko Dispatcher (Platform thread)** | Actor không sở hữu thread; ~1.125 actor chia sẻ ~8 platform thread. **Cấm gán mỗi actor 1 Virtual Thread** |
| **Netty EventLoop** | **Platform thread cố định (cores × 2)** | Hoàn toàn non-blocking. **Cấm tuyệt đối DB, Valkey, HTTP, JSON parse hoặc blocking I/O** |
| **I/O ngoại vi (DB, Valkey)** | **Virtual thread** | Thích hợp cho blocking I/O; dùng `ReentrantLock`, cấm `synchronized` tránh thread pinning |

---

## 3. Giao thức & Envelope Thống Nhất

### 3.1 Ba kênh giao tiếp
1. **Biên Realtime (Client ↔ Gateway)**: WSS + Protobuf nhị phân. Phục vụ toàn bộ hot path trận đấu.
2. **Internal Frame Channel (Gateway ↔ Engine)**: TCP dài hạn + length-prefixed Protobuf. Định tuyến tới `RoomActor`, backpressure 1 tầng ([ADR-001](#adr-001)).
3. **Kênh Điều Khiển (Client/BFF ↔ Nền tảng)**: REST/HTTPS ngoài hot path (Auth, Profile, Matchmaking).

### 3.2 Vì sao Native WebSocket + Protobuf (không Socket.io)
- **Payload nhị phân**: Nhỏ hơn JSON ~60–70%.
- **Client đa nền tảng**: Chuẩn W3C WebSocket có sẵn cho Flutter, Web, Unity mà không cần runtime Socket.io cồng kềnh.
- **Tối ưu Netty**: Cho phép thao tác trực tiếp `ByteBuf` và fan-out zero-copy.
- **Tự chủ FSM**: Reconnect và heartbeat gắn chặt vào vòng đời của `RoomActor`.

### 3.3 Envelope Thống Nhất (`GameMessage`)
Sử dụng chung một schema Protobuf (`game_message.proto`) cho cả 2 chặng: Client ↔ Gateway và Gateway ↔ Engine. Nhờ đó, Gateway không cần encode/decode lại payload khi fan-out zero-copy.

Cấu trúc `GameMessage`:
- `type`: Loại thông điệp.
- `room_id`: Gateway **ghi đè** từ `ChannelAttributes`, không tin giá trị client gửi.
- `student_id`, `student_index` (0–11).
- `sequence`: Số thứ tự tăng đơn điệu phục vụ chống trùng và replay.
- `client_timestamp_ms`: **Chỉ phục vụ telemetry/giám sát**, cấm dùng để chấm điểm.
- `internal`: Chứa `InternalHeader` (chỉ xuất hiện trên kênh Gateway ↔ Engine, Gateway phải gỡ trước khi gửi client).
- `payload`: `oneof` (Client→Server: `JoinRoom`, `SubmitAnswer`, `Resync`, `TeacherCommand`; Server→Client: `RoomStateSnapshot`, `AnswerAck`, `QuestionStarted`, `StudentJoined`, `GameOver`, `ConnectionDegraded`).

#### 3.3.1 Cấu trúc `InternalHeader`:
- `owner_pod_id`: Pod Engine sở hữu phòng (giúp Gateway học route).
- `epoch`: Fencing token chống split-brain (GĐ1 luôn gửi `0`).
- `trace_id`: UUID sinh từ handshake để liên kết distributed tracing giữa Gateway và Engine.
- `gateway_pod_id`: Pod Gateway gửi gói tin (để Engine phản hồi đúng đích).
- `routing_status`: `OK` | `NOT_OWNER` (cơ chế tự sửa RouteCache).
- `delivery_class`: `CRITICAL` | `BEST_EFFORT`.

Framing kênh nội bộ sử dụng 4-byte big-endian length prefix (`LengthFieldBasedFrameDecoder` / `LengthFieldPrepender` của Netty). Gói tin > 1 MB sẽ bị ngắt kết nối lập tức để chống OOM.

### 3.4 Xác thực: One-Time Join Token
```text
1. Client ──POST /session/{id}/join (REST)──► Dịch vụ Nền tảng
          ◄── join token (TTL 30s, dùng 1 lần) + connect_after_ms (jitter 0–5000ms)
2. Client chờ hết jitter → mở kết nối WSS tới Gateway.
3. Gateway: verify join token 1 lần lúc handshake:
            a. Kiểm tra chữ ký & hạn dùng exp cục bộ (vật liệu key nạp lúc boot).
            b. Chặn replay trên Valkey Cluster: SET join-token:{jti} "1" EX 30 NX
               (trả OK → chấp nhận; trả nil → từ chối ngay).
            c. Ghi ChannelAttributes{student_id, room_id, session_id, roles}
            d. JoinTokenAuthHandler TỰ GỠ khỏi pipeline.
4. Các gói tin sau KHÔNG verify lại; danh tính lấy trực tiếp từ ChannelAttributes.
```
- `connect_after_ms`: Trải đều 54.000 kết nối trong 5 giây, triệt tiêu connection storm.
- **Cơ chế chống Replay đã chốt:** Sử dụng Valkey Cluster `SET join-token:{jti} "1" EX 30 NX` giải quyết triệt để việc cưỡng chế vé 1 lần giữa nhiều Gateway pod mà không làm chậm hot path. Thuật toán ký và phân phối secret được nạp lúc boot.

### 3.5 Phân lớp Critical vs Best-Effort

| Lớp | Thông điệp điển hình | Hành vi truyền tải |
|---|---|---|
| **Critical** | `ANSWER_ACK`, `GAME_OVER`, `TEACHER_COMMAND`, `QUESTION_STARTED`, `CONNECTION_DEGRADED` | **Bypass hoàn toàn tick coalescing**. Khi backpressure: **tuyệt đối không drop**, nếu buffer tràn thì **đóng channel** |
| **Best-effort** | `ROOM_STATE_SNAPSHOT` (delta), `UPDATE_DRAFT`, progress | Gom qua tick coalescing. **Được phép drop** khi `!isWritable()` vì client sẽ nhận delta ở tick kế tiếp |

### 3.6 Tối ưu Payload
- **Delta snapshot**: Mặc định chỉ gửi phần dữ liệu thay đổi so với tick trước. Gửi full snapshot khi mới Join, Resync, hoặc chu kỳ N tick.
- **`student_index`**: Dùng 1 byte (0–11) thay thế cho UUID 16 byte.
- **Nén LZ4**: Áp dụng khi payload > 150 bytes (dưới ngưỡng này chi phí CPU lớn hơn băng thông tiết kiệm). Chưa bật ở GĐ1.
- **Khoảng cách schema đã biết**: `MessageType` có enum `UPDATE_DRAFT` nhưng chưa có message payload tương ứng trong `oneof`.

---

## 4. Luồng Dữ Liệu & Kỹ Thuật Tối Ưu Hot Path

### 4.1 Hồ sơ tải thực tế
Phần lớn CCU đang đọc câu hỏi trên màn hình. Với R = 4.500 phòng, S = 12 HS/phòng, Q = 25s/câu, U = 54.000 CCU:
- Inbound Submit: `54.000 / 25s ≈ 2.160 msg/s`.
- Inbound Heartbeat: `54.000 / 30s ≈ 1.800 msg/s`.
- **Tổng Inbound**: **≈ 4.000 msg/s**.
- **Tổng Outbound (có Coalescing 200ms)**: `2.160 × 12 ≈ 26.000 packet/s` (thay vì 270.000 packet/s nếu chạy Fixed-rate 200ms).

### 4.2 Luồng Vào phòng (Join Room)
1. Client gọi REST `/join`, nhận join token và `connect_after_ms`.
2. Hết jitter, client mở WSS gửi join token trong handshake.
3. Gateway xác thực join token, ghi `ChannelAttributes{student_id, room_id, session_id, roles}` và gỡ auth handler.
4. Gateway gửi `JoinRoom` qua Frame Channel. Chưa biết pod nào → gửi round-robin, Engine tự forward nếu không phải owner.
5. `RoomActor` nhận lệnh: cấp `student_index`, thêm vào state, trả `ROOM_STATE_SNAPSHOT` đầy đủ kèm `owner_pod_id`.
6. Gateway nhận response, lưu route `room_id → owner_pod_id` vào `RouteCache`.
7. `RoomActor` broadcast `STUDENT_JOINED` (Best-effort) cho cả phòng.

### 4.3 Luồng Nộp bài (Submit Answer) — Hot path p99 < 100ms
```text
1. Client: SubmitAnswer{sequence: N} → lưu RingBuffer chờ ACK, hiển thị Optimistic UI.
2. Gateway: RateLimitHandler kiểm tra token bucket (theo student_id).
            → Ghi đè room_id từ ChannelAttributes.
            → Tra RouteCache → gửi thẳng Engine pod sở hữu.
3. RoomActor (đơn luồng, xử lý tuần tự):
     a. server_received_at = clock.millis()  (NGAY khi lấy khỏi mailbox)
     b. sequence ≤ last_seen → GỬI LẠI ACK CŨ (không tính lại điểm), kết thúc.
     c. server_received_at > deadline + 500ms GRACE → từ chối, gửi ACK kèm mã lỗi.
     d. response_time_ms = server_received_at − server_question_started_at.
     e. Cập nhật điểm, đánh dấu dirty = true.
4. Phản hồi tức thì & Bền vững (Two-stage Confirmation):
     - Gửi ANSWER_ACK (Critical) ngay lập tức về client nộp bài → client hiển thị phản hồi UI tức thì ("Đã nhận bài").
     - **Chưa xóa RingBuffer:** Client **giữ nguyên submission trong RingBuffer**, chưa xóa.
     - Delta Snapshot (Best-effort) gom qua Tick Coalescing (§4.6) → Gateway fan-out zero-copy.
5. Xử lý nền bất đồng bộ & Xác nhận Bền vững (Async Backplane & Durable Confirmation):
     - Ghi Hot Snapshot (< 5 KB) lên Valkey Cluster: SET room:snap:{room_id} (Task 14).
     - **Gửi COMMITTED_SEQ (Critical):** Ngay sau khi ghi Valkey thành công, Server gửi tín hiệu `COMMITTED_SEQ` / `ANSWER_COMMITTED` (Critical, Task 15) → **Client mới được phép xóa submission khỏi RingBuffer**.
     - Đẩy GameEvent vào Kafka topic game.events.v1 (partition key = session_id) cho Teacher Dashboard và DB writer (Task 18).
```

### 4.4 Fan-out Zero-Copy qua nhiều Pod
Một phòng 12 người có thể kết nối rải rác tới nhiều Gateway pod, nhưng luôn chỉ có **duy nhất một `RoomActor`**:
```text
   HS 1-5            HS 6-9           HS 10-12
[Gateway Pod A]   [Gateway Pod B]   [Gateway Pod C]
       ▲                 ▲                 ▲
       └─────────────────┼─────────────────┘
                   Internal Frame Channel (TCP)
                         │ Engine gửi DUY NHẤT 1 gói/pod
                  [Engine Pod E2]
                   RoomActor(101)  ← Điểm tuần tự hoá duy nhất
```
Phòng 12 người trên 3 Gateway pod chỉ tốn **3 gói tin nội bộ** thay vì 12 ([ADR-006](#adr-006)). Gateway nhận gói tin và thực hiện fan-out cục bộ bằng zero-copy `retainedDuplicate()`.

### 4.5 Định tuyến tự học (Learned Routing)
Gateway không băm hash hay tra cứu Valkey để tìm vị trí phòng:
1. Gateway duy trì kết nối TCP tới toàn bộ 12–16 Engine pod.
2. Khi chưa biết vị trí phòng: Gateway gửi round-robin. Pod Engine nhận được sẽ tự forward nội bộ tới pod đúng.
3. Response từ Engine luông mang `InternalHeader.owner_pod_id` → Gateway học và ghi nhớ `room_id → engine_pod_id` vào `RouteCache`.
4. Nếu cache sai (do pod Engine chuyển giao hoặc rebalance): Pod nhận trả về `NOT_OWNER` kèm pod owner mới → Gateway cập nhật lại cache.
5. Khi kết nối tới một Engine pod bị đứt: Gateway tự động xóa toàn bộ cache trỏ tới pod đó.

Cơ chế này đạt được 3 tính chất: **Không cần Valkey trên hot path** · **Không cần TTL** · **Không bao giờ lệch sự thật**.

### 4.6 Tick Coalescing
Thay vì phát định kỳ 200ms cố định (sinh ra 270.000 pkt/s vô ích khi cả phòng im lặng), hệ thống áp dụng cơ chế cờ `dirty`:
- **200ms là trần tần suất tối đa, không phải nhịp phát cứng.**
- Khi state thay đổi: Nếu đã qua 200ms từ lần gửi trước → gửi ngay; nếu chưa đủ 200ms → đặt timer bù phần còn lại.
- Khi timer kích hoạt mà `dirty == false` → **không phát gói tin nào**.
- Kết quả: Outbound giảm từ 270k xuống còn ~26k packet/s, phòng im lặng phát đúng 0 gói ([ADR-004](#adr-004)).

### 4.7 Kết nối lại (Reconnect Flow)
**Nguồn khôi phục đúng đắn là client, không phải snapshot:**
1. Client duy trì RingBuffer N=10 submission gần nhất kèm sequence.
2. **Chỉ xoá submission khỏi RingBuffer khi đã nhận tín hiệu bền vững `COMMITTED_SEQ`** (Best-effort,
   §5.4 — cố tình KHÔNG phải Critical: mất một gói tự lành ở lần ghi Hot Snapshot kế tiếp, gói đó
   luôn mang `sequence` mới hơn nên bao trùm cả phần trước đó; đóng kết nối học sinh chỉ vì một tín
   hiệu dọn RingBuffer nội bộ bị nghẽn tạm thời là phản tác dụng — xem `plan.md` Task 15) —
   nhận `ANSWER_ACK` (Critical, tức thời) chỉ là xác nhận lạc quan, chưa được xoá.
3. Khi mất mạng và kết nối lại: Client gửi `RESYNC{last_acked_seq, pending[]}`.
4. `RoomActor` nhận lệnh, kiểm tra `pending[]` qua `LastSeenSequenceTable` để loại bỏ các bản ghi trùng lặp trong $O(1)$.
5. `RoomActor` trả về full snapshot và **gia hạn deadline câu hỏi** bù đúng bằng thời gian gián đoạn.
6. Màn hình học sinh hiển thị overlay "Đang đồng bộ...", **không văng lỗi, không mất đáp án đã chọn**.


### 4.8 Vào phòng muộn (Late Join) vs Kết nối lại
Vào phòng muộn và Reconnect là **hai luồng hoàn toàn khác nhau**:
- **Reconnect**: `student_id` đã có trong state phòng; giữ nguyên `student_index`; gửi `RESYNC` để replay pending.
- **Vào muộn**: `student_id` chưa có; cấp `student_index` mới; nhận snapshot hiện tại kèm deadline còn lại; **tuyệt đối không gửi đáp án các câu đã qua** (tránh gian lận).

Hành vi điểm câu đã qua tuân theo `missed_step_policy` của Game Definition:
- `ZERO` (mặc định): 0 điểm câu đã qua (công bằng cho thi đấu).
- `SKIP`: Bỏ qua câu đã qua khỏi mẫu số xếp hạng.
- `ALLOW_LATE`: Cho làm bù (chỉ dùng cho bài tự học; cấm dùng trong thi đấu vì người vào muộn có thêm thời gian suy nghĩ, đồng thời làm phình snapshot > 5 KB).

---

## 5. Tính Đúng Đắn & Rào Chắn Runtime

### 5.1 Năm Quy Tắc Server-Authoritative Timestamp
1. `server_received_at` phải được đóng dấu bằng `clock.millis()` **ngay khi lấy gói tin khỏi mailbox của actor**, trước mọi logic validate hay tra bảng.
2. `server_question_started_at` do actor ghi nhận chính xác tại thời điểm phát `QUESTION_STARTED`.
3. Nếu `server_received_at > deadline + GRACE(500ms)`: Từ chối bài nộp, gửi `ANSWER_ACK` kèm lý do quá hạn.
4. Cả hai mốc thời gian phải đọc từ **`Clock` được tiêm qua constructor** (không dùng `System.currentTimeMillis()` trực tiếp) để đảm bảo khả năng unit test tất định.
5. **`client_timestamp_ms` tuyệt đối không được xuất hiện trong bất kỳ công thức tính điểm nào.**

### 5.2 Chống Trùng Hai Tầng (Deduplication)
- Tầng 1 (Client): Mỗi lệnh có side-effect mang `sequence` tăng dần đơn điệu.
- Tầng 2 (Server): `LastSeenSequenceTable` lưu `student_id → last_seq` trong RAM của `RoomActor`.
- Nếu `sequence ≤ last_seen`: Bỏ qua cập nhật state và **gửi lại ACK cũ với số điểm gốc** (không tính lại điểm vì điểm có thể đã thay đổi).
- **Bắt buộc:** `LastSeenSequenceTable` phải nằm trong snapshot để sau khi hồi sinh actor không bị chấp nhận trùng lặp các gói tin replay từ client.

### 5.3 Chống Rò Rỉ Dữ Liệu Chéo Phòng
- `room_id` luôn luôn được đọc từ `ChannelAttributes` (đã bind lúc handshake), không bao giờ đọc từ payload do client gửi lên.
- Nếu payload client gửi chứa `room_id` khác với `ChannelAttributes`: **Đóng kết nối ngay lập tức và ghi log an ninh**.
- Vòng lặp fan-out chỉ duyệt trên `Set<Channel>` thuộc phòng đó trong `RoomRegistry`, tuyệt đối không duyệt lọc từ danh sách toàn cục.

### 5.4 Fan-out Zero-Copy & Quản lý Bộ nhớ Netty
```java
// Fan-out trong Gateway
for (Channel ch : roomChannels) {
    if (!ch.isWritable()) {
        if (isBestEffort) { dropCounter.increment(); continue; }
        else { ch.close(); continue; }
    }
    ch.writeAndFlush(new BinaryWebSocketFrame(frame.retainedDuplicate()));
}
// Giải phóng ByteBuf gốc trong khối finally
frame.release();
```
Bắt buộc dùng `retainedDuplicate()` và phải có `release()` trong `finally` để không rò rỉ Direct Memory.

### 5.5 Backpressure Một Tầng
Chuỗi backpressure đồng bộ:
```text
Mailbox RoomActor đầy → Engine ngừng đọc TCP Frame Channel → TCP Window đóng
→ Gateway thấy !isWritable() trên socket nội bộ → autoRead(false) trên WebSocket client
```
- Sử dụng Netty watermark: `WRITE_BUFFER_WATER_MARK = (32 KB low, 64 KB high)`.
- Khi `!isWritable()`:
  - Thông điệp **Best-effort**: Drop an toàn (client sẽ bù ở snapshot kế tiếp).
  - Thông điệp **Critical**: Không được drop; nếu buffer nghẽn kéo dài thì chủ động ngắt kết nối channel.

### 5.6 Rate Limiting Phân Tầng (Thân thiện NAT)
Hệ thống cấm dùng IP làm khoá rate limit chính vì hàng nghìn học sinh cùng trường thường đi qua **1 IP NAT duy nhất**:
- **L1 (Chống DDoS thô)**: Khoá theo IP, ngưỡng **4.000 handshake/phút** (đã chốt 2026-09-06,
  Business — ước lượng theo quy mô phiên/lớp lớn nhất thực tế đang vận hành, ~4.000 học sinh;
  **không phải số đo trực tiếp theo IP**, vì vận hành hiện tại không tách được học sinh nào
  đứng sau IP nào. Coi đây là trần an toàn giả định xấu nhất — 1 trường có thể chiếm trọn quy
  mô phiên lớn nhất. PH-1 (load test) cần xác nhận lại bằng số đo thật).
- **L2 (Chống lạm dụng)**: Khoá theo `student_id`, ngưỡng **10 handshake/phút**.
- **L3 (Bảo vệ dung lượng pod)**: Admission control toàn cục (xem [§6.5](#65-connection-storm-đầu-giờ)).
- **Rate limit thông điệp trong trận (theo `student_id`)**:
  - `SUBMIT_ANSWER`: Tối đa 3 lần/refill 1s.
  - `UPDATE_DRAFT`: Tối đa 10 lần/refill 10s (đi kèm yêu cầu bắt buộc client **debounce 150ms**).
  - `HEARTBEAT`: 2 lần/refill 30s.

### 5.7 Xử lý Sự Cố Engine Pod & Ngăn Ngừa Split-Brain
- Khi kết nối tới một Engine pod bị đứt: Gateway **không mở circuit breaker** (vì phòng chỉ tồn tại ở pod đó, không có fallback). Thay vào đó, Gateway gửi `CONNECTION_DEGRADED` (Critical) tới client và **giữ nguyên kết nối WebSocket**, chờ actor phục hồi.
- **Chống Split-Brain (kiến trúc đích)**:
  1. **Pekko Split Brain Resolver (SBR)**: Cấu hình `keep-majority`, `stable-after = 10s` (không hạ xuống 1s tránh false positive khi GC pause).
  2. **Fencing Token (`epoch`)**: Mọi thao tác ghi snapshot mang epoch tăng dần; epoch cũ bị từ chối tuyệt đối.
  3. Giám sát metric `zombie_actor_stopped_total`: Bất kỳ giá trị nào > 0 đều là dấu hiệu split-brain nghiêm trọng.

### 5.8 Toàn vẹn Snapshot
Mỗi bản ghi snapshot gồm `{ schema_version, epoch, crc32, payload }`:
- Chống đọc snapshot của code cũ sau deployment.
- Chống ghi đè từ zombie actor.
- Chống hỏng dữ liệu mạng bằng checksum CRC32.
- Nếu snapshot lỗi: Coi như state rỗng và phục hồi hoàn toàn dựa trên client replay.

---

## 6. Triển Khai, Vận Hành & Khôi Phục Sự Cố

### 6.1 Phân Tích Tài Nguyên Hệ Thống Cũ (`k12-socketio`) vs Dự Toán Hệ Thống Mới (`uni-realtime`)

#### 6.1.1 Phân Tích Hiện Trạng Thực Tế Hệ Thống Cũ (`Netty-SocketIO + Node.js/Spring`)
Dựa trên số liệu đo đạc thực tế tại ca cao điểm (19:00 – 21:30):
* **Tổng CPU tiêu thụ toàn cụm:** Chỉ khoảng **2.5 – 3.0 cores CPU** cho toàn bộ 7 Pods (gồm 4 pod `k12-socketio` và 3 pod `k12-socketio-worker`).
* **Chi tiết Tầng Socket (`k12-socketio` - 4 Pods):**
  * **K8s Config:** Request `1Gi RAM / 0.5 CPU`, Limit `6Gi RAM / 4 CPUs`.
  * **Thực tế sử dụng TỔNG (4 Pods):** CPU tiêu thụ **0.196 core** (trung bình **~0.049 core/pod**); Memory ngốn **~5.48 GiB** (trung bình **~1.37 GiB RAM/pod**).
  * **Đánh giá:** Đặt `Limit 4 CPUs/pod` (tổng 16 CPUs cho 4 pod) là mức lãng phí Quota K8s nghiêm trọng (>98% CPU requested/limited bị thừa). Bộ nhớ phồng chủ yếu ở tầng Native Memory do phảnh mảnh `glibc malloc` và đệm Socket.IO JSON.
* **Chi tiết Tầng Worker (`k12-socketio-worker` - 3 Pods):**
  * **K8s Config:** Request `1Gi RAM / 0.5 CPU`, Limit `8Gi RAM / 4 CPUs`.
  * **Thực tế sử dụng TỔNG (3 Pods):** CPU tiêu thụ **0.241 core** (trung bình **~0.08 core/pod**); Memory ngốn **~2.44 GiB** (trung bình **~0.81 GiB RAM/pod**).

---

#### 6.1.2 Bảng Đối Soát Kiến Trúc & Hiệu Năng (Hệ Thống Cũ vs Hệ Thống Mới)

| Tiêu chí | Hệ Thống Cũ (`k12-socketio`) | Hệ Thống Mới (`uni-realtime`) | Lợi ích Kiến trúc Mới |
|---|---|---|---|
| **Định dạng dữ liệu** | JSON over Socket.IO | **Binary Protobuf over Netty** | Giảm 80% dung lượng gói, 0 rác JSON Heap |
| **Giao thức nội bộ** | Valkey Pub/Sub Cluster | **Raw Netty TCP Direct** (Lazy-Learned) | **Triệt tiêu 100% nghẽn Valkey Pub/Sub** |
| **Mô hình Fan-out** | Parse & Serialize JSON từng client | **Zero-Copy `retainedDuplicate()`** | Không tốn CPU/RAM khi broadcast 12 HS |
| **Quản lý State** | Valkey Buffer + Thread Pool | **Pekko Typed Actors (`RoomActor`) RAM** | Đơn luồng, 0 Lock, 0 Race Condition |
| **Hiệu quả CPU** | 2.5-3.0 cores toàn cụm (Lãng phí >95%) | **Tối ưu 100% Multi-threading Java 25** | Tiết kiệm >40% CPU Quota trên K8s |

---

#### 6.1.3 Bảng Dự Toán Tài Nguyên K8s Hệ Thống Mới (Phục vụ 54.000 CCU / 4.500 Phòng)

| Tầng / Dịch vụ | Số Pod | Request (CPU / RAM) | Limit (CPU / RAM) | Ghi chú vận hành |
|---|:---:|:---:|:---:|---|
| **Gateway (`uni-websocket-gateway`)** | **4 – 5 Pods** | **1.5 CPU / 3 GiB** | **3.0 CPU / 6 GiB** | Peak Load Target: ~10.000–13.000 WS conns/pod (Tải thường 10k–20k CCU dùng 2–3 pods ~5k–6.5k WS/pod). |
| **Engine (`uni-game-engine`)** | **6 – 8 Pods** | **1.5 CPU / 3 GiB** | **3.0 CPU / 6 GiB** | Gánh ~550–750 phòng/pod (~6.700–9.000 HS) ở tải đỉnh (*chỉ áp dụng sau Task 14*; trước Task 14 giữ 300–375 phòng/pod). **Cấm HPA**. |
| **Valkey Cluster** | 3 shard + replica | Cluster | Cluster | Join token SETNX, Hot Snapshot (<5KB) & Lease (`Task 14`). |
| **PostgreSQL** | Multi-AZ | Primary-Replica | Primary-Replica | Lưu kết quả phiên sau khi FINISHED. |
| **Kafka Cluster** | 3 broker | Cluster | Cluster | Async event log analytics (`game.events.v1`, `Task 18`). |

> [!IMPORTANT]
> **Ràng buộc Mật độ Phòng & Connection Target:**
> 1. **Gateway Connections/pod:** Con số ~10k–13k WS/pod là **Mục tiêu Tải Đỉnh** khi chạy 54k CCU (4–5 pods). Ở tải thường (10k–20k CCU), hệ thống chạy 2–3 pods với mật độ an toàn ~5k–6.5k WS/pod.
> 2. **Mật độ phòng/Engine:** Mức 550–750 phòng/pod (~6.700–9.000 HS/pod) là chỉ tiêu tải đỉnh tối đa, **chỉ áp dụng sau khi hoàn tất Task 14 (`LeaseBasedRoomOwnership` + Hot Snapshot)** và đã xác minh `actor_mailbox_depth` không bị tích tụ. Trước khi Task 14 hoàn thành, mật độ thiết kế an toàn được duy trì ở 300–375 phòng/pod.

---

#### 6.1.4 Quy Trình Co Giãn Hạ Tầng: Tải Thường (10k – 20k CCU) vs Tải Đỉnh 3x (54k CCU)

Bảng chi tiết quy tắc co giãn (Scaling Rules) khi chạy ở mức tải bình thường và khi có spike 3x CCU:

| Thành phần / Tầng | Mức Tải Thường (10k – 20k CCU) | Mức Tải Đỉnh 3x (54.000 CCU) | Chi Tiết Hành Động Co Giãn (Scaling Action) |
|---|:---:|:---:|---|
| **Số phòng game (`room_id`)** | ~850 – 1.700 phòng | ~4.500 phòng | Tăng số phòng 12 người tương ứng theo lượng học sinh |
| **Gateway (`uni-websocket-gateway`)** | **2 – 3 Pods** | **4 – 5 Pods** | **TĂNG +2 Pods** (Scheduled Scaling trước 18:50 hoặc HPA theo CPU >65%) |
| **Engine (`uni-game-engine`)** | **3 – 4 Pods** | **6 – 8 Pods** | **TĂNG +3–4 Pods TRƯỚC 18:50** (Cấm auto-scale tự động; **CẤM scale khi chạy Modulo**) |
| **Tổng Gateway Request** | 3.0–4.5 CPUs / 6–9 GiB | 6.0–7.5 CPUs / 12–15 GiB | Tự động mở rộng Quota K8s cho Gateway |
| **Tổng Engine Request** | 4.5–6.0 CPUs / 9–12 GiB | 9.0–12.0 CPUs / 18–24 GiB | Mở rộng Quota K8s cho Engine trước ca thi đấu |
| **Valkey Connection Pool** | 50 conns/pod | 150 conns/pod | **TĂNG max-connections pool** để xử lý bão join token `SETNX` lúc 19:00 |

> [!CAUTION]
> **Ràng buộc Scale Engine trong Ca Thi:**
> - **Cấm auto-scale tự động (HPA) đối với Engine Pod.** Engine phải được scale thủ công (manual scheduled scale) trước giờ cao điểm.
> - **ĐẶC BIỆT NGHÊM CẤM:** Khi hệ thống vẫn đang sử dụng `ModuloRoomOwnership` (Phase 1), **CẤM TUYỆT ĐỐI** mọi thao tác scale Engine Pod (cả scale-up lẫn scale-down) trong ca thi đấu vì phép chia `room_id % N` sẽ bị xáo trộn làm vỡ room ownership toàn cụm. Việc scale Engine Pod **chỉ được phép thực hiện sau khi Task 14 (`LeaseBasedRoomOwnership`) đã triển khai chính thức và verify thành công**.

* **Quy trình TĂNG TÀI NGUYÊN (Scale-Up) khi có tin báo thi đấu 3x CCU:**
  1. **Bước 1 (18:30 - Trước ca thi 20 phút):** Thực hiện `kubectl scale deployment uni-game-engine --replicas=7` để khởi tạo sẵn 7 Engine Pods (chỉ áp dụng sau Task 14). Các Pods mới sẽ đăng ký danh sách vào `LeaseBasedRoomOwnership` sẵn sàng nhận phòng mới.
  2. **Bước 2 (18:40 - Trước ca thi 10 phút):** Thực hiện `kubectl scale deployment uni-websocket-gateway --replicas=5` để sẵn sàng đón đợt bão kết nối WebSocket (Connection Storm).
  3. **Bước 3 (18:50 - Bắt đầu ca thi):** Khóa chức năng Auto-scaling của Engine (Task 19) để giữ nguyên topology 7 Pods ổn định suốt ca thi 18h50 - 21h30.
* **Quy trình GIẢM TÀI NGUYÊN (Scale-Down) sau ca thi:**
  1. **Sau 21:30 (Khi ca thi kết thúc):** Kiểm tra số lượng kết nối CCU hạ xuống $< 10.000$.
  2. Scale down `uni-websocket-gateway` về **2 Pods** và `uni-game-engine` về **3 Pods** để tiết kiệm tài nguyên Cloud ban đêm.

---

#### 6.1.5 Phân Rã Bộ Nhớ RSS (Resident Set Size Memory Budget - Limit 6.0 GiB/pod)

Bộ nhớ K8s kiểm soát để trigger `OOMKilled` là **RSS Memory** (Heap + DirectMemory + Metaspace + Stacks + Native Memory):

1. **Gateway Pod RSS Budget (Limit 6.0 GiB):**
   * JVM Heap Max (`-Xmx3g`): **3.00 GiB** (Chứa Spring Boot, RouteCache, Channel Registry, Metrics).
   * Netty Direct Memory (`-XX:MaxDirectMemorySize=2g`): **2.00 GiB** (Đệm Socket cho 13k WebSockets binary).
   * Metaspace (`-XX:MaxMetaspaceSize=384m`): **0.38 GiB** (384 MB).
   * Code Cache & Symbols (`-XX:ReservedCodeCacheSize=240m`): **0.25 GiB** (256 MB).
   * Thread Stacks (~100 threads $\times$ `-Xss256k`): **0.025 GiB** (25 MB).
   * C++ Native & jemalloc Arenas: **0.20 GiB** (~200 MB).
   * **$\rightarrow$ TỔNG RSS PEAK:** **5.855 GiB** (Khoảng dự phòng an toàn: ~145 MB).

2. **Engine Pod RSS Budget (Limit 6.0 GiB):**
   * JVM Heap Max (`-Xms2g -Xmx3g`): **3.00 GiB** (Chứa 750 `RoomActor` state, Pekko System, Protobuf).
   * Netty Direct Memory (`-XX:MaxDirectMemorySize=1536m`): **1.50 GiB** (Kênh Netty Internal Frame Server).
   * Metaspace (`-XX:MaxMetaspaceSize=384m`): **0.38 GiB** (384 MB).
   * Code Cache & Symbols: **0.25 GiB** (256 MB).
   * Thread Stacks (~80 threads $\times$ `-Xss256k`): **0.02 GiB** (20 MB).
   * Snapshot C++ Off-Heap & jemalloc: **0.35 GiB** (~350 MB).
   * **$\rightarrow$ TỔNG RSS PEAK:** **5.500 GiB** (Khoảng dự phòng an toàn: ~500 MB).

> [!NOTE]
> **Ghi chú Kiểm chứng RSS Engine:** Con số 750 `RoomActor` trong 3 GiB Heap (~4 MB/phòng gồm state, snapshot buffer & mailbox) là mức ngân sách ước tính dựa trên thiết kế. Chỉ số này cần được kiểm chứng và điều chỉnh thực tế thông qua các kịch bản tải đè PH-1 Load Testing (đo dung lượng `RoomActor` state + snapshot buffer thật).

---

### 6.2 Cấu hình Kubernetes, JVM & Native Allocator (jemalloc)
- **QoS Profile (Burstable QoS Rationale)**: Requests được thiết lập 50% Limits (`cpu: 1.5`, `memory: 3Gi` vs `limits: cpu: 3.0`, `memory: 6Gi`).
  - *Lý do kiến trúc:* Đạt hiệu quả tối ưu cho K8s scheduler quota trong thời gian tải thấp (không lãng phí quota node khi không có ca thi), đồng thời cung cấp khoảng dự phòng 100% (burst headroom) để chịu bão kết nối (Connection Storm) và chống CPU Throttling tuyệt đối trong các ca thi đấu đỉnh điểm.
- `terminationGracePeriodSeconds`: **45s** (để Pekko CoordinatedShutdown di tản state an toàn).
- `topologySpreadConstraints`: `maxSkew: 1` theo hostname, không xếp chồng pod lên cùng worker node.
- `livenessProbe`: `/actuator/health/liveness` cổng riêng :8090 (chu kỳ 10s, failure 3). **Tuyệt đối không kiểm tra dependency ngoài (Valkey/DB) trong liveness probe**.
- `readinessProbe`: `/actuator/health/readiness` cổng :8090 (chu kỳ 5s).
- **JVM Flags Chuẩn hóa**:
  ```bash
  -Xms2048m -Xmx3072m
  -XX:MaxDirectMemorySize=2048m # Gateway (1536m cho Engine)
  -XX:MaxMetaspaceSize=384m
  -XX:+UseG1GC
  -XX:InitiatingHeapOccupancyPercent=35
  -XX:MaxGCPauseMillis=10
  -XX:G1HeapRegionSize=16m
  -Xss256k
  -XX:+UseContainerSupport
  -XX:+HeapDumpOnOutOfMemoryError
  -Duser.timezone=Asia/Ho_Chi_Minh
  ```
- **Native Memory Allocator (`jemalloc`) — Bắt buộc ([ADR-009](#adr-009))**:
  - **Vấn đề triệt tiêu**: Mặc định `glibc ptmalloc` tạo nhiều arena bộ nhớ theo CPU core (`MALLOC_ARENA_MAX = 8 * cores`), gây phân mảnh nghiêm trọng khi Netty liên tục cấp phát/giải phóng Direct ByteBuf. Hệ quả: RSS memory (Resident Set Size) phình to không trả lại cho OS, dẫn đến Pod bị Kubernetes **OOMKilled (`Exit Code 137`)** dù JVM Heap còn rất trống.
  - **Chuẩn cấu hình Dockerfile (Ubuntu/Debian)**:
    ```dockerfile
    # Cài đặt jemalloc
    RUN apt-get update && apt-get install -y --no-install-recommends libjemalloc2 && rm -rf /var/lib/apt/lists/*
    # Ép nạp jemalloc trước glibc malloc
    ENV LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libjemalloc.so.2
    # Cấu hình thu hồi dirty pages thần tốc về Linux kernel
    ENV MALLOC_CONF="background_thread:true,metadata_thp:auto,dirty_decay_ms:1000,muzzy_decay_ms:0,narenas:4"
    ```
  - **Ý nghĩa cấu hình**:
    - `background_thread:true`: Cho phép jemalloc chạy thread riêng dọn dẹp trang nhớ định kỳ, không làm chậm EventLoop của Netty.
    - `narenas:4`: Ép Native Memory Allocator giới hạn 4 arenas, loại bỏ hiện tượng phồng bộ nhớ RSS.
    - `dirty_decay_ms:1000`: Sau 1.000ms (1 giây) không sử dụng, lập tức trả trang nhớ bẩn về cho hệ điều hành.

### 6.3 TLS & Ràng buộc Ingress
TLS được terminate hoàn toàn tại **Load Balancer / Ingress**; traffic đi vào Gateway pod là plaintext ([ADR-008](#adr-008)). Lý do: 54.000 kết nối dồn vào 15s nếu giải mã TLS tại pod sẽ nuốt sạch CPU của Gateway (360 handshake/s/pod).

Hai cấu hình bắt buộc trên Ingress:
1. **Passthrough WebSocket Upgrade**: Hỗ trợ HTTP/1.1 Upgrade header.
2. **Idle Timeout > 30s**: Phải cấu hình timeout lớn hơn chu kỳ heartbeat 30s của client để tránh LB tự ngắt kết nối hợp lệ.

### 6.4 Giải tỏa Connection Storm 09:00
Thời điểm 09:00 bấm Bắt đầu là lúc khó nhất của toàn hệ thống (54.000 kết nối dồn về trong 15 giây):
1. **Pre-spawn actor lúc tạo phiên**: `RoomActor` được khởi tạo ngay ở trạng thái `LOBBY` khi giáo viên tạo phiên, tránh dồn 4.500 lượt spawn vào lúc 09:00.
2. **Staggered Join (Jitter)**: Endpoint `/join` trả về `connect_after_ms` ngẫu nhiên từ 0–5.000ms giúp phân tán đều các kết nối.
3. **Admission Control**: Khi vượt ngưỡng chịu tải của Gateway, trả về ngay mã **`503 Service Unavailable + Retry-After: 3`**, **tuyệt đối không trả 429** (429 gây hiểu nhầm là tài khoản bị phạt).

### 6.5 Timeline Khôi Phục Sự Cố Engine Pod (Kiến trúc đích)
```text
t = 0s      Pod Engine E2 chết (~350 phòng, ~4.000 học sinh).
            Gateway phát hiện TCP đứt → xoá RouteCache E2 → gửi CONNECTION_DEGRADED tới client
            (Gateway GIỮ NGUYÊN kết nối WebSocket với học sinh).
t = 5–10s   Pekko Failure Detector phát hiện node chết.
t = 10–20s  Pekko SBR đưa ra quyết định down node thiểu số (stable-after = 10s).
t ≈ 20s     Shard tái phân bổ sang node khác: Actor mới khởi tạo, tăng epoch, nạp snapshot từ Valkey
            (áp dụng stagger jitter 0–500ms nạp snapshot để chống bão request lên Valkey).
t ≈ 21s     Client gửi gói tin RESYNC kèm pending submissions trong RingBuffer
            → LastSeenSequenceTable lọc bỏ bản trùng. Deadline câu hỏi tự động gia hạn.
t ≈ 22s     Phòng trở lại PLAYING, broadcast state đầy đủ cho học sinh.
            TỔNG THỜI GIAN: 12–25 giây. SỐ LIỆU ĐÁP ÁN MẤT = 0.
```

> [!NOTE]
> **Khôi phục với Valkey Cluster (Sau Task 14):** Nhờ Hot Snapshot (< 5 KB) được lưu liên tục lên Valkey Cluster, khi 1 Engine pod bị crash và pod mới khởi động lại (hoặc failover), state của ~350 phòng được phục hồi từ Valkey. Client kết nối lại gửi `RESYNC(pending[])` → hoàn tất phục hồi với cam kết **Zero Data Loss**.
> 
> *Ghi chú GĐ1:* Con số 10–50ms chỉ có hiệu lực sau khi triển khai và verify Task 14 (Valkey Hot Snapshot & Lease Ownership). Ở GĐ1 hiện tại khi pod crash, phòng sẽ rớt kết nối cho tới khi pod phục hồi.


### 6.6 Chỉ số Giám sát & SLA Nội bộ

| Nhóm | Metric quan trọng nhất | Ý nghĩa & Cảnh báo |
|---|---|---|
| **Actor** | `actor_mailbox_depth` (p99) | Chỉ số cảnh báo sớm tốt nhất — tăng trước khi latency tăng |
| **Gateway** | `fanout_latency` (p99), `channel_not_writable_total` | Giám sát nghẽn fan-out và đệm socket |
| **Recovery** | `resync_duplicate_dropped_total` | Đếm số bản ghi trùng được dedupe thành công khi replay |
| **Fencing** | `zombie_actor_stopped_total` | **Bất kỳ giá trị nào > 0 đều phải điều tra ngay** (báo hiệu split-brain) |
| **JVM** | GC pause time (p99), Direct Memory usage | Phát hiện sớm memory leak ByteBuf |

#### Cam kết SLA Nội bộ:
- Mất dữ liệu đáp án khi sập pod: **0** (xác nhận bằng Chaos Test).
- End-to-end submit → ACK latency: **p99 < 100 ms** (Load Test @54k CCU).
- Khôi phục hoạt động của phòng sau sự cố: **p99 < 30 s**.
- Thời gian xử lý của Actor: p99 < 15 ms · Gateway fan-out latency: p99 < 5 ms.

---

## 7. Ranh Giới Giai Đoạn 1 & Lộ Trình GĐ2

### 7.1 Giai đoạn 1 (GĐ1) có gì
- Module WebSocket Gateway chạy Netty thuần.
- Engine Pekko `RoomActor`, FSM `LOBBY → PLAYING → FINISHED`.
- Kênh kết nối nội bộ `InternalFrameChannel` (TCP + length-prefixed Protobuf).
- **Server-authoritative timestamp** (tiêm `Clock`).
- **`LastSeenSequenceTable`** chống trùng lặp.
- **Tick coalescing** với cờ dirty flag 200ms.
- **Định tuyến tự học** qua `owner_pod_id` (không cần Valkey/TTL trên hot path).
- **Tích hợp Valkey Cluster**: Chặn replay join token (`SET join-token:{jti} 1 EX 30 NX` lúc handshake), lưu Hot Snapshot (< 5 KB) định kỳ + Fencing epoch bất đồng bộ.
- **Tích hợp Kafka Cluster**: Event streaming (`game.events.v1`, partition key `session_id`) đẩy kết quả trận đấu ra hệ sinh thái nền tảng.
- Rate limiting phân tầng, Zero-copy fan-out, Backpressure 1 tầng.
- Nền tảng quan sát đầy đủ (Prometheus, Grafana, OpenTelemetry, Log JSON).

### 7.2 Giai đoạn 1 (GĐ1) chưa có gì

| Chưa có ở GĐ1 | Hệ quả thực tế |
|---|---|
| **Cluster Sharding đa node + SBR** | GĐ1 đang chạy `ModuloRoomOwnership`; **đã chốt chuyển sang `LeaseBasedRoomOwnership`** (2026-09-07, `plan.md` Task 14 — chưa triển khai) để chịu được scale/crash mà không vỡ hash. Vẫn là phân bổ bán tĩnh (lease theo TTL), chưa có tự động rebalance phân tán kiểu Pekko Sharding |
| Trạng thái `RESYNCING` đa pod tự động | Hiện tại nạp snapshot từ Valkey khi pod hồi sinh; resync tự động liên pod nâng cao để GĐ2 |
| `tick_mode: FIXED` | Chỉ hỗ trợ `COALESCE`; nếu Game Definition khai báo FIXED phải **fail lúc upload** |
| Chế độ Team / Hybrid | Chỉ hỗ trợ duy nhất thể thức thi đấu cá nhân **Solo** |

### 7.3 Các điểm Code khác Tài liệu có chủ đích
1. **Phân bổ phòng**: Tài liệu kiến trúc đích mô tả gửi qua ShardRegion; GĐ1 triển khai qua interface `RoomOwnership` ([ADR-007](#adr-007)) — hiện là `ModuloRoomOwnership` (`room_id % N`), **đã chốt chuyển sang `LeaseBasedRoomOwnership`** (lease qua Valkey `SETNX`, xem [§9.2](#92-rủi-ro-tầng-runtime--chấm-điểm-jvm--actor) Rủi ro 4) trước khi cho phép auto-scale Engine.
2. **Định tuyến Gateway**: Gateway áp dụng cơ chế tự học route ngay từ GĐ1 nên khi nâng cấp lên GĐ2 sẽ **không cần sửa bất kỳ dòng code nào ở Gateway**.
3. **Fencing Token**: GĐ1 hiện luôn gửi `epoch = 0` trong `InternalHeader` (giữ tương thích schema cho GĐ2) — **sẽ có giá trị thật lần đầu** khi `LeaseBasedRoomOwnership` triển khai: epoch tăng mỗi lần một pod giành lại quyền sở hữu phòng, dùng để từ chối ghi snapshot từ zombie actor ([§5.8](#58-toàn-vẹn-snapshot)).

### 7.4 Phụ thuộc Ngoài Phạm vi (Blockers)
- **PH-1 · Load-test Harness chưa chạy**: Các con số capacity (`5.000 WS/pod`, `300–375 phòng/pod`) là giả thuyết để đo đạc và bác bỏ; ngưỡng handshake admission control phải được đo tại tầng Ingress.
- **PH-3 · Hợp đồng phía Client chưa hoàn thiện**: Client bắt buộc phải có RingBuffer 10 submission, `sequence` tăng dần, cơ chế gửi `RESYNC`, debounce 150ms khi gõ phím. Nếu client không hoàn thành, cam kết *"mất dữ liệu = 0"* không thể đạt được kể cả khi server hoạt động hoàn hảo 100%.

### 7.5 Quyết định còn treo
Năm câu hỏi cần cấp thẩm quyền quyết định — **3/5 đã chốt (2026-09-06)**, 2 câu còn mở:

| # | Câu hỏi | Người quyết định | Ảnh hưởng | Trạng thái |
|---|---|---|---|---|
| 1 | Mặc định của `missed_step_policy` | **Product** | Quyết định kích thước snapshot < 5 KB ([§4.8](#48-vào-phòng-muộn-late-join-vs-kết-nối-lại)) | 🔴 Còn treo |
| 2 | Công thức tính điểm Quiz GĐ1 | **Product** | Hoàn thiện `ScoreCalculator` (Task 2) | ✅ ĐÃ CHỐT — xem [§2.5](#25-game-definition--guardrails) |
| 3 | Ngân sách hạ tầng hàng tháng | **Business** | Số lượng pod Gateway & Engine tối ưu | 🔴 Còn treo |
| 4 | Hệ thống chạy bao nhiêu giờ mỗi ngày? | **Business** | Nếu chỉ chạy 4–6 tiếng/ngày có thể buộc phải đảo ngược [ADR-002](#adr-002) do chi phí duy trì quorum Pekko cluster luôn-bật | ✅ ĐÃ CHỐT — chạy cả ngày, ADR-002 **giữ nguyên**, xem [§1.1](#11-bài-toán--đặc-thù-edtech) |
| 5 | Quy mô trường lớn nhất sau một NAT IP | **Business** | Xác định ngưỡng chặn L1 ở [§5.6](#56-rate-limiting-phân-tầng) | ✅ ƯỚC LƯỢNG — 4.000, xem [§5.6](#56-rate-limiting-phân-tầng) (chưa phải số đo IP thật, chờ PH-1) |

### 7.6 Lộ trình nâng cấp lên Giai đoạn 2 (GĐ2)
Các bước nâng cấp tiếp theo:
```text
1. Bật Pekko Cluster Sharding + SBR (thay thế `LeaseBasedRoomOwnership` — xem ADR-007).
2. Tích hợp trực tiếp PostgreSQL batch writer từ Kafka consumer (lưu trữ kết quả phiên bền vững).
3. Triển khai Web frontend cho SessionAggregator (Teacher Dashboard) đọc từ Kafka event stream.
4. Mở rộng Game Definition cho chế độ Team và Hybrid (tick_mode: FIXED).
```
*Lưu ý: Toàn bộ quá trình nâng cấp từ bước 1 đến bước 3 chỉ diễn ra ở Engine pod, hoàn toàn không làm thay đổi Gateway.*

> [!NOTE]
> **Cập nhật 2026-09-08:** Bước 1 ở trên **không còn là điều kiện bắt buộc để giải quyết rủi ro
> "N thay đổi làm vỡ hash"** — `LeaseBasedRoomOwnership` (GĐ1, Task 14) đã đóng đúng rủi ro đó,
> verify bằng chaos test thật (xem [§9.2 Rủi ro 4](#92-rủi-ro-tầng-runtime--chấm-điểm-jvm--actor)).
> Cluster Sharding vẫn giữ vai trò trong lộ trình vì hai lý do khác, không phải lý do gốc: (a) là
> phương án dài hạn cho **thành viên cluster động** thật sự (pod tham gia/rời không cần biết trước
> danh sách tĩnh) — điều `LeaseBasedRoomOwnership` chưa làm được ở phía Gateway (xem phát hiện mới
> ở §9.2 Rủi ro 4: `uni.gateway.engine.pods` là danh sách tĩnh, đọc một lần lúc boot); (b) các lợi
> ích khác của Pekko Cluster (rebalancing có kiểm soát, SBR built-in) mà tự ghép Valkey lease +
> cơ chế discovery riêng cho Gateway sẽ phải tự xây lại một phần. Nói cách khác: bước 1 vẫn là
> *phương án dài hạn/dự phòng* khi hệ thống thật sự cần scale-up/down linh hoạt trong giờ thi đấu
> mà không chấp nhận khởi động lại Gateway — không phải điều kiện tiên quyết cho GĐ2 nói chung.
> Cách đóng gap trước mắt (nhẹ hơn nhiều so với đưa cả Pekko Cluster vào) là `plan.md` Task 21 —
> Gateway tự phát hiện/hot-reload danh sách Engine pod.

---

## 8. Hồ Sơ Quyết Định Kiến Trúc (Architecture Decision Records — ADR)

Chín quyết định kiến trúc then chốt, mỗi mục ghi rõ **quyết định** và **cái giá phải trả (trade-offs)**. Mục nào không nêu cái giá là mục chưa được cân nhắc.

| ADR | Quyết định | Trạng thái |
|---|---|---|
| [ADR-001](#adr-001) | Kênh GW ↔ Engine: TCP + length-prefixed Protobuf, **không gRPC** | Đã chấp nhận |
| [ADR-002](#adr-002) | Pekko Typed + Cluster Sharding, **bắt buộc kèm SBR + fencing token** | **Có thể bị lật** |
| [ADR-003](#adr-003) | Recovery = "mất dữ liệu 0", không phải "khôi phục < 50ms" | Đã chấp nhận |
| [ADR-004](#adr-004) | Tick coalescing (dirty-flag) là mặc định | Đã chấp nhận |
| [ADR-005](#adr-005) | Netty thuần trên hot path; Spring Boot chỉ bootstrap + actuator | Đã chấp nhận |
| [ADR-006](#adr-006) | Engine gửi một gói mỗi GW pod; Gateway nhân bản cục bộ | Đã chấp nhận |
| [ADR-007](#adr-007) | Quyền sở hữu phòng cô lập sau đúng một interface | Đã chấp nhận |
| [ADR-008](#adr-008) | TLS terminate ở LB/ingress, không ở pod | Đã chấp nhận · GĐ1 |
| [ADR-009](#adr-009) | jemalloc làm native allocator; ép LD_PRELOAD, không dùng glibc malloc | Đã chấp nhận |

- **Đã chấp nhận**: Đang có hiệu lực, code phải tuân thủ nghiêm ngặt.
- **Có thể bị lật**: Đang có hiệu lực nhưng có câu hỏi chưa trả lời đủ sức đảo ngược — không xây thêm thứ khó tháo gỡ lên trên.

---

### ADR-001

**Kênh Gateway ↔ Engine dùng TCP dài hạn + length-prefixed Protobuf.** Dùng
`LengthFieldBasedFrameDecoder` / `LengthFieldPrepender` của Netty, **không tự viết parser**. Một
connection mỗi cặp (GW pod, Engine pod), **multiplex bằng `room_id`**. Gói vượt `maxFrameLength`
(1 MB) → đóng connection + log, không OOM.

**Không gRPC:** Bốn thứ gRPC mang lại đều đã có hoặc không cần (codegen từ envelope dùng chung ·
deadline vô nghĩa với stream sống suốt phiên · load balancing do Cluster Sharding lo ·
interceptor). Thứ còn lại là **flow-control chồng hai tầng** — lúc nghẽn không xác định được tầng
nào đang chặn.

**Cái giá:** Tự viết framing và tự quản vòng đời connection (phát hiện đứt, dial lại, dọn state).

**Hệ quả:** Backpressure một tầng trở nên khả thi — **thêm bất kỳ queue tầng app nào giữa mailbox
và socket sẽ xoá lợi ích này** ([§5.5](#55-backpressure-một-tầng)). Gói từ Engine ra client không cần
encode lại ở Gateway, điều kiện cần của fan-out zero-copy.

---

### ADR-002

**Pekko Typed + Cluster Sharding.** Tự dựng bằng thread pool là tự viết lại mailbox, phân bổ
actor, rebalancing, FSM — và tự chuốc leak/deadlock ở phần khó nhất.

**Ràng buộc không thể tách rời:** Không bật Cluster Sharding nếu thiếu SBR và fencing token.
Sharding trần là đường thẳng tới hai actor cùng `room_id` chạy song song — chấm điểm hai lần, hai
nguồn broadcast mâu thuẫn, hai luồng ghi snapshot đè nhau. SBR chỉ đảm bảo *cuối cùng* còn một
node; cửa sổ `stable-after` 10s vẫn có hai actor cùng sống, và fencing token (`epoch`) bịt cửa sổ
đó.

| `pekko.cluster` | Giá trị |
|---|---|
| `downing-provider-class` | `SplitBrainResolverProvider` |
| `split-brain-resolver.active-strategy` | `keep-majority` |
| `split-brain-resolver.stable-after` | `10s` — **KHÔNG hạ xuống 1s** |
| `split-brain-resolver.down-all-when-unstable` | `on` |
| `failure-detector.acceptable-heartbeat-pause` | `5s` — nhạy hơn = false positive khi GC pause |
| `failure-detector.threshold` | `10.0` |
| `number-of-shards` | `1000` (~3–4× số node tối đa). **Không đổi được sau khi cluster đã chạy** |

**Cái giá:** Cluster luôn-bật, **không scale xuống 0 được** — phải giữ quorum. Kéo theo: **không
đặt HPA theo CPU cho Engine pod** (scale dưới quorum kích hoạt `keep-majority` và down chính
cluster đang khoẻ); chi phí nhàn rỗi thành khoản có thật, ở EdTech có thể **chi phối**.

**Rủi ro từng mở, đã chốt (2026-09-06, Business, [§7.5](#75-quyết-định-còn-treo) câu 4):** hệ
thống chạy **cả ngày** (ca điểm/thi đấu chỉ 18h50–21h30, nhưng hạ tầng không tắt ngoài khung
giờ đó) — rủi ro "chỉ chạy 4–6 tiếng/ngày khiến hoá đơn phần lớn là tiền trả cho lúc không ai
dùng" **không xảy ra**. ADR này giữ nguyên, không cần đảo ngược. Vẫn giữ nguyên tắc: **đừng xây
thêm thứ khó tháo lên trên giả định cluster luôn-bật** — chỉ là lý do đảo ngược không còn nữa.

**Hệ quả:** `terminationGracePeriodSeconds: 45` bắt buộc (thiếu graceful leave thì mỗi rolling
update là một lần nghi ngờ split-brain) · `requests = limits` · theo dõi
`zombie_actor_stopped_total`, bất kỳ giá trị > 0 đều đáng điều tra.

> [!NOTE]
> **GĐ1 (cập nhật 2026-09-08):** Cluster Sharding **chưa bật, và không còn là điều kiện bắt buộc
> để đóng rủi ro pod crash** — `LeaseBasedRoomOwnership` + Hot Snapshot thật (`plan.md` Task 14) đã
> có code, đã verify bằng chaos test thật (`DockerComposeChaosIT`, kill container Engine đang giữ
> phòng, đợi hết lease TTL, xác nhận pod sống sót giành lại + phục hồi đúng roster từ Valkey).
> Quyền sở hữu phòng khi cờ `uni.engine.room-store.enabled=true` không còn suy ra từ `room_id % N`
> nữa mà là ai giữ được lease Valkey — nên đổi `N` (thêm/bớt pod) không còn làm vỡ hash toàn cụm
> như hành vi mặc định `ModuloRoomOwnership` vẫn có. Cờ này **vẫn `false` trong `application.yml`
> mặc định** — chưa ai bật thật ngoài `docker-compose.dev.yml` test cục bộ.
>
> **Phát hiện mới (2026-09-08), tách biệt với Task 14:** dù Engine đã hết phụ thuộc `N`, **Gateway
> vẫn không route được tới một Engine pod hoàn toàn mới** — `uni.gateway.engine.pods` là danh sách
> tĩnh, `GatewayNetworkLifecycle` chỉ đọc và dial (`FrameChannelClient.connect`) đúng **một lần**
> lúc Gateway khởi động, không có cơ chế nào thêm pod vào `knownPods` sau đó. Vì vậy "scale-up giữa
> phiên" theo đúng nghĩa vận hành (SRE thêm pod Engine mới mà không khởi động lại Gateway) **vẫn
> chưa khả thi**, bất kể `RoomOwnership` phía Engine dùng thuật toán gì — xem `plan.md` Task 21
> (mới, mở ra từ phát hiện này) và test đỏ có chủ đích `DockerComposeScaleUpIT`
> (`@Disabled`, tài liệu hoá gap bằng test thật thay vì chỉ ghi chú). Cluster Sharding vẫn là
> phương án dài hạn cho đúng bài toán này (thành viên cluster động, cả hai chiều Gateway lẫn
> Engine) — không phải cho rủi ro pod-crash đã đóng ở trên.

---

### ADR-003

**Mục tiêu recovery là "mất dữ liệu = 0", không phải "khôi phục dưới 50ms".**

> Học sinh không mất dữ liệu và không mất phiên khi hệ thống gián đoạn 15–25 giây.

Ngưỡng mili-giây sai vì nó chỉ đo một khâu. `T_total ≈ 12–25s` gồm: `T_detect` 5–10s ·
`T_down` 5–10s · `T_rebalance` 0,5–2s · **`T_load` 10–50ms** · `T_reconnect` 1–2s. `T_load` —
phần duy nhất mà "50ms" từng đo — là phần rẻ nhất và không chi phối. `T_detect` không ép xuống
được: hạ xuống 1s nghĩa là một GC pause sẽ down node đang khoẻ.

Cơ chế: **client-side replay** ([§4.7](#47-kết-nối-lại-reconnect-flow)) — ring buffer submission chưa ACK,
`RESYNC` sau khi nối lại, server loại bản trùng bằng `LastSeenSequenceTable`. Hệ quả trực tiếp:
**snapshot là tối ưu tốc độ, không phải nguồn đúng đắn** — nguồn đúng đắn là client. Và **Kafka
không được tham gia recovery**.

**Cái giá:** Phải thiết kế cho một khoảng gián đoạn nhìn thấy được, không giấu bằng con số SLA
đẹp. Ba thứ phải làm thật: overlay "Đang đồng bộ…" (không văng lỗi, không xoá đáp án đã chọn) ·
**gia hạn deadline** đúng bằng khoảng gián đoạn · trạng thái `RESYNCING` ở actor.

**Rủi ro đang mở:** Hợp đồng client **chưa tồn tại và chưa ai được giao** (PH-3,
[§7.4](#74-phụ-thuộc-ngoài-phạm-vi-blockers)) → mục tiêu của ADR này hiện **không có cơ sở** dù server làm
đúng 100%. Không công bố SLA trước khi client ship phần đó.

> [!CAUTION]
> **Cập nhật 2026-09-07 (Task 15 / phát hiện B2):** review kiến trúc phát hiện thêm một khoảng
> trống ở phía **server**, độc lập với PH-3: `ANSWER_ACK` gửi tức thời (hot path), còn Hot
> Snapshot ghi Valkey là async và luôn xảy ra **sau** — nếu client xoá RingBuffer ngay khi nhận
> `ANSWER_ACK` (như §4.7 mô tả), một pod crash đúng lúc giữa hai mốc đó làm mất câu trả lời đã
> ACK nhưng chưa persist, **kể cả khi PH-3 hoàn thành 100%**. Đã thêm message `CommittedSeq`
> (Task 15, gửi sau khi Valkey xác nhận ghi — xem `plan.md`) làm tín hiệu discard thật, tách khỏi
> `ANSWER_ACK`. **"Mất dữ liệu = 0" chỉ đúng khi CẢ HAI điều kiện đạt: PH-3 xong VÀ client dùng
> `committed_seq` (không phải việc nhận `ANSWER_ACK`) làm điều kiện xoá RingBuffer** — nếu PH-3
> triển khai theo đúng nguyên văn §4.7 hiện tại (xoá khi nhận ACK) mà không cập nhật theo
> `CommittedSeq`, lỗ hổng này vẫn còn nguyên dù client đã ship xong.

---

### ADR-004

**Tick coalescing (dirty-flag) là mặc định. 200ms là trần tần suất, không phải nhịp phát — phòng
không có gì thay đổi phát 0 gói.**

Actor giữ cờ `dirty`: Có thay đổi → đủ 200ms kể từ lần flush trước thì bắn ngay, ngược lại hẹn
timer đúng phần còn thiếu; timer nổ mà `dirty == false` thì không gửi gì.

Quiz không có gì để nội suy giữa hai lần nộp bài, nên fixed-rate chỉ gửi lại state không đổi:
270.000 packet/s (fixed-rate 200ms) so với **≈ 26.000 packet/s** (coalescing) ở 54k CCU — nặng
hơn ~10 lần. Fixed-rate **chỉ** cho game chuyển động liên tục, khai trong Game Definition
(`tick_mode: FIXED`), là thuộc tính của từng game chứ không phải cấu hình toàn cục.

**Cái giá:** Logic flush có trạng thái (`dirty`, `flushScheduled`, `lastFlushAt`) và nhiều đường
đi hơn để sai. Kèm rủi ro chưa đo: mỗi phòng bẩn tự hẹn một timer → ~1.000 timer đồng thời/pod
trên hashed-wheel scheduler của Pekko — cần spike trước khi hiện thực; không đạt thì đổi sang
flush wheel gom lô ở tầng pod.

**Hệ quả:** Phân loại Critical vs Best-effort trở nên bắt buộc ([§3.5](#35-phân-lớp-critical-vs-best-effort)) — phòng
im lặng phát 0 gói nên thứ duy nhất báo học sinh biết bài đã nhận là `ANSWER_ACK` · broadcast là
**delta** · `Clock` phải tiêm vào actor.

> [!NOTE]
> **GĐ1:** Chỉ hiện thực `COALESCE`. `tick_mode: FIXED` vẫn trong schema nhưng **chưa có
> implementation** — nạp definition khai `FIXED` phải **fail ngay lúc nạp**, không im lặng rơi về
> `COALESCE`.

---

### ADR-005

**Đường đi gói tin là Netty thuần, pipeline tự dựng.** Spring Boot chỉ khởi động process, phục vụ
`/actuator/*`, cung cấp bean dùng chung ở `uni-observability`. Không `@RestController` nào nằm
trên đường đi của gói tin game.

Ba thứ hot path bắt buộc làm được, và không cái nào lộ ra ở tầng trừu tượng của Spring: Thao tác
trực tiếp `ByteBuf` để fan-out zero-copy · đọc `channel.isWritable()` và bật `autoRead(false)` ·
kiểm soát thứ tự handler và **gỡ handler xác thực khỏi pipeline** sau handshake.

**Cái giá:** Mất phần lớn tiện ích Spring trên biên (không auto-config WebSocket, không
`@MessageMapping`, không test slice) — xác thực, rate limit, định tuyến, fan-out đều tự viết và tự
test. Và người mới đọc pipeline Netty tốn công hơn đọc một controller.

**Hệ quả:** **Virtual threads không được bật** ở cả hai service · **không DB/Valkey/HTTP call nào
trong Netty EventLoop** (một lời gọi blocking chặn mọi connection mà loop đó phục vụ) · số event
loop cố định = cores × 2.

---

### ADR-006

**Engine gửi một gói cho mỗi Gateway pod có client của phòng; Gateway nhân bản zero-copy ở tầng
cuối.** Phòng 12 người trải 3 GW pod tốn **3 gói** qua kênh nội bộ thay vì 12. Gateway giữ
`RoomRegistry: Map<room_id, Set<Channel>>` trong RAM, fan-out lặp trên set của **đúng phòng đó**,
không lọc động từ danh sách toàn cục.

**Cái giá:** Gateway phải giữ một cấu trúc trạng thái — không còn stateless tuyệt đối. Chấp nhận
được vì nó tự lành: Dựng từ chính các connection pod đó đang giữ, `channelInactive` xoá entry
ngay, không có gì để đồng bộ với pod khác.

**Hệ quả:** Zero-copy là bắt buộc, không phải tối ưu · điều kiện cần là hai chặng dùng chung
envelope ([ADR-001](#adr-001)) · **dùng `retainedDuplicate()`, không `retain()`** ·
`frame.release()` phải nằm trong `finally` · `channelInactive` phải gỡ Channel khỏi **mọi** set
ngay, Channel chết còn trong set của phòng khác là rò dữ liệu chéo phòng.

---

### ADR-007

**Quyền sở hữu phòng nằm sau một interface duy nhất `RoomOwnership`.** **Không class nào ngoài
`RoomOwnership` được biết tới thuật toán phân bổ cụ thể** — dù đó là `% N` hay lease Valkey.

GĐ1 khởi đầu với đúng một implementation, `ModuloRoomOwnership` (`room_id % N`), tĩnh và đơn giản
nhất có thể. **Quyết định 2026-09-07:** thay bằng `LeaseBasedRoomOwnership` làm implementation
chính của GĐ1 (`plan.md` Task 14, **chưa triển khai**) — một pod **giành** quyền sở hữu phòng qua
Valkey (`SET room:owner:{room_id} "{pod_id}:{epoch}" EX <ttl> NX`, TTL ngắn + renew định kỳ +
fencing bằng `epoch`) thay vì được **gán** cố định bằng phép chia dư. `ModuloRoomOwnership`
không bị xoá — giữ làm fallback khi Valkey không khả dụng lúc pod cần giành lease lần đầu.

**Vì sao không chọn Consistent Hashing thay cho Valkey Lease:** giảm được tỷ lệ vỡ phòng khi scale
từ "toàn bộ" xuống "~1/N" (đúng tính chất của Ketama/virtual node), nhưng (a) không giải quyết
phục hồi khi crash — vẫn cần y hệt Valkey Hot Snapshot, nên không tiết kiệm được phụ thuộc Valkey
nào; (b) muốn ring cập nhật động (thêm/bớt pod) mà không redeploy toàn bộ thì **mọi** pod phải
đồng bộ đúng một view membership tại mọi thời điểm — tức tự xây lại một phần bài toán mà Pekko
Cluster Sharding (GĐ2) đã giải sẵn, có SBR, có kiểm chứng thực tế. Không đáng tự làm tay ở GĐ1.

Hai ràng buộc đi kèm (không đổi bởi quyết định trên): Mọi response từ Engine **đóng dấu
`InternalHeader.owner_pod_id`** · nhận gói của phòng không thuộc pod này → forward, hoặc trả
`NOT_OWNER` kèm owner hiện tại. Ràng buộc thứ nhất là thứ khiến Gateway **học** vị trí phòng thay
vì tự tính ([§4.5](#45-định-tuyến-tự-học-learned-routing)) — **cơ chế học của Gateway không cần
sửa gì** khi Engine đổi từ modulo sang lease, đúng giá trị mà interface này được thiết kế để bảo vệ.

**Cái giá:** Một tầng gián tiếp, cộng thêm từ 2026-09-07 là một phụ thuộc Valkey thật cho quyết
định sở hữu (trước đó chỉ là phép tính thuần RAM) — đổi lấy khả năng chịu được crash/scale mà
không vỡ hash.

**Hệ quả:** GĐ2 thay một class nữa (`LeaseBasedRoomOwnership` → `ShardRegionRoomOwnership`),
không đụng Gateway, `RoomActor`, hay codec · **Gateway không cần sửa gì khi lên GĐ2** ·
`InternalHeader.epoch` từ chỗ luôn gửi `0` ở GĐ1 giai đoạn đầu, sẽ có giá trị thật lần đầu khi
`LeaseBasedRoomOwnership` triển khai — trường đã có mặt trên wire từ Task 1 nên không cần đổi schema.

---

### ADR-008

**TLS terminate ở LB/ingress; Gateway pod nhận WebSocket plaintext.** Pipeline Netty **không có
`SslHandler`**, và GĐ1 **không thêm cờ config để bật TLS tại pod** — một cờ chưa dùng là một nhánh
chưa test.

Lý do: Connection storm đưa 54.000 kết nối vào trong ~15 giây, TLS handshake ~1–3ms CPU mỗi lượt
→ 3.600 handshake/s chia 10 Gateway pod = **360 handshake/s/pod trên 2 vCPU**, riêng TLS đã có thể
chiếm hết CPU của pod.

Hai ràng buộc bắt buộc lên ingress, phải nằm trong manifest chứ không phải trí nhớ: **Passthrough
WebSocket upgrade** · **idle timeout > chu kỳ heartbeat (30s)**, thiếu thì LB cắt connection đang
khoẻ và sinh reconnect storm.

**Cái giá:** Chi phí TLS không biến mất, nó chuyển sang lớp ingress — nút thắt chỉ đổi chỗ. Traffic
giữa ingress và pod là plaintext: Chấp nhận được khi cùng mạng tin cậy, **không** chấp nhận được
nếu về sau đi qua mạng chung, lúc đó ADR này phải xem lại.

**Hệ quả:** Ngưỡng `max_handshake_per_sec` phải đo **ở ingress** (vẫn bị PH-1 chặn).
---

### ADR-009

**Sử dụng jemalloc làm bộ cấp phát bộ nhớ Native (Off-Heap), thay thế hoàn toàn glibc ptmalloc mặc định.**

Ép nạp thư viện thông qua biến môi trường container:
`LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libjemalloc.so.2` và cấu hình giải phóng trang nhớ:
`MALLOC_CONF="background_thread:true,dirty_decay_ms:1000,muzzy_decay_ms:0"`.

**Lý do:** Khi Gateway phục vụ hàng chục nghìn kết nối WebSocket và liên tục cấp phát/giải phóng Netty `ByteBuf` ngoài Heap, cơ chế arena theo CPU core của `glibc` (`MALLOC_ARENA_MAX`) gây phân mảnh bộ nhớ dữ dội. glibc không chịu trả lại các trang nhớ không dùng cho Linux kernel, làm cho chỉ số RSS (Resident Set Size) của Pod tăng tịnh tiến theo thời gian (Memory Bloat / RSS Creep) dù JVM Heap và Netty Pooled Direct Memory không hề bị leak. Hậu quả là Kubernetes Node tự động bắn hạ Pod với mã lỗi `OOMKilled (Exit Code 137)`.

`jemalloc` được chọn thay vì `tcmalloc` vì:
1. Đã là tiêu chuẩn thực tế trong toàn bộ hệ sinh thái dữ liệu & networking lớn của Java (Kafka, Cassandra, Netty, Trino, Elasticsearch) và Valkey.
2. Hỗ trợ cơ chế `dirty_decay` với `background_thread:true` chạy ngầm, chủ động purge và trả trang nhớ bẩn về OS sau 1.000ms mà không làm block Netty EventLoop.

**Cái giá:**
- Cần cài đặt thêm package `libjemalloc2` (~200 KB) vào Docker image nền.
- Thêm một thread nền rất nhẹ do OS quản lý cho mỗi tiến trình.

**Hệ quả:**
- Biểu đồ bộ nhớ RSS của Pod Gateway và Engine trên Kubernetes được làm phẳng (flat-line), triệt tiêu hoàn toàn lỗi K8s OOMKilled do phân mảnh glibc.
- Toàn bộ service chạy trên container Linux bắt buộc phải có cờ `LD_PRELOAD` và `MALLOC_CONF` trong Dockerfile hoặc Kubernetes Pod manifest ([§6.2](#62-cấu-hình-kubernetes-jvm--native-allocator-jemalloc)).

---

## 9. Phân Tích Rủi Ro Thực Chiến & Chiến Lược Phòng Ngừa

Hệ thống phục vụ 54.000 CCU đồng thời với yêu cầu độ trễ p99 < 100ms trên môi trường Production sẽ gặp phải các rủi ro vận hành phức tạp mà môi trường test nhỏ không bộc lộ. Dưới đây là 5 nhóm rủi ro tiềm ẩn lớn nhất, nguyên nhân gốc rễ và chiến lược kỹ thuật phòng
ngừa — mỗi rủi ro ghi rõ phần **bắt buộc ngay ở GĐ1** (không đổi kiến trúc đã chốt) tách biệt
khỏi phần **đề xuất cho GĐ2** (cần ADR riêng, chưa được phép code trước):

### 9.1 Rủi ro Tầng Mạng & Quản lý Bộ nhớ Netty

#### ⚠️ Rủi ro 1: Head-of-Line Blocking trên kết nối TCP nội bộ multiplex
- **Hiện trạng:** Để tối ưu chi phí kết nối, hệ thống duy trì **duy nhất 1 kết nối TCP dài hạn** giữa mỗi cặp (Gateway pod, Engine pod) để chuyển tiếp gói tin của nhiều phòng khác nhau ([ADR-001](#adr-001)).
- **Nguy cơ:** TCP là giao thức truyền byte stream tuần tự có thứ tự (in-order). Khi phòng 101 phát ra một gói `ROOM_STATE_SNAPSHOT` đầy đủ (~4–5 KB), nếu mạng giữa 2 pod bị packet drop hoặc gói tin lớn này đang được truyền:
  - Toàn bộ các gói tin nhỏ khác (như `ANSWER_ACK` chỉ ~50 bytes của phòng 102, 103) đang xếp hàng sau trên cùng kết nối TCP đó **sẽ bị chặn lại (Head-of-Line Blocking)**.
  - **Hệ quả:** Latency p99 của hàng chục phòng khác bị nhảy vọt từ 15ms lên 80–150ms mà không rõ nguyên nhân.
- **Chiến lược phòng ngừa GĐ1 (bắt buộc ngay, không đổi kiến trúc):** Snapshot đã bị ràng buộc
  cứng < 5 KB ([§2.4](#24-bên-trong-engine--mô-hình-roomactor)), nên ở tải hiện tại HoL chưa
  phải sự cố quan sát được, chỉ là rủi ro cần đo. Bật metric độ trễ theo từng cặp pod
  (`internal_frame_p99_latency{gw_pod, engine_pod}`) và alert khi lệch bất thường giữa các
  phòng dùng chung một connection — đó là dấu hiệu sớm của HoL.
- **Đề xuất cho GĐ2 (chưa quyết — không code trước khi có ADR riêng):** Connection pooling
  (2–4 kết nối/cặp, băm `room_id % pool_size`) hoặc chunk hoá payload > 1.5 KB. **Cả hai đều
  mâu thuẫn trực tiếp với [ADR-001](#adr-001) hiện tại** ("một connection mỗi cặp, không tự viết
  parser") và chunk hoá còn đòi thêm trường `chunk_index`/`total_chunks` chưa có trong
  `GameMessage`/`InternalHeader` ([§3.3](#33-envelope-thống-nhất-gamemessage)). Không hiện thực ở Task 4
  cho tới khi ADR-001 được sửa đổi chính thức và schema đi qua PR riêng.

#### ⚠️ Rủi ro 2: Rò rỉ bộ nhớ Direct Memory (Off-Heap Leak) & Phân mảnh RSS (Memory Bloat)
- **Hiện trạng:** Gateway áp dụng cơ chế Zero-Copy: `BinaryWebSocketFrame(frame.retainedDuplicate())` và gọi `frame.release()` trong khối `finally` ([§5.4](#54-fan-out-zero-copy--quản-lý-bộ-nhớ-netty)).
- **Nguy cơ kép:**
  1. **Off-Heap Leak**: Bộ nhớ Direct Memory của Netty không được Garbage Collector tự động thu hồi. Nếu thiếu `release()` hoặc unhandled exception → Gateway pod crash đột ngột với `OutOfMemoryError: Direct buffer memory`.
  2. **Phân mảnh glibc (RSS Creep)**: Kể cả khi Netty giải phóng đúng, bộ cấp phát mặc định `glibc ptmalloc` vẫn bị phân mảnh arena do tạo/hủy buffer liên tục và không trả RAM cho OS. Hậu quả: Chỉ số RSS của Pod tăng dần tới hạn ngạch K8s và bị **Kubernetes OOMKilled (`Exit Code 137`)**.
- **Chiến lược phòng ngừa:**
  1. **Ép nạp `jemalloc` trên container**: Thay thế glibc bằng `jemalloc` (`LD_PRELOAD`, `dirty_decay_ms:1000`) để trả ngay RAM thừa về OS, làm phẳng hoàn toàn biểu đồ RSS ([ADR-009](#adr-009), [§6.2](#62-cấu-hình-kubernetes-jvm--native-allocator-jemalloc)).
  2. **Bật kiểm tra rò rỉ trên Staging/Canary:** Cấu hình JVM `-Dio.netty.leakDetection.level=ADVANCED`.
  3. **Giám sát chặt chẽ:** Đặt alert cảnh báo trên Prometheus khi `jvm.buffer.memory.used{id="direct"}` vượt quá 70% hạn mức Direct Memory và `container_memory_working_set_bytes` vượt 80% hạn ngạch Pod.

---

### 9.2 Rủi ro Tầng Runtime & Chấm Điểm (JVM & Actor)

#### ⚠️ Rủi ro 3: GC Pause làm học sinh bị "phạt oan" thời gian trả lời
- **Hiện trạng:** Điểm số phụ thuộc tốc độ: `response_time_ms = server_received_at − server_question_started_at`. Trong đó, `server_received_at` lấy thời gian ngay khi bốc gói tin ra khỏi Mailbox của Actor ([§5.1](#51-năm-quy-tắc-server-authoritative-timestamp)).
- **Nguy cơ:** Nếu JVM của Engine pod xảy ra một đợt **G1 GC Pause kéo dài 80–150ms** (do cấp phát mảng tạm thời hoặc snapshot serialization):
  - Gói tin nộp bài của học sinh đã nằm sẵn trong socket buffer của hệ điều hành, nhưng JVM bị dừng lại (Stop-The-World) nên Actor chưa lấy ra khỏi Mailbox để đóng dấu được.
  - **Hệ quả:** Khi GC pause kết thúc, Actor mới đóng dấu `server_received_at` → Học sinh bị tính thêm 100ms oan uổng, dẫn đến bị trừ điểm oan hoặc bị từ chối do quá deadline!
- **Chiến lược phòng ngừa:**
  1. **Tối ưu cờ JVM GC:** `-XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:G1ReservePercent=15` — đây
     là biện pháp **thật, đang áp dụng** ở GĐ1.
  2. ~~Đóng dấu thời gian sớm tại Netty~~ — **mâu thuẫn trực tiếp với §5.1 rule 1** ("`server_received_at`
     phải được đóng dấu ngay khi lấy gói tin khỏi mailbox của actor") và **không phải hành vi code
     thật**: `RoomState.submitAnswer` đóng dấu `clock.millis()` tại mailbox, đúng §5.1, không phải
     ở `FrameChannelServer`. Mục này từng ghi nhầm như một chiến lược đã áp dụng — **sửa lại
     (2026-09-07): đây là phương án đã cân nhắc và loại**, vì đóng dấu ở Netty đòi hỏi thêm một
     trường timestamp xuyên qua `GameMessage`/`InternalHeader` (thay đổi wire schema, PR riêng)
     chỉ để tiết kiệm phần đuôi GC pause vốn đã có công cụ giảm thiểu riêng (mục 1). Rủi ro GC
     pause **vẫn tồn tại** ở GĐ1 — chỉ được giảm nhẹ bằng tuning GC, chưa được loại bỏ triệt để.

#### ⚠️ Rủi ro 4: "Cái bẫy" Modulo Hash (`room_id % N`) khi thay đổi số Pod
- **Hiện trạng (cập nhật 2026-09-08):** Phần Engine của rủi ro này **đã đóng và verify bằng chaos
  test thật** — `LeaseBasedRoomOwnership` (`plan.md` Task 14) đã có code, đã chạy
  `DockerComposeChaosIT` kill container Engine thật đang giữ phòng, xác nhận pod sống sót giành
  lại lease + phục hồi đúng roster từ Hot Snapshot Valkey. Quyền sở hữu không còn suy ra từ `N`
  nữa (không còn `room_id % N`) khi cờ `uni.engine.room-store.enabled=true` — cờ này **vẫn `false`
  mặc định trong `application.yml` production**, mới chỉ bật ở `docker-compose.dev.yml` test cục
  bộ. **Chưa đóng:** xem mục "Nguy cơ còn lại" ngay dưới — Gateway có một giới hạn riêng, độc lập
  với Task 14, cũng chặn đúng kịch bản "SRE thêm pod giữa ca thi".
- **Nguy cơ (bản gốc, Engine — đã có giải pháp code + chaos test thật):** Trong lúc các lớp học đang diễn ra (4.500 phòng đang chơi), nếu 1 pod bị chết hoặc SRE thấy tải cao muốn scale-up từ 12 pod lên 14 pod:
  - Giá trị $N$ thay đổi → Hầu hết các kết quả của phép tính `room_id % N` sẽ bị **nhảy sang pod khác**!
  - **Hệ quả:** Gateway gửi gói tin sang nhầm pod, actor mới khởi tạo ở pod mới không có dữ liệu phòng cũ, làm vỡ trận hàng nghìn phòng thi đấu!
- **Nguy cơ còn lại (phát hiện 2026-09-08, chưa có giải pháp):** Dù Engine hết phụ thuộc `N`,
  **Gateway đọc `uni.gateway.engine.pods` đúng một lần lúc khởi động** (`GatewayNetworkLifecycle`)
  và không có cơ chế thêm pod mới sau đó (`FrameChannelClient.knownPods` chỉ co lại khi pod ngắt
  kết nối, không bao giờ lớn thêm). Một pod Engine hoàn toàn mới — SRE mới thêm, Gateway chưa từng
  biết tới lúc boot — **không thể nhận được gói tin nào**, bất kể `RoomOwnership` phía Engine đúng
  hay sai. Tức là "scale-up giữa phiên" theo đúng nghĩa vận hành vẫn **chưa khả thi** tới khi có
  giải pháp riêng cho Gateway (xem `plan.md` Task 21, mới) — không phải lỗi của Task 14, mà là một
  giới hạn kiến trúc khác ở tầng Gateway.
- **Chiến lược phòng ngừa:**
  1. **Quy tắc vận hành cứng (`plan.md` Task 19) vẫn có hiệu lực đầy đủ** — không chỉ vì Task 14
     chưa bật production, mà còn vì Task 21 (Gateway) chưa tồn tại. Gỡ quy tắc này cần **cả hai**
     điều kiện: Task 14 bật + verify staging thật, **và** Task 21 xong + verify.
  2. **`LeaseBasedRoomOwnership` (đã chốt + có code + chaos test thật, `plan.md` Task 14):** `SET
     room:owner:{room_id}` + `INCR room:epoch:{room_id}` (2 key tách biệt, xem AC Task 14) để ghim
     pod sở hữu phòng, giải quyết đúng phần "N thay đổi làm vỡ hash" ở phía Engine. `ttl` mặc định
     20s (`lease-ttl-seconds`, trong khoảng khuyến nghị 15–30s) kèm renew định kỳ (`ttl/3`) +
     fencing bằng `epoch` — đã áp dụng thật trong `docker-compose.dev.yml`, chưa bật production.
  3. **Gateway dynamic pod discovery (`plan.md` Task 21, mới — chưa có giải pháp):** cần một cơ
     chế đọc lại/hot-reload `uni.gateway.engine.pods` hoặc tự phát hiện pod mới (service discovery)
     mà không cần khởi động lại Gateway (khởi động lại Gateway tự nó đóng mọi WebSocket đang mở
     trên pod đó — khác hẳn mất 1 Engine pod, vốn được thiết kế để không đóng socket, §9.7). Cluster
     Sharding là một trong các phương án cho vấn đề này (cùng lúc giải quyết luôn việc phát hiện
     thành viên cho cả Gateway lẫn Engine), nhưng không phải điều kiện bắt buộc duy nhất — một cơ
     chế discovery/hot-reload riêng cho Gateway (nhẹ hơn nhiều so với đưa cả Pekko Cluster vào) cũng
     có thể đóng được đúng gap này mà không cần đổi cách Engine quản lý ownership.

---

### 9.3 Rủi ro Tầng Hạ Tầng Phụ Trợ Valkey Cluster & Kafka

#### ⚠️ Rủi ro 5: Lệch đồng hồ (NTP Clock Drift) làm từ chối vé hợp lệ
- **Hiện trạng:** Join token vào phòng có TTL 30 giây. Gateway giải mã JWT/join token và so sánh trường hạn dùng `exp` với đồng hồ cục bộ của pod ([§3.4](#34-xác-thực-one-time-join-token)).
- **Nguy cơ:** Dịch vụ cấp join token (BFF/Auth) và các máy chủ Kubernetes chạy Gateway nằm trên các node vật lý khác nhau. Nếu đồng bộ giờ (NTP) bị lệch chỉ **3–5 giây**:
  - Học sinh vừa nhận join token ở Web, mở WebSocket tới Gateway thì Gateway so với giờ của nó thấy `current_time > exp` → **Lập tức từ chối học sinh với lỗi vé hết hạn**.
- **Chiến lược phòng ngừa:**
  1. **Cấu hình Clock Skew Tolerance:** Thêm khoảng dung sai từ **3–5 giây** khi kiểm tra hạn dùng `exp` tại `JoinTokenAuthHandler`.
  2. **Giám sát đồng bộ NTP:** Đặt alert cảnh báo trên K8s cluster khi độ lệch NTP (`chrony`/`ntpd` offset) giữa các worker node vượt quá 500ms.

#### ⚠️ Rủi ro 6: Kafka Producer Buffer Full làm nghẽn Engine Pod
- **Hiện trạng:** `RoomActor` đẩy `GameEvent` sang Kafka topic `game.events.v1` để lưu DB và làm Dashboard.
- **Nguy cơ:** Nếu Kafka Cluster bị lag (ổ đĩa broker nghẽn I/O hoặc broker rebalancing):
  - Bộ đệm của Kafka Producer trên Engine pod (`buffer.memory = 32MB`) sẽ bị đầy nhanh chóng.
  - Nếu Kafka Producer cấu hình mặc định: Lệnh `send()` sẽ bị **block** chờ giải phóng buffer.
  - **Hệ quả dây chuyền:** Actor bị block bởi lời gọi Kafka → Mailbox của Actor bị đóng băng → Cả phòng 12 học sinh bị đứng hình!
- **Chiến lược phòng ngừa:**
  1. **Bắt buộc Non-blocking:** Cấu hình Kafka Producer `max.block.ms = 0` và `acks = 1`.
  2. **Cô lập hàng đợi in-memory:** Đẩy event vào hàng đợi `Disruptor` hoặc `LinkedBlockingQueue` có giới hạn (bounded) trên thread riêng. Nếu hàng đợi đầy, **chủ động drop event phân tích chứ tuyệt đối không làm tắc nghẽn hot path của game**.

---

### 9.4 Rủi ro Tầng Thiết Bị Client & Người Dùng

#### ⚠️ Rủi ro 7: Thiết bị học sinh cấu hình yếu làm nghẽn UI Thread
- **Hiện trạng:** Client (Flutter / Web) phải duy trì RingBuffer 10 đáp án, giải mã Protobuf và render giao diện liên tục.
- **Nguy cơ:** Trong các trường học, nhiều học sinh sử dụng máy tính bảng cũ hoặc máy tính phòng tin học cấu hình thấp (CPU yếu, RAM hẹp):
  - Trình duyệt/App bị đơ (UI Thread freeze) khiến việc gửi heartbeat hoặc xử lý tin nhắn Protobuf bị chậm.
  - Học sinh ngỡ rằng "Server bị lag" nhưng thực chất là do thiết bị cá nhân bị nghẽn CPU.
- **Chiến lược phòng ngừa:**
  1. **Đo đạc RTT thực tế:** Dùng trường `client_timestamp_ms` so sánh với `server_received_at` để tính toán độ trễ mạng thực tế và hiển thị chỉ báo trạng thái mạng (xanh/vàng/đỏ) trên giao diện học sinh.
  2. **Tối ưu hóa render:** Hạn chế re-render canvas không cần thiết, chỉ cập nhật delta của những thành viên có thay đổi điểm số.

---

### 9.5 Ma trận Tổng hợp Rủi ro & Checklist Thẩm Định cho Team

| # | Rủi ro | Tác động | Khả năng | Biện pháp phòng ngừa cốt lõi | Người phụ trách |
|---|---|:---:|:---:|---|:---:|
| **1** | **GC Pause làm lệch timestamp** | 🔴 Cao | 🟡 Vừa | Đóng dấu `received_at` ở Netty transport; tuning G1 `MaxGCPauseMillis=10` | Backend Lead |
| **2** | **Direct Memory Leak & RSS Bloat** | 🔴 Cao | 🟡 Vừa | Ép nạp `jemalloc` (`LD_PRELOAD`); bật Netty leak detection ADVANCED; alert RSS | Backend / SRE |
| **3** | **Kafka lag làm block Engine** | 🔴 Cao | 🟢 Thấp | Cấu hình `max.block.ms=0`, đẩy event qua worker thread riêng biệt | Backend Dev |
| **4** | **Lệch đồng hồ NTP làm hỏng Join token** | 🟡 Vừa | 🟡 Vừa | Clock skew tolerance 5s tại Gateway; alert NTP sync | DevOps / SRE |
| **5** | **Head-of-Line Blocking trên TCP** | 🟡 Vừa | 🟢 Thấp ở GĐ1 (snapshot đã < 5 KB) | GĐ1: giám sát `internal_frame_p99_latency`/cặp pod. GĐ2 (nếu đo thấy cần): pool 2–4 TCP + sửa ADR-001, không tự làm trước | Backend Dev |
| **6** | **Scale pod làm vỡ Modulo Hash** | 🔴 Cao | 🟢 Thấp | Tạm thời: cấm scale Engine pod giữa trận đấu (Task 19). Fix thật đã chốt: `LeaseBasedRoomOwnership` (Task 14, chưa triển khai) | Backend Dev / DevOps / SRE |
| **7** | **Frontend thiếu RingBuffer (PH-3)** | 🔴 Cao | 🟡 Vừa | Ký hợp đồng kỹ thuật bắt buộc: RingBuffer 10 phần tử + phát `RESYNC` | Frontend Lead |

---

## 10. Ví Dụ Thực Chiến, Chuyển Đổi Yêu Cầu PO Sang Đặc Tả Kỹ Thuật (Dev Specs) & Kịch Bản BDD

Mục này trình bày ví dụ thực tế quy trình chuyển đổi tài liệu yêu cầu nghiệp vụ từ **Product Owner (PO)** (`PO_Require_Game+nhóm_+tập+thể+Inclass.doc`) sang **Đặc Tả Kỹ Thuật cho Developer (Dev Specs)** và các **Kịch Bản Kiểm Thử Hành Vi (BDD - Behavior Driven Development)** nhằm đảm bảo tính toàn vẹn giữa Yêu cầu Sản phẩm và Hiện thực Mã nguồn.

---

### 10.1 Bảng Ánh Xạ Chuyển Đổi Yêu Cầu PO (PO Specs) $\rightarrow$ Kỹ Thuật Developer (Dev Specs)

| Yêu cầu PO (`PO_Require_Game...doc`) | Đặc tả Kỹ thuật Developer (Dev Specs) | Thành phần / Codebase Phụ trách |
|---|---|---|
| **Chế độ chơi (Game Modes):**<br>- `cooperative`: Tập thể (Đánh boss)<br>- `team`: Chia X nhóm thi đấu<br>- `individual`: Thi đấu cá nhân | Mở rộng `GameDefinition` (Task 11) chứa enum `game_mode` (`SOLO`, `COOPERATIVE`, `TEAM`, `INDIVIDUAL`). Khai báo `team_count` và `team_assignment` trong Data Model `RoomState`. | `modules/uni-game-engine/.../definition/GameDefinition.java`<br>`modules/uni-game-engine/.../room/RoomState.java` |
| **Cơ chế Mechanic:**<br>- `progress_meter` (Thanh tiến trình)<br>- `progress_display_mode`: `simple_bar` / `staged_visual` | Cấu hình `progress_target` (đích tiến trình). Thêm mốc phần trăm (`progress_stages`) trong payload Protobuf `RoomStateSnapshot`. `RoomActor` tự tính `% = (câu đúng / progress_target) * 100`. | `modules/uni-protocol/.../game_message.proto`<br>`modules/uni-game-engine/.../room/RoomActor.java` |
| **Điều kiện Thắng (`win_condition`):**<br>- `progress_completed`: Đạt 100%<br>- `first_to_finish`: Đội đầu tiên chạm 100%<br>- `most_points_when_time_up`: Điểm cao nhất khi hết giờ | Thêm `WinConditionEvaluator` vào `RoomState.evaluateStep()`. Khi thỏa mãn điều kiện, `RoomActor` chuyển FSM sang trạng thái `FINISHED` và dừng ván game. | `modules/uni-game-engine/.../room/RoomState.java`<br>`modules/uni-game-engine/.../scoring/FormulaScoreCalculator.java` |
| **Tài nguyên dùng chung (`shared_resource`):**<br>- `time`: Trừ thời gian khi sai<br>- `lives`: Trừ số mạng của phòng | Thêm `shared_resource_type` và `penalty_value`. Khi nộp bài sai, `RoomState` trừ trực tiếp vào `step_deadline_at` hoặc `remaining_lives` của nhóm/phòng. | `modules/uni-game-engine/.../room/RoomState.java` |
| **Tự động hiện nút "Vào chơi" (No Room Code):**<br>- Không cần link hay mã phòng.<br>- Lấy danh tính từ tài khoản Uniclass/CMS | Xác thực `JoinTokenAuthHandler` tại Gateway qua JWT join token một lần (`JoinTokenAuthHandler.java`). Trích xuất `student_id`, `room_id`, `session_id` từ token claim. | `modules/uni-websocket-gateway/.../auth/JoinTokenAuthHandler.java` |
| **Tương thích Hệ thống Cũ (`lms-worker`):**<br>- Thảo luận nhóm, nộp bài tập nhóm, trao cúp thành tích | `GameEventPublisher` đẩy `GameEvent` bất đồng bộ sang Kafka topic `game.events.v1`. Các listener `lms-worker` (`ActiveGroupDiscussionListener`, `SubmitExerciseListener`) tiêu thụ sự kiện từ Kafka để trao cúp/lưu DB. | `modules/uni-game-engine/.../events/GameEventPublisher.java`<br>`vn.edupiaclass.lms.worker.listener.event.group_discussion.*` |

---

### 10.2 Kịch Bản Kiểm Thử Theo Phát Triển Bằng Hành Vi (BDD Scenarios)

Dưới đây là 3 kịch bản BDD chuẩn (`Given - When - Then`) ánh xạ từ yêu cầu của PO để Dev viết Integration Test (`uni-e2e` / `uni-game-engine`):

#### 🧪 Kịch bản BDD 1: Game Tập Thể — Đánh Boss Rồng Số Học (`cooperative` Mode)
```gherkin
Feature: Chế độ chơi Tập thể đánh Boss chung (Cooperative Mode)

  Scenario: Cả lớp 12 học sinh cùng trả lời đúng để đánh gục Boss
    Given 12 học sinh đã vào phòng game chế độ "cooperative" với progress_target = 10 (cần 10 câu đúng)
    And Boss đang ở trạng thái 0% sát thương (Giai đoạn visual 1: Rồng nguyên vẹn)
    When 5 học sinh gửi đáp án đúng SubmitAnswer trong câu 1
    Then Server RoomActor tính toán tiến trình đạt (5 / 10) * 100 = 50%
    And Server broadcast gói tin RoomStateSnapshot mang delta progress_percentage = 50% và stage_index = 3 (Rồng lộ xương sườn)
    And submit_ack trả về cho 5 học sinh có latency p99 < 100ms
    When thêm 5 học sinh khác gửi đáp án đúng SubmitAnswer ở câu 2
    Then tiến trình phòng đạt 100% (progress_completed)
    And Server RoomActor tự động chuyển FSM sang trạng thái FINISHED
    And broadcast thông điệp GameOver chiến thắng cho toàn thể học sinh trong phòng
```

#### 🧪 Kịch bản BDD 2: Game Chia Nhóm Thi Đấu Tốc Độ (`team` Mode)
```gherkin
Feature: Chế độ chơi Chia Nhóm thi đấu (Team Mode)

  Scenario: Hai nhóm thi đấu tốc độ với win_condition = first_to_finish
    Given Phòng game được chia thành 2 nhóm: Đội Đỏ (6 HS) và Đội Xanh (6 HS)
    And score_aggregation = sum_all, round_time_limit = 30s
    When Đội Đỏ có 6 học sinh trả lời đúng ngay ở giây thứ 5
    Then Server RoomActor cộng dồn điểm cho Đội Đỏ và đóng dấu server_received_at chuẩn xác
    And Đội Đỏ hoàn thành tiến trình 100% trước Đội Xanh
    Then Server RoomActor tuyên bố Đội Đỏ thắng cuộc (first_to_finish)
    And Đẩy sự kiện TeamSubmitExerciseEvent sang Kafka topic game.events.v1
    And lms-worker (SubmitExerciseListener) nhận sự kiện và trao cúp danh dự cho Đội Đỏ trong DB
```

#### 🧪 Kịch bản BDD 3: Khôi Phục Kết Nối Giữa Chừng & Chống Nộp Bài Trùng (Resilience & Deduplication)
```gherkin
Feature: Khôi phục kết nối và Chống nộp trùng dữ liệu

  Scenario: Học sinh bị ngắt mạng tạm thời trong lúc ván game đang diễn ra
    Given Học sinh A đã nộp bài thành công ở câu 1 với sequence = 1 và nhận ANSWER_ACK
    When Học sinh A bị rớt mạng WebSocket và kết nối lại sau 10 giây
    Then Gateway xác thực vé One-Time Join Token và tra cứu RouteCache đưa học sinh A về đúng Engine Pod
    And Client gửi gói tin Resync(last_acked_seq = 1, pending = [sequence_1])
    Then Server RoomActor tra cứu LastSeenSequenceTable phát hiện sequence_1 <= last_seen (1 <= 1)
    And Server trả lại ANSWER_ACK cũ, không cộng điểm lần hai, không làm thay đổi điểm số ván game
    And Học sinh A nhận lại đúng trạng thái ván game hiện tại (RoomStateSnapshot) mà không bị mất điểm
```

---

### 10.3 Ma Trận Tương Thích & Quy Trình Chuyển Đổi cho Đội Ngũ Phát Triển (Dev Workflow)

```text
[ PO REQUIREMENT (.DOC) ]
   │
   ├─► 1. Parse Input Schema (Nhóm A: Mode/Mechanic, Nhóm B: LLM Progress Target/Stages)
   ├─► 2. Viết BDD Scenarios (Given - When - Then)
   └─► 3. Ánh xạ vào Codebase hiện tại:
           ├── Protocol: Thêm field vào GameMessage / RoomStateSnapshot (uni-protocol)
           ├── Engine FSM: Bổ sung WinCondition & Progress Calculator vào RoomState (uni-game-engine)
           ├── Gateway: Giữ nguyên JoinTokenAuthHandler & RouteCache (uni-websocket-gateway)
           └─► Worker Integration: Đẩy Kafka Event sang lms-worker / SubmitExerciseListener
```
