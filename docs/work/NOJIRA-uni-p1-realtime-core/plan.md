# Plan: Uni Realtime Giai đoạn 1 — WebSocket Gateway + Game Engine

<!--
Lưu tại:      docs/work/NOJIRA-uni-p1-realtime-core/plan.md
Tech design:  docs/specs/tech-design/EdTech_Game_Realtime_Architecture_v3.0.md
Entry point:  _context.md  (đọc TRƯỚC file này)
-->

---
uc_id: NOJIRA-uni-p1
track: standard
size: L
parallel_safe: true
---

> **Đường dẫn đã chốt (2026-09-05)**: code **thay thế tại chỗ trong repo hiện tại**, build bằng
> **Maven**. Mọi module Maven nằm dưới `modules/` với prefix `uni-`:
> `uni-protocol` / `uni-observability` / `uni-gateway` / `uni-engine`. Chọn module bằng
> `-pl :uni-<tên>` (theo artifactId) thay vì đường dẫn, nên lệnh chạy được từ root.
> `uni-observability` không thuộc task nào — đó là nơi phần observability của project cũ
> được port sang. Task 13 sẽ thêm `modules/uni-e2e`.
> Hai quyết định khác ảnh hưởng task: **TLS terminate ở LB/ingress** (Task 6) và
> **GĐ1 chỉ có quiz → COALESCE-only** (Task 3). Chi tiết ở `_context.md`.

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Pekko scheduler không chịu nổi ~1.000 timer đồng thời/pod** — mỗi phòng dirty tự hẹn một single-shot timer (ADR-4) | Med | **High** — đổi cách hiện thực tick coalescing | **Spike 4 giờ trước Task 3** (§16.3 tự nhận đây là rủi ro chính của Engine). Nếu hỏng → chuyển sang một flush wheel gom lô ở tầng pod thay vì timer mỗi actor |
| **Dùng `retain()` thay `retainedDuplicate()` trong vòng fan-out** | Med | **High** — client thứ 2 trở đi nhận 0 byte, triệu chứng mơ hồ | Test 12 client bắt buộc (Task 8). Test 1 client **sẽ pass** dù code sai. Cộng `-Dio.netty.leakDetection.level=paranoid` |
| **Vô tình tạo hai tầng backpressure** — thêm queue/buffer riêng ở tầng app cạnh watermark socket | Med | Med — nghẽn không truy được tầng nào chặn, mất lợi ích chính của ADR-1 | Task 9 review riêng. Quy tắc: **không** có queue nào giữa `RoomActor` mailbox và socket |
| **Mất Engine pod = mất phòng** (không có sharding ở GĐ1) | **High** | High — đang giờ học | Đánh đổi có chủ đích ở 2–3k CCU. Bắt buộc: UI hiển thị rõ, `terminationGracePeriodSeconds` đủ, và **bỏ trước GĐ2** |
| **Scope creep sang snapshot/sharding** | Med | Med — GĐ1 trượt lịch, gói rủi ro nhất bị làm vội | Ranh giới đã chốt ở `_context.md`. Task nào chạm Redis/ShardRegion → dừng, đưa sang GĐ2 |
| **`Clock` gọi thẳng `System.currentTimeMillis()`** | Med | Med — tick coalescing và chấm điểm không test tất định được | Quyết định D1: `Clock` là tham số tiêm, ép từ Task 2. Sửa sau rất đắt |
| **PH-3: client contract vắng mặt** | **High** | **High** — SLA "mất dữ liệu = 0" không có cơ sở | Ngoài phạm vi GĐ1. Đã ghi ở `_context.md`. **Không được công bố SLA đó** trước khi client ship ring buffer + RESYNC |

**Rollback plan:**
- **Bối cảnh**: greenfield, chưa có production. Rollback ở đây là **rollback từng task**, không phải rollback release.
- Trigger: task fail verification, hoặc build tổng thể gãy sau khi merge task.
- Action: `git revert <commit>` của đúng task đó. Không task nào trong GĐ1 tạo migration DB hay thay đổi schema ngoài `modules/uni-protocol/`.
- Lưu ý riêng: **Task 1 (`modules/uni-protocol/`) là ngoại lệ** — mọi task khác phụ thuộc nó. Revert Task 1 nghĩa là revert cả nhánh. Đổi `.proto` sau Task 1 phải đi qua PR riêng và codegen lại cả hai service.
- Estimated time: < 10 phút/task.

---

## Execution Mode

**Mode:** mixed

```
Dependency graph:

  [T1 protocol]
    ├──→ [T2 RoomActor core] ──→ [SPIKE timer] ──→ [T3 tick coalescing] ──┐
    │         └──→ [T11 game definition tối giản] ────────────────────────┤
    │                                                                      │
    ├──→ [T4 frame channel codec+server] ──→ [T10 ownership + stamp] ──────┤
    │         └──→ [T5 GW frame client + route cache] ─────────────────────┤
    │                                                                      │
    └──→ [T6 GW pipeline + auth] ──┬──→ [T7 rate limit] ──────────────────┤
                                    └──→ [T8 fan-out] ──→ [T9 backpressure]┤
                                                                           ▼
                                                        [SYNC] ──→ [T13 E2E skeleton]

  [T12 observability] ── song song toàn tuyến, gộp bất cứ lúc nào sau T1
```

Ba nhánh **T2 / T4 / T6** độc lập hoàn toàn sau T1 — ba người làm song song được.

---

## Task List

### Task 1: Dựng module `protocol` và chốt `game_message.proto` — ✅ XONG (2026-09-05)

- **Mode:** sequential after [none] — **chặn toàn bộ các task khác**
- **Kết quả:** `mvn clean install` xanh toàn reactor; `GameMessageRoundTripTest` 4/4 pass.
  Ba nhánh T2 / T4 / T6 giờ mở, chạy song song được.
- **Mô tả:** Một schema Protobuf dùng chung cho cả biên WebSocket lẫn kênh nội bộ (ADR-1). Codegen một lần, hai service cùng phụ thuộc module này.
- **File dự kiến:** `modules/uni-protocol/src/main/proto/game_message.proto`, `modules/uni-protocol/pom.xml`
- **Dependency:** none
- **Acceptance criteria:**
  - [x] `GameMessage` có đủ: `type`, `room_id`, `student_id`, `student_index`, `sequence`, `client_timestamp_ms`
  - [x] `client_timestamp_ms` có **comment ngay trong `.proto`** ghi rõ chỉ dùng cho telemetry, **cấm dùng tính điểm** (§9.4 điều 5)
  - [x] `InternalHeader` có `owner_pod_id` và `epoch` — `epoch` chưa dùng ở GĐ1 nhưng có sẵn chỗ cho GĐ2
  - [x] Payload `oneof` phủ: `JoinRoom`, `SubmitAnswer`, `Resync`, `RoomStateSnapshot`, `AnswerAck`, `QuestionStarted`, `StudentJoined`, `GameOver`, `ConnectionDegraded`, `TeacherCommand`
  - [x] `gateway` và `engine` **cùng phụ thuộc một artifact** — không copy `.proto` sang hai nơi
- **Verification:** `mvn -pl :uni-protocol clean install` + test serialize/deserialize khứ hồi giữ nguyên mọi trường
- **Rollback nếu fail:** revert commit. Xem lưu ý ngoại lệ ở Rollback plan.

---

### Task 2: Hiện thực `RoomActor` — FSM, chấm điểm, server timestamp, dedupe — ✅ XONG (2026-09-06)

- **Mode:** sequential after [T1] · parallel with [T4, T6]
- **Mô tả:** Lõi game engine. Pekko Typed, đơn luồng, `Clock` tiêm vào. Gồm luôn watchdog đo-và-cảnh-báo.
- **Kết quả:** `mvn -pl :uni-engine test -Dtest=RoomActorTest` 8/8 pass; `mvn -pl :uni-engine test`
  (toàn module) 9/9 pass, leak detection `paranoid` sạch. Chi tiết: `note.md`.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../room/RoomActor.java`, `.../room/RoomState.java`, `.../scoring/ScoreCalculator.java`
- **Dependency:** Task 1
- **Acceptance criteria:**
  - [x] FSM `LOBBY → PLAYING → FINISHED`; `FINISHED` kết thúc bằng `Behaviors.stopped()`
  - [x] `server_received_at = clock.millis()` đóng dấu **ngay khi lấy khỏi mailbox**, trước mọi xử lý
  - [x] `response_time_ms = server_received_at − server_question_started_at`
  - [x] Từ chối khi `server_received_at > deadline + GRACE(500ms)`, ACK kèm lý do
  - [x] **`client_timestamp_ms` không xuất hiện ở bất kỳ đâu trong đường chấm điểm** (grep được)
  - [x] `LastSeenSequenceTable` (`student_id → last_seq`): `sequence ≤ last_seen` → **gửi lại ACK cũ**, không im lặng bỏ, không cộng điểm lần hai
  - [x] `Clock` là tham số constructor — **không** gọi `System.currentTimeMillis()` trực tiếp
  - [x] Watchdog: đo `System.nanoTime()` quanh `handle()`, ghi metric, `log.warn` khi > 10ms. **Không cố ngắt** (§10.5)
- **Verification:** `mvn -pl :uni-engine test -Dtest=RoomActorTest` — dùng `BehaviorTestKit`, không mạng. Phải có case: gian lận `client_timestamp_ms` → điểm không đổi; gửi lại cùng `sequence` → điểm không đổi + ACK cũ trả lại.
- **Ghi chú còn treo:** `ScoreCalculator` dùng `PlaceholderScoreCalculator` tạm (flat, đánh dấu rõ
  TEMPORARY) vì công thức điểm Quiz Product chưa chốt (tech-design.md §9.2 câu 1). Thay bằng
  công thức thật khi Product quyết — không được lặng lẽ trở thành mặc định production.
- **Rollback nếu fail:** revert commit; T3/T11 chưa bắt đầu nên không kéo theo gì.

---

### SPIKE (time-box 4 giờ): Pekko scheduler chịu được bao nhiêu timer đồng thời?

- **Mode:** sequential after [T2] — **chạy TRƯỚC T3**
- **Mô tả:** ADR-4 khiến mỗi phòng dirty tự hẹn một single-shot timer. §16.3 đã nhận đây là rủi ro chính của Engine nhưng chưa ai đo. Pekko dùng hashed-wheel scheduler, `tick-duration` mặc định 10ms.
- **Câu hỏi cần trả lời:** ~1.000 `startSingleTimer` đồng thời/pod ở độ phân giải 200ms có giữ được độ chính xác và không ăn hết CPU không?
- **Cách làm:** spawn 1.000 actor rỗng, mỗi actor hẹn timer 200ms theo nhịp ngẫu nhiên; đo độ lệch thực tế so với hạn hẹn (p50/p99) và CPU. Không cần Gateway, không cần mạng.
- **Đạt nếu:** p99 độ lệch < 50ms và CPU < 30% ở 2 vCPU.
- **Nếu không đạt:** đổi thiết kế sang **một flush wheel gom lô ở tầng pod** (một timer duy nhất quét danh sách phòng dirty) thay vì timer mỗi actor. Ghi lại quyết định vào v3.0 §6.2.
- **Output:** ghi theo `templates/spike-template.md`, đặt tại `docs/work/NOJIRA-uni-p1-realtime-core/spike-pekko-timer.md`

---

### Task 3: Tick coalescing trong `RoomActor`

- **Mode:** sequential after [T2, SPIKE]
- **Mô tả:** 200ms là **trần tần suất**, không phải nhịp phát (ADR-4). Cách hiện thực phụ thuộc kết quả SPIKE.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../room/RoomActor.java` (mở rộng), `.../room/CoalescingFlush.java`
- **Dependency:** Task 2, SPIKE
- **Acceptance criteria:**
  - [ ] **Phòng im lặng phát 0 gói** — đây là mệnh đề trung tâm của ADR-4, phải có test riêng
  - [ ] Độ trễ tối đa từ lúc `dirty` tới lúc broadcast ≤ 200ms
  - [ ] Broadcast là **delta**, không phải full state (full state chỉ khi JOIN)
  - [ ] `ANSWER_ACK`, `GAME_OVER`, `TEACHER_COMMAND`, `QUESTION_STARTED`, `CONNECTION_DEGRADED` **bypass hoàn toàn** coalescing (§5.4)
  - [ ] `tick_mode` đọc từ Game Definition (`COALESCE` mặc định), không hardcode toàn cục
  - [ ] **GĐ1 chỉ hiện thực đường `COALESCE`** (quyết định #4: danh mục GĐ1 chỉ có quiz).
        `tick_mode: FIXED` vẫn nằm trong schema nhưng **chưa có implementation** →
        nạp definition có `FIXED` phải **fail nhanh lúc nạp** với thông báo rõ ràng,
        tuyệt đối không im lặng rơi về `COALESCE`
- **Verification:** `mvn -pl :uni-engine test -Dtest=TickCoalescingTest` với `ManualTime` của Pekko. Case bắt buộc: 5 giây không có input → **đếm đúng 0 gói outbound**.
- **Rollback nếu fail:** revert; T2 vẫn dùng được (broadcast ngay lập tức, chấp nhận tải cao tạm thời).

---

### Task 4: Internal Frame Channel — codec + phía server (Engine) — ✅ XONG (2026-09-06)

- **Mode:** sequential after [T1] · parallel with [T2, T6]
- **Mô tả:** TCP dài hạn + length-prefixed Protobuf (ADR-1). Dùng handler có sẵn của Netty, **không tự viết parser**.
- **Kết quả:** `mvn -pl :uni-engine test -Dtest=FrameCodecTest` 4/4 pass (EmbeddedChannel);
  `mvn -pl :uni-engine test -Dtest=FrameChannelServerTest` 1/1 pass (socket thật, port ephemeral —
  bonus so với yêu cầu, chứng minh bootstrap thật sự bindable). Toàn module 14/14, leak detection
  `paranoid` sạch. Chi tiết: `note.md`.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../net/FrameChannelServer.java`, `.../net/FrameCodec.java`
- **Dependency:** Task 1
- **Acceptance criteria:**
  - [x] `LengthFieldPrepender(4)` + `LengthFieldBasedFrameDecoder(1MB, 0, 4, 0, 4)`
  - [x] **Một connection dùng chung cho mọi phòng** giữa mỗi cặp (GW pod, Engine pod) — multiplex bằng `room_id`, không phải một connection mỗi phòng
  - [x] Gói bị chia thành nhiều mảnh TCP vẫn ráp đúng
  - [x] Gói vượt `maxFrameLength` → đóng connection + log, không OOM
- **Verification:** `mvn -pl :uni-engine test -Dtest=FrameCodecTest` dùng `EmbeddedChannel`, **bắt buộc có case ghi từng byte một** để chứng minh framing đúng khi phân mảnh.
- **Ghi chú:** protobuf encode/decode viết tay bằng `GameMessage.parseFrom`/`toByteArray` thay vì
  `netty-codec-protobuf` (có sẵn transitively nhưng cần prototype reflection) — đơn giản hơn và
  vẫn đúng tinh thần "không tự viết parser" (phần framing vẫn 100% Netty). Netty 4.2.17 đã
  deprecate `NioEventLoopGroup`; `FrameChannelServer` dùng `MultiThreadIoEventLoopGroup` +
  `NioIoHandler.newFactory()` thay thế — không còn warning deprecation khi build.
  `FrameChannelServer` chỉ bootstrap + gọi callback `Consumer<GameMessage>`; định tuyến gói tin
  tới đúng `RoomActor` theo `room_id` là việc của Task 10 (`RoomOwnership`), chưa làm ở đây.
- **Rollback nếu fail:** revert; nhánh T2 không bị ảnh hưởng.

---

### Task 5: Frame Channel phía Gateway + lazy-learned route cache — ✅ XONG (2026-09-06)

- **Mode:** sequential after [T1, T4]
- **Mô tả:** **PH-2** — Gateway học vị trí phòng từ `owner_pod_id` do Engine đóng dấu, không tự tính hash. Đây là lý do Giai đoạn 2 sẽ không phải sửa Gateway.
- **Kết quả:** `mvn -pl :uni-gateway test -Dtest=RouteCacheTest` 5/5 pass (plain JUnit, logic thuần).
  `mvn -pl :uni-gateway test -Dtest=FrameChannelClientTest` 3/3 pass (socket thật, 2 fake Engine
  pod trên loopback — ngoài yêu cầu Verification, chứng minh round-robin/learn/evict thật hoạt
  động cùng nhau, không chỉ đúng ở mức map). Toàn module 15/15, toàn reactor xanh. Chi tiết: `note.md`.
- **File dự kiến:** `modules/uni-gateway/src/main/java/.../routing/FrameChannelClient.java`, `.../routing/RouteCache.java`
- **Dependency:** Task 1, Task 4
- **Acceptance criteria:**
  - [x] Gateway giữ connection tới **tất cả** Engine pod
  - [x] Chưa biết phòng → gửi **round-robin**; response mang `owner_pod_id` → cache `room_id → pod`
  - [x] Lần gửi sau đi **thẳng** tới đúng pod, không qua hop nội bộ
  - [x] Connection tới một Engine pod đứt → **xoá mọi entry cache trỏ tới pod đó**
  - [x] Cache **không có TTL** — entry sai tự sửa ở lần dùng kế tiếp (§8.2)
  - [x] `RouteCache` **không biết gì về `room_id % N`** — quy tắc sở hữu nằm hoàn toàn bên Engine
- **Verification:** `mvn -pl :uni-gateway test -Dtest=RouteCacheTest`. Case bắt buộc: sau khi pod A đứt, không entry nào còn trỏ tới A; gói kế tiếp quay lại round-robin.
- **Ghi chú:** Cần một `InternalFrameCodec` riêng ở `uni-gateway` (không tái dùng `FrameCodec`
  package-private của `uni-engine` — hai service triển khai độc lập, chỉ dùng chung schema
  `uni-protocol`, không dùng chung code Netty). `FrameChannelClient` nhận `EventLoopGroup` từ
  bên ngoài (dùng chung với `GatewayBootstrap`), không tự tạo group riêng — giữ đúng bất biến
  "EventLoop cố định = cores × 2" của Task 6 cho toàn bộ pod, không nhân đôi số thread.
- **Rollback nếu fail:** revert. Không có fallback tạm — thiếu task này thì Gateway không gửi được gì tới Engine.

---

### Task 6: Netty pipeline Gateway + WS handshake + ticket auth — ⚠️ MỘT PHẦN XONG (2026-09-06)

- **Mode:** sequential after [T1] · parallel with [T2, T4]
- **Mô tả:** Biên WebSocket. Spring Boot chỉ lo bootstrap/actuator, đường đi gói tin là Netty thuần (quyết định A1).
- **Kết quả:** `mvn -pl :uni-gateway test -Dtest=GatewayPipelineTest` 6/6 pass (`EmbeddedChannel`),
  toàn module 7/7, không leak, không deprecation warning. Chi tiết: `note.md`.
- **File dự kiến:** `modules/uni-gateway/src/main/java/.../net/GatewayBootstrap.java`, `.../auth/TicketAuthHandler.java`, `.../net/RoomRouteHandler.java`
- **Dependency:** Task 1
- **Acceptance criteria:**
  - [x] Pipeline đúng thứ tự: `HttpServerCodec → HttpObjectAggregator(8KB) → WebSocketServerProtocolHandler → TicketAuthHandler → RateLimitHandler → ProtobufDecoder → RoomRouteHandler`
  - [x] `TicketAuthHandler` **tự gỡ khỏi pipeline** sau handshake — mỗi gói sau đó không verify lại chữ ký
  - [x] Ticket hợp lệ → ghi `ChannelAttributes{student_id, room_id, session_id}`
  - [x] **`room_id` luôn đọc từ `ChannelAttributes`, không bao giờ từ payload** (§10.6)
  - [x] Payload mang `room_id` khác attribute → **đóng channel + log cảnh báo bảo mật**
  - [x] Netty EventLoop cố định = cores × 2; **không** DB/Redis/HTTP call nào trong EventLoop (§13.2)
  - [x] **Không có `SslHandler` trong pipeline** — TLS terminate ở LB/ingress (quyết định #3),
        pod nhận WS plaintext. Không thêm cờ config bật TLS tại pod ở GĐ1
  - [ ] Ràng buộc lên ingress phải ghi thành văn bản trong `docker-compose.dev.yml` / manifest:
        **passthrough WebSocket upgrade** và **idle-timeout > chu kỳ heartbeat** (30s),
        nếu không connection sẽ bị LB cắt giữa chừng — **chưa làm**, `docker-compose.dev.yml`
        chưa tồn tại trong repo (thuộc phạm vi Task 13 walking skeleton)
- **Verification:** `mvn -pl :uni-gateway test -Dtest=GatewayPipelineTest`. Case bắt buộc: gói có `room_id` giả mạo → channel bị đóng.
- **Ghi chú quan trọng — thuật toán ký ticket KHÔNG được hiện thực ở đây:** tech-design.md
  G1a/G1c (thuật toán ký, phân phối khoá, dung sai lệch đồng hồ) **vẫn chưa chốt** — phải hỏi
  đội dịch vụ nền tảng, không tự bịa. `TicketAuthHandler` vì vậy nhận một `TicketVerifier`
  (interface only, **không có implementation thật trong main code**) qua constructor;
  test dùng fake verifier. Khi G1a/G1c chốt, chỉ cần viết một implementation thật của
  `TicketVerifier` và wire vào `GatewayBootstrap` — không phải sửa `TicketAuthHandler`.
  **Không dùng verifier tạm này ở staging/production.**
- **Rollback nếu fail:** revert; nhánh T2/T4 không bị ảnh hưởng.

---

### Task 7: Rate limiting phân tầng theo loại thông điệp — ⚠️ MỘT PHẦN XONG (2026-09-06)

- **Mode:** sequential after [T6] · parallel with [T8]
- **Mô tả:** Khoá theo `student_id`, **không** khoá theo IP làm tầng chính — trường học đi sau NAT dùng chung một IP (§10.1).
- **Kết quả:** `mvn -pl :uni-gateway test -Dtest=RateLimitHandlerTest` 5/5 pass (`EmbeddedChannel`,
  `Clock` cố định) + `TokenBucketTest` 4/4 pass (logic thuần). Toàn module 24/24, toàn reactor xanh.
- **File dự kiến:** `modules/uni-gateway/src/main/java/.../net/RateLimitHandler.java`
- **Dependency:** Task 6
- **Acceptance criteria:**
  - [x] Bucket riêng theo loại: `SUBMIT_ANSWER` 3/refill 1s · `UPDATE_DRAFT` 10/refill 10s · `HEARTBEAT` 2/refill 1 mỗi 30s
  - [x] Khoá bucket là `student_id` lấy từ `ChannelAttributes` (cụ thể: mỗi connection/`RateLimitHandler`
        đã gắn với đúng 1 student_id sau `TicketAuthHandler`, nên bucket state per-instance = per-student_id)
  - [ ] L1 theo IP (nếu bật) đặt **300 handshake/phút**, không phải 5 — **chưa hiện thực**, xem ghi chú
  - [x] Vượt ngưỡng → `RATE_LIMIT_EXCEEDED`, **không** đóng channel (chỉ áp dụng đúng nghĩa cho
        `SUBMIT_ANSWER` — xem ghi chú về `UPDATE_DRAFT`/`HEARTBEAT`)
- **Verification:** `mvn -pl :uni-gateway test -Dtest=RateLimitTest`. Case bắt buộc: **500 client sau cùng một IP đều kết nối được** — đây là kịch bản khách hàng thật.
  (Tên file test thật là `RateLimitHandlerTest`, khớp class `RateLimitHandler` — `RateLimitTest`
  trong plan có vẻ là tên rút gọn.)
- **Ghi chú quan trọng:**
  - **"tổng 15/15s"** trong AC gốc không hiện thực thành bucket thứ tư riêng — ba cửa sổ thời
    gian khác nhau (1s/10s/30s) không gộp thành một cửa sổ chung có nghĩa rõ ràng; đọc đây là
    tổng ước lượng thô, không phải cơ chế cần code.
  - **L1 IP-based admission control (300 handshake/phút)** là "nếu bật" theo chính AC — đây là
    control ở tầng handshake/ingress, không phải per-message như `RateLimitHandler`. Không hiện
    thực ở Task 7 vì chưa có điểm gắn (chưa có admission-control component nào trong repo).
    Case bắt buộc "500 client cùng IP đều kết nối được" **vẫn đúng theo cấu trúc** — vì không có
    cơ chế nào theo dõi IP ở đây cả, test xác nhận 500 `RateLimitHandler` độc lập không hề đụng
    nhau.
  - **`UPDATE_DRAFT`/`HEARTBEAT` vượt ngưỡng bị drop im lặng, không có `RATE_LIMIT_EXCEEDED`
    thật trên dây** — schema không có payload ack nào cho hai loại này (`UPDATE_DRAFT` còn thiếu
    hẳn payload trong oneof, tech-design.md §G3). Chỉ `SUBMIT_ANSWER` có `AnswerAck.reject_reason`
    để mang tín hiệu này thật sự. Không tự thêm field/message mới vào `.proto` ở task này.
- **Rollback nếu fail:** revert; tạm chạy không rate limit ở môi trường dev, **không** đưa lên staging.

---

### Task 8: Room registry cục bộ + fan-out zero-copy

- **Mode:** sequential after [T6] · parallel with [T7]
- **Mô tả:** `Map<room_id, Set<Channel>>` trong từng pod, broadcast bằng `retainedDuplicate()`. **Đây là task dễ sai nhất trong GĐ1.**
- **File dự kiến:** `modules/uni-gateway/src/main/java/.../fanout/RoomRegistry.java`, `.../fanout/Broadcaster.java`
- **Dependency:** Task 6
- **Acceptance criteria:**
  - [ ] Dùng **`retainedDuplicate()`**, tuyệt đối không `retain()`
  - [ ] `frame.release()` nằm trong khối `finally`
  - [ ] Fan-out chỉ lặp trên `Set<Channel>` của đúng phòng, **không lọc động từ danh sách toàn cục**
  - [ ] `channelInactive` gỡ Channel khỏi **mọi** set ngay lập tức
  - [ ] Engine gửi **một gói cho mỗi GW pod**, Gateway mới nhân bản (quyết định B1)
- **Verification:** `mvn -pl :uni-gateway test -Dtest=FanoutTest` với **12 `EmbeddedChannel`** — kiểm **từng client nhận đủ số byte**, không chỉ client đầu. Chạy kèm `-Dio.netty.leakDetection.level=paranoid`, log leak phải sạch.
- **Rollback nếu fail:** revert.

> [!CAUTION]
> Test 1 client **sẽ pass** kể cả khi code dùng sai `retain()`. Chỉ test ≥ 2 client mới lộ.
> Không rút gọn tiêu chí 12 client này.

---

### Task 9: Nối chuỗi backpressure một tầng

- **Mode:** sequential after [T5, T8]
- **Mô tả:** Một cơ chế backpressure duy nhất chạy suốt từ mailbox actor về tới socket client (§10.2). Đây là lợi ích chính của ADR-1 — làm hỏng nó là mất lý do bỏ gRPC.
- **File dự kiến:** `modules/uni-gateway/src/main/java/.../net/BackpressureHandler.java`, `modules/uni-engine/src/main/java/.../net/FrameChannelServer.java` (sửa)
- **Dependency:** Task 5, Task 8
- **Acceptance criteria:**
  - [ ] Chuỗi đúng: mailbox đầy → Engine ngừng đọc Frame Channel → TCP window đóng → GW thấy `!isWritable()` → `autoRead(false)` trên WS client
  - [ ] `WRITE_BUFFER_WATER_MARK` = (32 KB low, 64 KB high) mỗi channel
  - [ ] `!isWritable()`: **Best-effort → drop** · **Critical → không drop**, đóng channel (§5.4)
  - [ ] **Không có queue hay buffer tầng app nào** giữa mailbox và socket
  - [ ] Metric `channel_not_writable_total` được phát ra
- **Verification:** `mvn -pl :uni-gateway,:uni-engine test -Dtest=BackpressureTest` — client chậm không kéo tụt client khác cùng phòng.
- **Rollback nếu fail:** revert. **Không** merge nếu tiêu chí "không có queue tầng app" bị vi phạm — sửa sau rất đắt.

---

### Task 10: Quyền sở hữu phòng ở Engine + đóng dấu `owner_pod_id` — ✅ XONG (2026-09-06)

- **Mode:** sequential after [T4]
- **Mô tả:** `room_id % N` **bên trong Engine**, cô lập trong đúng một class để Giai đoạn 2 chỉ thay class này bằng ShardRegion (quyết định B2).
- **Kết quả:** `mvn -pl :uni-engine test -Dtest=RoomOwnershipTest` 5/5 pass (plain JUnit, logic
  thuần) + `RoomOwnershipHandlerTest` 2/2 pass (`EmbeddedChannel`, thêm ngoài yêu cầu — chứng
  minh handler thật sự forward/đóng dấu NOT_OWNER đúng, không chỉ đúng thuật toán). Grep bắt
  buộc đã chạy, chỉ khớp `ModuloRoomOwnership`. Toàn module 21/21, toàn reactor xanh.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../room/RoomOwnership.java`, `.../net/FrameChannelServer.java` (sửa)
- **Dependency:** Task 4
- **Acceptance criteria:**
  - [x] Quy tắc sở hữu nằm sau **một interface duy nhất** (`RoomOwnership`), có đúng một implementation `ModuloRoomOwnership`
  - [x] Mọi response đóng dấu `InternalHeader.owner_pod_id`
  - [x] Nhận gói của phòng không thuộc pod này → forward hoặc trả `NOT_OWNER` kèm owner hiện tại
        (chọn trả `NOT_OWNER` — đúng cơ chế §4.5 đã tài liệu hoá; không hiện thực forward
        Engine-to-Engine vì không nằm trong bất kỳ task nào)
  - [x] **Không class nào ngoài `RoomOwnership` biết tới phép `% N`** (grep được)
- **Verification:** `mvn -pl :uni-engine test -Dtest=RoomOwnershipTest` + `grep -rn "% N\|modulo" modules/uni-engine/src/main --include=*.java` chỉ trả về `ModuloRoomOwnership`.
- **Ghi chú:** `FrameChannelServer` đổi constructor để nhận thêm `RoomOwnership` (đã sửa
  `FrameChannelServerTest` theo — dùng `ModuloRoomOwnership` 1-pod cho kịch bản "sở hữu mọi
  phòng" của test cũ). Logic ownership + NOT_OWNER tách thành `RoomOwnershipHandler` riêng
  (`engine.net`) để test được qua `EmbeddedChannel` mà không cần bind socket thật.
- **Rollback nếu fail:** revert.

---

### Task 11: Game Definition tối giản + `MAX_TRANSITIONS`

- **Mode:** sequential after [T2] · parallel with [T3]
- **Mô tả:** Đủ để chơi trọn một ván quiz. Kèm rào chắn runtime tối thiểu (§10.5).
- **File dự kiến:** `modules/uni-engine/src/main/java/.../definition/GameDefinition.java`, `.../definition/DefinitionLoader.java`
- **Dependency:** Task 2
- **Acceptance criteria:**
  - [ ] Định nghĩa được: danh sách step, thời lượng mỗi step, `tick_mode`, công thức điểm
  - [ ] **Validate DAG lúc nạp** — phát hiện chu trình bằng duyệt đồ thị, từ chối definition có chu trình
  - [ ] **Không script engine, không biểu thức tuỳ ý** — công thức điểm dùng tập toán tử giới hạn
  - [ ] `MAX_TRANSITIONS` mỗi phiên làm hàng rào cuối
  - [ ] `missed_step_policy` **có mặt trong schema** với mặc định `ZERO`, dù luồng late join chưa làm ở GĐ1
  - [ ] `tick_mode` chấp nhận `COALESCE` | `FIXED` trong schema, nhưng loader **từ chối `FIXED`
        lúc nạp** ở GĐ1 (chưa có implementation — xem Task 3, quyết định #4)
- **Verification:** `mvn -pl :uni-engine test -Dtest=DefinitionLoaderTest` — definition có chu trình bị từ chối **lúc nạp**, không phải lúc chạy.
- **Rollback nếu fail:** revert; tạm hardcode một quiz cố định để T11 chạy được.

---

### Task 12: Nền quan sát

- **Mode:** parallel — gộp bất cứ lúc nào sau T1
- **Mô tả:** Các metric của §15.1 áp dụng được ở GĐ1. Không chờ tới cuối mới gắn.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../metrics/EngineMetrics.java`, `modules/uni-gateway/src/main/java/.../metrics/GatewayMetrics.java`
- **Dependency:** Task 1
- **Acceptance criteria:**
  - [ ] Engine: `actor_processing_latency` (p99), `actor_mailbox_depth` (p99)
  - [ ] Gateway: `fanout_latency` (p99), `handshake_rate`, `channel_not_writable_total`
  - [ ] `/actuator/prometheus` trả đủ các metric trên ở cả hai service
  - [ ] Trace ID sinh tại Gateway lúc handshake, truyền qua `InternalHeader` xuống actor (§15.3)
- **Verification:** `curl -s localhost:8080/actuator/prometheus | grep -E "actor_mailbox_depth|channel_not_writable"` trả về kết quả khác rỗng.
- **Rollback nếu fail:** revert; không chặn task nào khác.

---

### Sync checkpoint

> Chờ toàn bộ T3, T5, T7, T8, T9, T10, T11, T12 xong trước khi bắt đầu T13.

- [ ] Ba nhánh song song (T2/T4/T6) đều pass verification
- [ ] SPIKE có kết luận ghi thành văn bản, và T3 hiện thực **đúng theo kết luận đó**
- [ ] `mvn clean install` toàn project sạch
- [ ] Log leak detection (`paranoid`) sạch trong toàn bộ test suite
- [ ] Không task nào chạm Redis, ShardRegion hay Kafka — ranh giới GĐ1 còn nguyên

---

### Task 13: Ghép walking skeleton end-to-end + smoke test

- **Mode:** sequential after [SYNC]
- **Mô tả:** Chứng minh một gói tin đi hết vòng qua hệ thống thật. Đây là tiêu chí "xong Giai đoạn 1".
- **File dự kiến:** `modules/uni-e2e/src/test/java/.../WalkingSkeletonTest.java`, `docker-compose.dev.yml`
- **Dependency:** Sync checkpoint
- **Acceptance criteria:**
  - [ ] 12 client thật join một phòng qua WebSocket, nhận `ROOM_STATE_SNAPSHOT` với `student_index` khác nhau
  - [ ] Submit → nhận `ANSWER_ACK` (Critical, đi ngay) → **cả 12 client** nhận delta snapshot
  - [ ] Submit lại cùng `sequence` → điểm không đổi, ACK cũ được trả lại
  - [ ] 5 giây không ai thao tác → **0 gói outbound** (chứng minh ADR-4 chạy thật, không chỉ trong unit test)
  - [ ] Chạy với ≥ 2 Engine pod: route cache học đúng `owner_pod_id`, gói đi thẳng từ lần thứ hai
  - [ ] Giết một Engine pod → client thuộc pod đó nhận `CONNECTION_DEGRADED` và **WebSocket KHÔNG bị đóng** (§9.7)
- **Verification:** `mvn -pl :uni-e2e verify` với `docker-compose.dev.yml` (2 GW + 2 Engine).
- **Rollback nếu fail:** không revert — đây là task tích hợp, fail nghĩa là một task thượng nguồn sai. Truy về task đó.

---

## Pre-merge Checklist

- [ ] Tất cả task pass verification
- [ ] `mvn clean install` sạch từ root
- [ ] Test suite chạy với `-Dio.netty.leakDetection.level=paranoid`, **không có leak**
- [ ] `grep -rn "client_timestamp_ms" modules/uni-engine/src/main --include=*.java` → **không hit nào trong đường chấm điểm**
- [ ] `grep -rn "\.retain()" modules/uni-gateway/src/main --include=*.java` → **không hit nào trong vòng fan-out**
- [ ] Không có TODO/FIXME chưa resolve trong code mới
- [ ] SPIKE đã có kết luận và T3 khớp với kết luận đó
- [ ] `_context.md` cập nhật `dev_selftest` và `phase`

> [!NOTE]
> **Các gate của framework kit không chạy được trong repo này** — `scripts/governance-check.sh`,
> `validate-sdd-gate.sh`, `validate-trace.sh` đều không tồn tại (xem `_context.md`).
> Checklist trên dùng lệnh build/test thật thay thế. Muốn có gate thật thì chạy skill
> `onboarding` trước, đừng đánh dấu các dòng đó là pass khi script không tồn tại.
