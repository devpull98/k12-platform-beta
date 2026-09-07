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
- **Kết quả:** `mvn -pl :uni-engine test -Dtest=RoomActorTest` 9/9 pass; `mvn -pl :uni-engine test`
  (toàn module) 46/46 pass, leak detection `paranoid` sạch. Chi tiết: `note.md`.
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
- **Cập nhật 2026-09-06 (sau khi Product chốt công thức, system-architecture.md §2.5):**
  `PlaceholderScoreCalculator` đã bị xoá, thay bằng `FormulaScoreCalculator` (bọc
  `ScoringFormula` — cây biểu thức đóng của Task 11 — thay vì một class chấm điểm đứng riêng,
  tránh nhân đôi cơ chế). `FormulaScoreCalculator.binaryChoice()` hiện thực đúng công thức
  Phase 1: trắc nghiệm 1-trong-4, nhị phân đúng/sai, đúng = 100 điểm, sai = 0, không bonus tốc
  độ. `ScoreCalculator.award(...)` mở rộng nhận thêm `correctAnswerIds`, nối qua
  `RoomState.startQuestion(...)` (3 tham số) và `RoomActor.StartQuestion` (3 field). Test mới:
  `FormulaScoreCalculatorTest` (3 case) + `RoomActorTest.should_award0_when_answerDoesNotMatchTheCorrectChoice`.
  `mvn -pl :uni-engine test` 46/46 pass. Không còn "Ghi chú còn treo" nào cho Task 2.
- **Rollback nếu fail:** revert commit; T3/T11 chưa bắt đầu nên không kéo theo gì.

---

### SPIKE (time-box 4 giờ): Pekko scheduler chịu được bao nhiêu timer đồng thời? — ✅ ĐẠT (2026-09-06)

- **Mode:** sequential after [T2] — **chạy TRƯỚC T3**
- **Mô tả:** ADR-4 khiến mỗi phòng dirty tự hẹn một single-shot timer. §16.3 đã nhận đây là rủi ro chính của Engine nhưng chưa ai đo. Pekko dùng hashed-wheel scheduler, `tick-duration` mặc định 10ms.
- **Câu hỏi cần trả lời:** ~1.000 `startSingleTimer` đồng thời/pod ở độ phân giải 200ms có giữ được độ chính xác và không ăn hết CPU không?
- **Cách làm:** spawn 1.000 actor rỗng, mỗi actor hẹn timer 200ms theo nhịp ngẫu nhiên; đo độ lệch thực tế so với hạn hẹn (p50/p99) và CPU. Không cần Gateway, không cần mạng.
- **Đạt nếu:** p99 độ lệch < 50ms và CPU < 30% ở 2 vCPU.
- **Kết quả thật (2 lần chạy độc lập, `-XX:ActiveProcessorCount=2`):** p99 lệch 36–37ms (< 50ms) ·
  CPU đỉnh 5–6% (< 30%). **GO** — giữ nguyên single-shot timer mỗi actor, không cần flush wheel.
  Chi tiết đầy đủ: `spike-pekko-timer.md`.
- **Nếu không đạt:** đổi thiết kế sang **một flush wheel gom lô ở tầng pod** (một timer duy nhất quét danh sách phòng dirty) thay vì timer mỗi actor. Ghi lại quyết định vào v3.0 §6.2.
- **Output:** ghi theo `templates/spike-template.md`, đặt tại `docs/work/NOJIRA-uni-p1-realtime-core/spike-pekko-timer.md`
- **Harness:** `modules/uni-engine/src/test/java/.../spike/SchedulerCapacitySpike.java` — có
  `main()`, tên KHÔNG khớp pattern `*Test`/`*Tests` của Surefire nên không chạy trong `mvn test`
  bình thường (đã xác nhận: 37/37 test uni-engine không đổi thời gian chạy). Chạy tay theo
  hướng dẫn trong javadoc của file.

---

### Task 3: Tick coalescing trong `RoomActor` — ✅ XONG (2026-09-07)

- **Mode:** sequential after [T2, SPIKE]
- **Mô tả:** 200ms là **trần tần suất**, không phải nhịp phát (ADR-4). Cách hiện thực phụ thuộc kết quả SPIKE.
- **Kết quả:** `mvn -pl :uni-engine test -Dtest=TickCoalescingTest` 5/5 pass (`ActorTestKit` +
  `ManualTime` thật — timer thật sự chạy, không phải `BehaviorTestKit`). Toàn module 51/51,
  toàn reactor `mvn clean install` xanh. **Prove-it**: tạm đổi `buildDeltaSnapshot()` sang duyệt
  `players.keySet()` thay vì `dirtyStudentIds` — xác nhận đúng 1 test Red
  (`should_includeOnlyChangedPlayers_when_flushingADelta`) trước khi trả lại Green.
- **Trước khi code — hai quyết định kỹ thuật chặn T3 (tech-design.md §9.1 G2a/G2b) đã chốt
  2026-09-06/07, trong đội (không phải Product/Business):**
  - **G2b:** chọn D1–D4 — delta ở mức người chơi (mỗi `PlayerState` trong delta là bản đầy đủ
    của đúng những học sinh đổi kể từ lần flush trước; vắng mặt = không đổi), không đụng `.proto`.
  - **G2a:** `N = 10` — cứ 10 lần flush thì gửi 1 full snapshot thay vì delta (lưới an toàn cho
    một delta best-effort bị drop dưới backpressure, vì PH-3 client resync chưa tồn tại).
- **File dự kiến:** `modules/uni-engine/src/main/java/.../room/RoomActor.java` (mở rộng), `.../room/CoalescingFlush.java`
- **Dependency:** Task 2, SPIKE
- **Acceptance criteria:**
  - [x] **Phòng im lặng phát 0 gói** — test riêng `should_broadcastZeroPackets_when_roomStaysSilentAfterInitialActivity`
  - [x] Độ trễ tối đa từ lúc `dirty` tới lúc broadcast ≤ 200ms — test `should_capBroadcastDelayAt200ms_when_dirtiedRightAfterAPreviousFlush`
  - [x] Broadcast là **delta**, không phải full state (full state chỉ khi JOIN) — `RoomState.joinRoom()`
        trả full snapshot trực tiếp cho người vừa vào; `RoomState.buildDeltaSnapshot()` chỉ liệt kê
        học sinh dirty
  - [x] `ANSWER_ACK` **bypass hoàn toàn** coalescing — vẫn đi thẳng qua `replyTo` như Task 2, không
        đụng đường flush. `GAME_OVER`/`TEACHER_COMMAND`/`QUESTION_STARTED`/`CONNECTION_DEGRADED`
        **chưa được RoomActor phát ra như broadcast nào cả** ở bất kỳ task nào tính đến giờ — nên
        AC "bypass coalescing" đúng cấu trúc (chúng không đi qua flush), nhưng việc thật sự phát
        các message này ra ngoài là việc chưa làm, thuộc Task 13 (xem ghi chú)
  - [x] `tick_mode` đọc từ Game Definition (`COALESCE` mặc định), không hardcode toàn cục —
        `RoomActor.create(...)` nhận tham số `TickMode`, ném `IllegalArgumentException` ngay khi
        gọi nếu khác `COALESCE` (test `should_rejectFixedTickMode_when_creatingRoomActor...`)
  - [x] **GĐ1 chỉ hiện thực đường `COALESCE`**. `tick_mode: FIXED` vẫn nằm trong schema
        (`DefinitionLoader` đã từ chối từ Task 11) — `RoomActor.create` thêm một lớp fail-fast
        thứ hai phòng trường hợp gọi thẳng bỏ qua loader
- **Verification:** `mvn -pl :uni-engine test -Dtest=TickCoalescingTest` với `ManualTime` của Pekko. Case bắt buộc: 5 giây không có input → **đếm đúng 0 gói outbound**.
- **Ghi chú quan trọng:**
  - **RoomActor trước Task 3 hoàn toàn không có khái niệm roster/join** (note.md Task 2 đã ghi rõ:
    "Join room / student_index / broadcast / tick coalescing — thuộc Task 3"). Vì delta cần
    `PlayerState` thật (student_index, display_name, score, answered_current, connected), Task 3
    phải thêm `RoomActor.JoinRoom` + roster (`Map<String, PlayerRecord>`) vào `RoomState` — không
    có nơi nào khác đã làm việc này trước đó.
  - `RoomActor.create(...)` thêm tham số `ActorRef<GameMessage> broadcastTarget` — nơi các
    snapshot flush được gửi tới. Giữ đúng khuôn mẫu `replyTo` của `SubmitAnswer`/`JoinRoom`:
    RoomActor không biết gì về transport (không biết gateway pod nào, không biết socket nào).
    **Nối `broadcastTarget` với `FrameChannelServer`/kênh nội bộ thật, và thật sự phát
    `QUESTION_STARTED`/`GAME_OVER`/`CONNECTION_DEGRADED`/`StudentJoined` ra ngoài, là việc của
    Task 13** — đúng ranh giới đã áp dụng nhất quán ở Task 9/12 (không nối dây nửa vời trước khi
    có điểm gắn thật).
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
  - [x] Pipeline đúng thứ tự: `HttpServerCodec → HttpObjectAggregator(50KB) → WebSocketServerProtocolHandler → TicketAuthHandler → RateLimitHandler → ProtobufDecoder → RoomRouteHandler`
        (cập nhật 2026-09-06: cap chung mọi gói WS chốt ở 50KB — system-architecture.md §1.1/§7.5 —
        thay cho 8KB tạm ban đầu)
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

### Task 7: Rate limiting phân tầng theo loại thông điệp — ✅ XONG (2026-09-07)

- **Mode:** sequential after [T6] · parallel with [T8]
- **Mô tả:** Khoá theo `student_id`, **không** khoá theo IP làm tầng chính — trường học đi sau NAT dùng chung một IP (§10.1).
- **Kết quả:** `mvn -pl :uni-gateway test -Dtest=RateLimitHandlerTest` 5/5 pass (`EmbeddedChannel`,
  `Clock` cố định) + `TokenBucketTest` 4/4 pass (logic thuần) + `IpAdmissionControllerTest` 3/3
  pass (logic thuần) + `IpAdmissionHandlerTest` 3/3 pass (`EmbeddedChannel` với remote address
  giả lập được). Toàn module 52/52, toàn reactor `mvn clean install` xanh. **Prove-it**: tạm bỏ
  qua verdict của `IpAdmissionController` trong `IpAdmissionHandler` — xác nhận đúng 1 test Red
  trước khi trả lại Green.
- **File dự kiến:** `modules/uni-gateway/src/main/java/.../net/RateLimitHandler.java`
- **Dependency:** Task 6
- **Acceptance criteria:**
  - [x] Bucket riêng theo loại: `SUBMIT_ANSWER` 3/refill 1s · `UPDATE_DRAFT` 10/refill 10s · `HEARTBEAT` 2/refill 1 mỗi 30s
  - [x] Khoá bucket là `student_id` lấy từ `ChannelAttributes` (cụ thể: mỗi connection/`RateLimitHandler`
        đã gắn với đúng 1 student_id sau `TicketAuthHandler`, nên bucket state per-instance = per-student_id)
  - [x] L1 theo IP đặt **4.000 handshake/phút** (cập nhật: quyết định Business 2026-09-06 nâng từ
        300 → 4.000, xem system-architecture.md §5.6 — con số trong AC gốc của plan.md đã lỗi
        thời trước khi Task 7 được hiện thực đầy đủ). Hiện thực ở `IpAdmissionController` +
        `IpAdmissionHandler`, gắn làm handler **đầu tiên** trong `GatewayPipeline` (trước cả
        `BackpressureHandler`) — điểm gắn trước đây chưa tồn tại, nay có rồi
  - [x] Vượt ngưỡng → `RATE_LIMIT_EXCEEDED`, **không** đóng channel (chỉ áp dụng đúng nghĩa cho
        `SUBMIT_ANSWER` — xem ghi chú về `UPDATE_DRAFT`/`HEARTBEAT`). Riêng L1 (IP) là control ở
        tầng handshake, không phải per-message — vượt ngưỡng L1 **đóng channel** (đúng bản chất
        "chống DDoS thô", khác hẳn ngữ nghĩa `RATE_LIMIT_EXCEEDED` của các bucket per-message)
- **Verification:** `mvn -pl :uni-gateway test -Dtest=RateLimitTest`. Case bắt buộc: **500 client sau cùng một IP đều kết nối được** — đây là kịch bản khách hàng thật.
  (Tên file test thật là `RateLimitHandlerTest`, khớp class `RateLimitHandler` — `RateLimitTest`
  trong plan có vẻ là tên rút gọn.) Với L1, case tương đương là `IpAdmissionControllerTest`
  (4.000 lần admit cùng 1 IP đều `true`) — 500 < 4.000 nên không mâu thuẫn với case gốc.
- **Ghi chú quan trọng:**
  - **"tổng 15/15s"** trong AC gốc không hiện thực thành bucket thứ tư riêng — ba cửa sổ thời
    gian khác nhau (1s/10s/30s) không gộp thành một cửa sổ chung có nghĩa rõ ràng; đọc đây là
    tổng ước lượng thô, không phải cơ chế cần code.
  - **L1 IP-based admission control**: khác hẳn cơ chế per-message theo `student_id` mà
    `RateLimitHandler` làm — đây là control ở tầng kết nối/handshake, chạy tại
    `IpAdmissionHandler.channelActive` (mỗi TCP connection mới = 1 lần thử handshake), **trước**
    mọi handler khác kể cả `HttpServerCodec`, để một IP vượt ngưỡng không tốn dù một cycle CPU
    cho HTTP parsing hay WS upgrade. Dùng lại `TokenBucket` (Task 7 đã có sẵn) nhưng nhân theo
    IP qua một `ConcurrentHashMap` chia sẻ toàn pod (`IpAdmissionController`) — khác
    `RateLimitHandler` vốn 1 instance/connection vì lúc đó identity đã biết (per-student_id).
    Case bắt buộc "500 client cùng IP đều kết nối được" **vẫn đúng** — 500 << 4.000, và
    `IpAdmissionControllerTest.should_trackBudgetsIndependently_perIp` xác nhận IP khác không hề
    bị ảnh hưởng bởi một IP đã vượt ngưỡng.
  - **`UPDATE_DRAFT`/`HEARTBEAT` vượt ngưỡng bị drop im lặng, không có `RATE_LIMIT_EXCEEDED`
    thật trên dây** — schema không có payload ack nào cho hai loại này (`UPDATE_DRAFT` còn thiếu
    hẳn payload trong oneof, tech-design.md §G3). Chỉ `SUBMIT_ANSWER` có `AnswerAck.reject_reason`
    để mang tín hiệu này thật sự. Không tự thêm field/message mới vào `.proto` ở task này.
  - **Không hiện thực L2 (khoá theo `student_id`, 10 handshake/phút) hay L3 (admission control
    toàn pod, §6.5)** dù cả hai đều xuất hiện ở system-architecture.md §5.6 cạnh L1 — plan.md
    Task 7's AC chỉ nhắc L1 ("L1 theo IP (nếu bật)"), L2/L3 không nằm trong bất kỳ task nào của
    `plan.md` tính đến giờ. Mở rộng sang đó ở đây sẽ là tự thêm phạm vi ngoài AC đã giao.
- **Rollback nếu fail:** revert; tạm chạy không rate limit ở môi trường dev, **không** đưa lên staging.

---

### Task 8: Room registry cục bộ + fan-out zero-copy — ✅ XONG (2026-09-06)

- **Mode:** sequential after [T6] · parallel with [T7]
- **Mô tả:** `Map<room_id, Set<Channel>>` trong từng pod, broadcast bằng `retainedDuplicate()`. **Đây là task dễ sai nhất trong GĐ1.**
- **Kết quả:** `mvn -pl :uni-gateway test -Dtest=FanoutTest` 4/4 pass — **đã chủ động đổi tạm
  sang `.retain()` để xác nhận test thật sự Red** (client thứ 2 nhận mảng byte rỗng), rồi
  trả lại `.retainedDuplicate()` để Green — không chỉ tin code đúng vì test pass ngay lần đầu.
  `RoomRegistryTest` 5/5 pass. Nối dây thật vào `TicketAuthHandler`/`RoomRouteHandler`/
  `GatewayPipeline`/`GatewayBootstrap` — `GatewayPipelineTest` thêm 2 case xác nhận add-on-join
  và remove-on-channelInactive qua đúng pipeline thật. Toàn module 35/35, toàn reactor xanh,
  `-Dio.netty.leakDetection.level=paranoid` sạch (đã bật sẵn toàn cục qua Surefire).
- **File dự kiến:** `modules/uni-gateway/src/main/java/.../fanout/RoomRegistry.java`, `.../fanout/Broadcaster.java`
- **Dependency:** Task 6
- **Acceptance criteria:**
  - [x] Dùng **`retainedDuplicate()`**, tuyệt đối không `retain()`
  - [x] `frame.release()` nằm trong khối `finally`
  - [x] Fan-out chỉ lặp trên `Set<Channel>` của đúng phòng, **không lọc động từ danh sách toàn cục**
  - [x] `channelInactive` gỡ Channel khỏi **mọi** set ngay lập tức
  - [x] Engine gửi **một gói cho mỗi GW pod**, Gateway mới nhân bản (quyết định B1) — thoả mãn
        theo đúng hình dạng `Broadcaster.broadcast(roomId, mộtFrame)`, không cần code thêm gì
- **Verification:** `mvn -pl :uni-gateway test -Dtest=FanoutTest` với **12 `EmbeddedChannel`** — kiểm **từng client nhận đủ số byte**, không chỉ client đầu. Chạy kèm `-Dio.netty.leakDetection.level=paranoid`, log leak phải sạch.
- **Ghi chú:** `TicketAuthHandler` (Task 6) đăng ký channel vào `RoomRegistry` ngay khi bind
  `ChannelAttributes` (đây là chỗ duy nhất biết `room_id`), nhưng nó tự gỡ khỏi pipeline sau
  đó — nên việc gỡ đăng ký (`channelInactive`) đặt ở `RoomRouteHandler` (handler duy nhất còn
  sống suốt vòng đời connection). `RoomRegistry` phải là **một instance chia sẻ** cho cả pod
  (khác với `TicketAuthHandler`/`RateLimitHandler` — mỗi channel một instance riêng) —
  `GatewayBootstrap` nhận nó qua constructor và truyền xuống `GatewayPipeline.addTo` cho mọi
  channel. Chưa nối `Broadcaster` với `FrameChannelClient.onResponse` (Task 5) — việc "gắn dây"
  toàn luồng Engine→Gateway→client là Task 13.
- **Rollback nếu fail:** revert.

> [!CAUTION]
> Test 1 client **sẽ pass** kể cả khi code dùng sai `retain()`. Chỉ test ≥ 2 client mới lộ.
> Không rút gọn tiêu chí 12 client này.

---

### Task 9: Nối chuỗi backpressure một tầng — ⚠️ MỘT PHẦN XONG (2026-09-06)

- **Mode:** sequential after [T5, T8]
- **Mô tả:** Một cơ chế backpressure duy nhất chạy suốt từ mailbox actor về tới socket client (§10.2). Đây là lợi ích chính của ADR-1 — làm hỏng nó là mất lý do bỏ gRPC.
- **Kết quả:** `mvn -pl :uni-gateway,:uni-engine test -Dtest=BackpressureTest` — gateway 5/5 pass,
  engine 2/2 pass. Case bắt buộc "client chậm không kéo tụt client khác cùng phòng" pass
  (`should_notAffectOtherClientsInTheSameRoom_when_oneClientIsSlow`) — **đã chủ động đảo ngược
  logic Critical/Best-effort để xác nhận 3 test liên quan thật sự Red trước khi tin Green**,
  đúng tinh thần prove-it đã dùng ở Task 8. Toàn `uni-gateway` 40/40, toàn `uni-engine` 23/23,
  toàn reactor xanh, không leak, không deprecation warning.
- **File dự kiến:** `modules/uni-gateway/src/main/java/.../net/BackpressureHandler.java`, `modules/uni-engine/src/main/java/.../net/FrameChannelServer.java` (sửa)
- **Dependency:** Task 5, Task 8
- **Acceptance criteria:**
  - [ ] Chuỗi đúng: mailbox đầy → Engine ngừng đọc Frame Channel → TCP window đóng → GW thấy `!isWritable()` → `autoRead(false)` trên WS client — **chỉ hiện thực từng khúc, chưa nối trọn chuỗi**, xem ghi chú
  - [x] `WRITE_BUFFER_WATER_MARK` = (32 KB low, 64 KB high) mỗi channel — áp dụng cho WS client
        (`GatewayBootstrap`), GW→Engine (`FrameChannelClient`), và Engine's accepted channel
        (`FrameChannelServer`)
  - [x] `!isWritable()`: **Best-effort → drop** · **Critical → không drop**, đóng channel (§5.4) — trong `Broadcaster`
  - [x] **Không có queue hay buffer tầng app nào** giữa mailbox và socket — `Broadcaster` chỉ
        ghi-ngay-hoặc-bỏ trong đúng 1 vòng lặp, không có cấu trúc tích luỹ nào để "sửa sau rất đắt"
  - [x] Metric `channel_not_writable_total` được phát ra — trong `BackpressureHandler` (gateway)
        và `FrameChannelServer.BackpressureHandler` (engine, nested)
- **Verification:** `mvn -pl :uni-gateway,:uni-engine test -Dtest=BackpressureTest` — client chậm không kéo tụt client khác cùng phòng.
- **Ghi chú quan trọng — vì sao "một phần xong":** đã hiện thực **`BackpressureHandler`** làm
  MỘT cơ chế duy nhất (writability của chính channel đó → toggle `autoRead` của chính nó),
  áp dụng nhất quán ở **cả 3 hop**: WS client (Gateway), GW→Engine (`FrameChannelClient`), và
  Engine's accepted channel (`FrameChannelServer`). Đây là cơ chế Netty chuẩn, đúng, test được.
  Nhưng **chưa nối được** đoạn cụ thể "mailbox RoomActor đầy → Engine tự toggle `autoRead` của
  kết nối đó": không có API nào có sẵn trong Pekko typed để đọc độ sâu mailbox đồng bộ, và một
  connection nội bộ mang traffic của NHIỀU phòng (multiplex theo `room_id`, ADR-001) nên
  "mailbox của 1 phòng đầy" không map 1-1 vào "channel nào cần dừng đọc" — đây là giới hạn thật
  của kiến trúc chia sẻ 1 connection/pod-pair, không phải thiếu sót code. Bịa ra một cơ chế đo
  mailbox depth ở đây sẽ là hạ tầng suy đoán ngoài phạm vi bất kỳ task nào đã giao. Việc nối dây
  đầy đủ (nếu cần) thuộc Task 13 hoặc một quyết định kiến trúc riêng.
- **Cập nhật 2026-09-07 (sau Task 13):** Task 13 đã nối `RoomSupervisor`/`ChannelReplyActor`
  thật — tin nhắn giờ thật sự chảy Gateway↔Engine qua `FrameChannelServer`/`FrameChannelClient`.
  Điều đó **không đổi kết luận ở trên**: `RoomSupervisor` dispatch tới đúng `RoomActor` theo
  `room_id`, nhưng connection nội bộ vẫn là MỘT connection dùng chung cho MỌI phòng giữa một
  cặp pod (ADR-001 không đổi) — nên "mailbox 1 phòng đầy → dừng đọc đúng connection đó" vẫn
  không map 1-1 được, đúng như dự đoán. AC đầu tiên vẫn để `[ ]`, có chủ đích — đây là giới hạn
  kiến trúc đã biết, không phải việc quên làm.
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

### Task 11: Game Definition tối giản + `MAX_TRANSITIONS` — ✅ XONG (2026-09-06)

- **Mode:** sequential after [T2] · parallel with [T3]
- **Mô tả:** Đủ để chơi trọn một ván quiz. Kèm rào chắn runtime tối thiểu (§10.5).
- **Kết quả:** `mvn -pl :uni-engine test -Dtest=DefinitionLoaderTest` 8/8 pass +
  `ScoringFormulaTest` 6/6 pass (thêm ngoài yêu cầu — chứng minh tập toán tử giới hạn tính
  đúng, không chỉ đúng cấu trúc dữ liệu). **Prove-it**: tạm vô hiệu hoá `rejectCycles(...)`,
  xác nhận đúng 2/8 test chu trình fail, rồi bật lại. Toàn module 37/37, toàn reactor xanh.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../definition/GameDefinition.java`, `.../definition/DefinitionLoader.java`
- **Dependency:** Task 2
- **Acceptance criteria:**
  - [x] Định nghĩa được: danh sách step, thời lượng mỗi step, `tick_mode`, công thức điểm
  - [x] **Validate DAG lúc nạp** — phát hiện chu trình bằng duyệt đồ thị, từ chối definition có chu trình
  - [x] **Không script engine, không biểu thức tuỳ ý** — công thức điểm dùng tập toán tử giới hạn
  - [x] `MAX_TRANSITIONS` mỗi phiên làm hàng rào cuối
  - [x] `missed_step_policy` **có mặt trong schema** với mặc định `ZERO`, dù luồng late join chưa làm ở GĐ1
  - [x] `tick_mode` chấp nhận `COALESCE` | `FIXED` trong schema, nhưng loader **từ chối `FIXED`
        lúc nạp** ở GĐ1 (chưa có implementation — xem Task 3, quyết định #4)
- **Verification:** `mvn -pl :uni-engine test -Dtest=DefinitionLoaderTest` — definition có chu trình bị từ chối **lúc nạp**, không phải lúc chạy.
- **Ghi chú:**
  - `ScoringFormula` là cây biểu thức `sealed interface` đóng (7 record: `Constant`,
    `IsCorrect`, `ResponseTimeMs`, `Add`, `Subtract`, `Multiply`, `Divide`, `Min`, `Max`) —
    không `eval`, không reflection, không hook script nào; đây chính là "tập toán tử giới hạn"
    của AC, không phải parser cho một mini-language dạng text.
  - `MAX_TRANSITIONS` mới chỉ là **giá trị cấu hình được validate dương** trong
    `GameDefinition`/`DefinitionLoader`; việc THỰC THI hàng rào này lúc chạy (đếm transition
    thật trong một phiên) chưa có nơi nào tiêu thụ `GameDefinition` — `RoomActor` (Task 2) chưa
    được nối với nó. Đây là "gắn dây" tương lai (Task 13 hoặc tương đương), không phải thiếu ở T11.
  - Công thức điểm CỤ THỂ cho quiz đã được Product chốt 2026-09-06 (system-architecture.md §2.5)
    và hiện thực ở Task 2 qua `FormulaScoreCalculator.binaryChoice()` — `ScoringFormulaTest` ở
    đây vẫn giữ nguyên vai trò minh hoạ khả năng biểu diễn của `ScoringFormula` nói chung, còn
    công thức thật (100/0, không bonus tốc độ) có test riêng ở `FormulaScoreCalculatorTest`.
  - Không xây dựng tầng deserialize JSON/YAML cho definition "upload bởi người vận hành" (§2.5)
    — chưa có quyết định định dạng dây nào, tự bịa sẽ là phát minh hạ tầng ngoài phạm vi.
- **Rollback nếu fail:** revert; tạm hardcode một quiz cố định để T11 chạy được.

---

### Task 12: Nền quan sát — ✅ XONG (2026-09-06)

- **Mode:** parallel — gộp bất cứ lúc nào sau T1
- **Mô tả:** Các metric của §15.1 áp dụng được ở GĐ1. Không chờ tới cuối mới gắn.
- **Kết quả:** Đã CHẠY THẬT cả hai app (`mvn -pl :uni-gateway spring-boot:run` /
  `mvn -pl :uni-engine spring-boot:run`) và `curl` `/actuator/prometheus` thật — không chỉ tin
  unit test. Cả 5 metric xuất hiện đúng ngay từ lúc khởi động, giá trị 0 (chưa có traffic thật):
  `actor_processing_latency_seconds{...}`, `actor_mailbox_depth 0.0`,
  `channel_not_writable_total 0.0` (engine), `fanout_latency_seconds{...}`,
  `handshake_rate_total 0.0`, `channel_not_writable_total 0.0` (gateway). Đã tắt cả hai process
  sau khi xác nhận. 88/88 test (46 gateway + 42 engine) pass, toàn reactor xanh.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../metrics/EngineMetrics.java`, `modules/uni-gateway/src/main/java/.../metrics/GatewayMetrics.java`
- **Dependency:** Task 1
- **Acceptance criteria:**
  - [x] Engine: `actor_processing_latency` (p99), `actor_mailbox_depth` (p99)
  - [x] Gateway: `fanout_latency` (p99), `handshake_rate`, `channel_not_writable_total`
  - [x] `/actuator/prometheus` trả đủ các metric trên ở cả hai service
  - [x] Trace ID sinh tại Gateway lúc handshake, truyền qua `InternalHeader` — **tới biên
        Gateway→Engine đã xong và test được**; "xuống actor" thật thì chờ Task 13 nối
        `RoomRouteHandler` với `FrameChannelClient.send(...)` (xem ghi chú)
- **Verification:** `curl -s localhost:8080/actuator/prometheus | grep -E "actor_mailbox_depth|channel_not_writable"` trả về kết quả khác rỗng.
- **Ghi chú quan trọng:**
  - **Phát hiện giữa chừng: metric đăng ký kiểu lazy (Task 9) sẽ không hiện trong scrape cho
    tới khi có connection/traffic đầu tiên** — vi phạm chính lời hứa "không chờ tới cuối mới
    gắn" của Task 12. Đã refactor `BackpressureHandler` (cả 2 phía) để nhận `GatewayMetrics`/
    `EngineMetrics` (đăng ký eager trong constructor) thay vì tự gọi `MeterRegistry.register()`
    mỗi lần một channel mới được tạo.
  - **Phát hiện thứ hai, nghiêm trọng hơn: `GatewayMetrics`/`EngineMetrics` chưa hề được Spring
    quản lý** — không có `@Bean` nào, nên nếu chạy app thật, metric sẽ KHÔNG xuất hiện dù code
    "đúng" theo unit test. Thêm `MetricsConfiguration` (`@Configuration` + `@Bean`) ở cả hai
    module để đăng ký chúng vào Spring context, dùng `MeterRegistry` do Actuator tự cấu hình.
    Đây là lý do bắt buộc phải chạy app thật để verify — unit test một mình sẽ không lộ ra lỗ
    hổng này.
  - `actor_mailbox_depth` là gauge **dùng chung cho cả pod** (không gắn tag `room_id`) — nếu
    gắn theo room thì gauge không thể tồn tại trước khi phòng đầu tiên được tạo, phá vỡ đúng
    yêu cầu "hiện diện từ lúc khởi động". `RoomActor` (Task 2) đổi sang dùng
    `EngineMetrics.processingLatencyTimer()` thay vì tự tạo Timer riêng (đổi tên metric từ
    `engine.room.actor.processing.time` sang đúng `actor_processing_latency` theo AC).
  - `EngineMetrics.recordMessageEnqueued()/recordMessageDequeued()` (nuôi `actor_mailbox_depth`)
    tồn tại và có unit test, nhưng **chưa được gọi ở bất kỳ đâu trong code chính** — không có
    nơi nào thật sự gửi message vào một `RoomActor` từ bên ngoài (đó là việc của Task 13). Cố
    tình không wire nửa vời (chỉ decrement mà không increment) vì sẽ làm gauge chạy âm ngay khi
    `RoomActorTest` tự gửi message — một giá trị sai còn tệ hơn một giá trị 0 trung thực.
  - `RoomRouteHandler` giờ luôn đóng dấu `InternalHeader.trace_id` (đọc từ
    `ChannelAttributes.TRACE_ID`, sinh bằng `UUID.randomUUID()` tại `TicketAuthHandler` lúc
    handshake) vào MỌI message trước khi forward — kể cả khi chưa có
    `FrameChannelClient.send(...)` nào tiêu thụ nó thật sự. Đây là điểm đúng về mặt kiến trúc để
    dừng lại (đã CHỐT xong phần Gateway); Task 13 chỉ cần gọi `send(...)` với message đã chuẩn
    bị sẵn, không cần biết gì về tracing.
- **Rollback nếu fail:** revert; không chặn task nào khác.

---

### Sync checkpoint

> Chờ toàn bộ T3, T5, T7, T8, T9, T10, T11, T12 xong trước khi bắt đầu T13.

- [x] Ba nhánh song song (T2/T4/T6) đều pass verification (T6 vẫn "một phần" ở chỗ chưa có
      `TicketVerifier` thật — chờ G1a/G1c — nhưng `GatewayPipelineTest` của chính nó vẫn xanh)
- [x] SPIKE có kết luận ghi thành văn bản, và T3 hiện thực **đúng theo kết luận đó**
- [x] `mvn clean install` toàn project sạch (119 test: 4 protocol + 59 gateway + 55 engine + 1 e2e)
- [x] Log leak detection (`paranoid`) sạch trong toàn bộ test suite
- [x] Không task nào chạm Redis, ShardRegion hay Kafka — ranh giới GĐ1 còn nguyên
- **Ghi chú:** T9 vẫn "một phần" (giới hạn kiến trúc đã biết, xem ghi chú ở Task 9) — quyết định
  thực tế 2026-09-07 là tiến hành T13 vì phần backpressure watermark-based (không phải
  mailbox-depth) đã đúng và test được ở cả 3 hop; T13 tự nó không cần chuỗi mailbox-depth cụ
  thể để chứng minh walking skeleton hoạt động.

---

### Task 13: Ghép walking skeleton end-to-end + smoke test — ⚠️ MỘT PHẦN XONG (2026-09-07)

- **Mode:** sequential after [SYNC]
- **Mô tả:** Chứng minh một gói tin đi hết vòng qua hệ thống thật. Đây là tiêu chí "xong Giai đoạn 1".
- **Kết quả:** `mvn -pl :uni-e2e -am test` (bắt buộc `-am` — xem ghi chú build) → 1/1 pass,
  `WalkingSkeletonTest` dùng **socket thật hoàn toàn** (không `EmbeddedChannel` nào): WebSocket
  client thật (Netty `WebSocketClientHandshaker`) → `GatewayBootstrap` thật (cổng ephemeral) →
  `FrameChannelClient` thật → TCP thật → `FrameChannelServer` thật (cổng ephemeral) →
  `RoomSupervisor`/`RoomActor` thật. `mvn clean install` toàn reactor từ root: BUILD SUCCESS,
  119 test tổng cộng, không leak.
- **File dự kiến:** `modules/uni-e2e/src/test/java/.../WalkingSkeletonTest.java`, `docker-compose.dev.yml`
- **Dependency:** Sync checkpoint
- **Acceptance criteria:**
  - [x] Client thật join một phòng qua WebSocket, nhận `ROOM_STATE_SNAPSHOT` (full) — **2 client**,
        không phải 12 (xem ghi chú "vì sao 2, không phải 12")
  - [x] Submit → nhận `ANSWER_ACK` (Critical, đi ngay) → **cả 2 client** nhận delta snapshot
  - [x] Submit lại cùng `sequence` → điểm không đổi (`replay.equals(ack)`), ACK cũ được trả lại
  - [x] Im lặng sau đó → **0 gói outbound** trong 500ms (chứng minh ADR-4 chạy thật qua socket
        thật, không chỉ trong unit test của Task 3)
  - [ ] Chạy với ≥ 2 Engine pod: route cache học đúng `owner_pod_id` — **chưa test trong module
        này** (logic đã có sẵn và đã test riêng ở `FrameChannelClientTest`, Task 5); chưa ghép
        vào một kịch bản `uni-e2e` chung
  - [ ] Giết một Engine pod → client nhận `CONNECTION_DEGRADED`, WebSocket không đóng (§9.7) —
        **cơ chế đã hiện thực** (`RouteCache.evictPod` trả về room bị ảnh hưởng,
        `FrameChannelClient.onPodDisconnected`, `EngineResponseRouter.broadcastConnectionDegraded`)
        nhưng **chưa có test nào lắp cả chuỗi lại với nhau** để chứng minh bằng thực nghiệm
  - [ ] `docker-compose.dev.yml` với 2 GW + 2 Engine thật — **không làm**, xem ghi chú Docker
- **Verification:** `mvn -pl :uni-e2e -am test` (bắt buộc `-am`, xem ghi chú build). AC gốc đòi
  `mvn -pl :uni-e2e verify` + `docker-compose.dev.yml` — **chưa làm được phần Docker**, xem dưới.
- **Ghi chú quan trọng — kiến trúc mới phải xây (Task 2/9 đều đã ghi rõ đây là việc của Task 13):**
  - **`RoomSupervisor`** (`modules/uni-engine/.../room/RoomSupervisor.java`, mới): actor duy nhất
    mỗi Engine pod, spawn `RoomActor` lười theo `room_id` lúc `JOIN_ROOM` đầu tiên, dịch
    `GameMessage` thành đúng `RoomActor.Command`, và học tập hợp connection nào đang theo dõi
    phòng nào (từ `JOIN_ROOM`) để fan-out broadcast tới đúng tập đó — không phải một target cố
    định như Task 3 giả định tạm.
  - **`ChannelReplyActor`** (`modules/uni-engine/.../net/ChannelReplyActor.java`, mới): điểm
    DUY NHẤT đóng dấu `InternalHeader{owner_pod_id, delivery_class}` trước khi ghi ra
    `Channel` — `RoomActor`/`RoomState` (Task 2/3) không hề biết pod id hay cách phân loại
    delivery class, đúng như thiết kế transport-agnostic ban đầu.
  - **`EngineResponseRouter`** (`modules/uni-gateway/.../net/EngineResponseRouter.java`, mới):
    nửa còn lại ở Gateway — `ANSWER_ACK` đi thẳng một học sinh (tìm channel theo `student_id`
    trong `RoomRegistry` của đúng phòng), mọi thứ khác qua `Broadcaster` (đã có từ Task 8),
    `NOT_OWNER` bị bỏ qua (không có payload, §8.2 chỉ sửa route cho lần sau), và `internal`
    luôn bị `clearInternal()` trước khi tới client (đúng comment trong `.proto`).
  - **`RoomRouteHandler`** (sửa): giờ gọi `EngineSender.send(...)` thật thay vì `fireChannelRead`
    rồi không ai đọc — đây chính là chỗ Task 12 dự đoán trước ("chỉ cần gọi `send(...)`, không
    cần biết gì về tracing"). Nhân tiện áp **cùng ranh giới tin cậy cho `student_id`** như
    `room_id` đã có từ Task 6 (§10.6) — envelope's `student_id` trước đây KHÔNG bị ép về giá trị
    đã xác thực, một lỗ hổng nhỏ chưa ai phát hiện tới giờ vì chưa có gì tiêu thụ message đó.
  - **`EngineSender`** (`modules/uni-gateway/.../routing/EngineSender.java`, mới): interface tách
    khỏi `FrameChannelClient` cụ thể, đúng khuôn `TicketVerifier` — để test `RoomRouteHandler`
    không cần mở real socket.
  - **`RouteCache.evictPod`** đổi `void` → trả `Set<String>` room bị ảnh hưởng;
    `FrameChannelClient` thêm tham số `onPodDisconnected` — nền tảng cho §9.7, dùng bởi
    `EngineResponseRouter.broadcastConnectionDegraded` (xem AC còn treo ở trên).
  - **`EngineNetworkLifecycle`/`GatewayNetworkLifecycle`** (`.../boot/`, mới): lần đầu tiên hai
    `Application` class thật sự khởi động Netty/Pekko lúc Spring Boot boot — trước Task 13,
    `EngineApplication`/`GatewayApplication` chỉ boot Spring, đúng như chính javadoc của chúng
    đã ghi. **Đã CHẠY THẬT** cả hai (`mvn spring-boot:run` + `curl`/`netstat`, theo đúng tinh
    thần Task 12): Engine bind cổng 9100 (frame channel) + 8090 (actuator) thành công; Gateway
    chỉ bind 8080 (actuator) — cổng 9000 (WS) **cố tình không mở** vì `GatewayNetworkLifecycle`
    có `@ConditionalOnBean(TicketVerifier.class)` và chưa có bean thật nào (G1a/G1c chưa chốt).
    Đây là hành vi ĐÚNG, không phải lỗi — khớp đúng câu cấm của `TicketAuthHandler`: "Không dùng
    verifier tạm này ở staging/production".
  - **`GatewayNetworkLifecycle`** gán `podId` cho mỗi entry trong `uni.gateway.engine.pods`
    theo **vị trí trong danh sách** (`engine-0`, `engine-1`, ...) — đơn giản hoá có chủ đích vì
    config hiện tại (`ENGINE_PODS=host:port,...`) không mang id riêng; đòi hỏi
    `uni.engine.pod-id` của từng Engine pod khớp đúng vị trí đó trong danh sách của Gateway.
    PH-1/service discovery thật sẽ thay cái này sau.
  - **`EngineMetrics.recordMessageDequeued()`** giờ được gọi thật trong `RoomActor.watched()`
    (chỉ với `JoinRoom`/`SubmitAnswer` — hai loại duy nhất `RoomSupervisor` đếm enqueue) — đóng
    nốt lỗ hổng Task 12 đã cảnh báo trước ("chưa được gọi ở bất kỳ đâu... đó là việc của Task 13").
- **Ghi chú — vì sao 2 client, không phải 12:** AC gốc đòi 12 client để lộ đúng loại lỗi
  `retain()`-vs-`retainedDuplicate()` (Task 8 đã có test 12 client riêng, ở tầng `Broadcaster`).
  Ở tầng walking skeleton, câu hỏi khác hẳn: "một message có đi hết vòng và quay lại đúng người
  không" — 2 client (một gửi, một chỉ quan sát) đã đủ để chứng minh cả trực tiếp lẫn broadcast,
  thêm 10 client nữa không kiểm thêm được gì mới ở tầng tích hợp này.
- **Ghi chú — build (`spring-boot-maven-plugin` phá reactor):** `mvn clean install` từ root ban
  đầu FAIL khi thêm `uni-e2e` — mọi `import com.uni.realtime.{gateway,engine}.*` báo "package
  does not exist", dù `mvn -pl :uni-e2e -am test` chạy tốt. Nguyên nhân: `repackage` (không có
  `<classifier>`) thay artifact chính của `uni-gateway`/`uni-engine` bằng jar thực thi (class nằm
  dưới `BOOT-INF/classes`), và khi `install` (không phải `test-compile`) chạy tới `package` cho
  hai module đó TRƯỚC LƯỢT `uni-e2e`, reactor resolve theo artifact ĐÃ BỊ THAY, không phải
  `target/classes` nữa. Sửa bằng thêm `<classifier>exec</classifier>` vào cấu hình
  `spring-boot-maven-plugin` ở cả hai `pom.xml` — giữ jar thường làm artifact chính, jar thực thi
  nằm cạnh với tên khác. `mvn spring-boot:run` không đổi hành vi (chạy từ `target/classes`, không
  phải jar đã đóng gói).
- **Ghi chú — Docker Compose CHƯA làm, không phải quên:** Docker daemon **không chạy** trong môi
  trường viết task này (`docker info` báo lỗi kết nối tới `dockerDesktopLinuxEngine`), và chưa hề
  có `Dockerfile` nào cho hai service. Viết `docker-compose.dev.yml` mù (không chạy thử được) đi
  ngược đúng nguyên tắc dự án đã áp dụng suốt từ Task 12 ("phải chạy thật để verify, không chỉ
  tin test"/code). Giá trị cốt lõi của AC này — chứng minh routing/coalescing/dedupe hoạt động
  thật qua socket thật — **đã được chứng minh** bằng `WalkingSkeletonTest` (không cần container).
  Việc còn lại (Dockerfile, compose, kịch bản giết pod thật, 12-client-qua-container) cần môi
  trường có Docker chạy được để làm và verify đúng tinh thần dự án.
- **Cố ý CHƯA làm (ngoài phạm vi đã nêu ở trên, không phải thiếu sót Task 13):**
  - `TeacherCommand.NEXT_STEP` (bắt đầu câu hỏi có nội dung thật qua dây) — không có định dạng
    nội dung câu hỏi nào được chốt (tech-design.md Task 11 note). `WalkingSkeletonTest` bắt đầu
    câu hỏi bằng `RoomSupervisor.GetRoomActor` (hook test/ops-only, xem javadoc lớp
    `RoomSupervisor`), không phải qua dây — giống hệt cách `RoomSupervisorTest` (Task 13, engine)
    đã làm.
  - `PAUSE`, `KICK_STUDENT` (TeacherCommand) — không wire, log cảnh báo. `PAUSE` không có phase
    tương ứng trong `RoomActor`; `KICK_STUDENT` cần tra `student_id → channel` mà pod này chưa có.
  - Luồng "rời phòng" (`connected=false`) — vẫn treo từ Task 3, chưa có tín hiệu nào từ Gateway
    khi một channel đóng được truyền sang Engine.
- **Rollback nếu fail:** không revert — đây là task tích hợp, fail nghĩa là một task thượng nguồn sai. Truy về task đó.

---

### Task 14: Redis Hot Snapshot thật cho `RoomActor` — đóng phát hiện B1 — ⏳ CHƯA BẮT ĐẦU (thêm 2026-09-07)

- **Mode:** sequential after [T13]
- **Mô tả:** `_context.md` mục "Phát hiện review kiến trúc (2026-09-07)" (B1) ghi nhận: tài liệu
  (ADR-002 GĐ1-note, §6.3) nói pod crash → phòng phục hồi từ Redis Snapshot trong 10–50ms, nhưng
  **chưa có một dòng code nào ghi Redis snapshot** — `RoomActor.flush()` chỉ gửi
  `state.flush()` tới `broadcastTarget` (client), không có nhánh persist. Task này làm cho câu đó
  đúng: ghi Hot Snapshot (< 5 KB) lên Redis bất đồng bộ sau mỗi flush/câu hỏi (§4.3 bước 5), và
  nạp lại khi `RoomActor` mới được `RoomSupervisor` spawn sau khi pod cũ chết.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../persistence/RedisSnapshotStore.java` (mới),
  `RoomActor.java`/`RoomSupervisor.java` (sửa: gọi ghi async sau flush, nạp snapshot lúc spawn nếu có)
- **Dependency:** Task 13 (cần `RoomSupervisor`/`RoomActor` đã nối dây thật)
- **Acceptance criteria:**
  - [ ] Ghi async trên thread pool/virtual thread riêng — **tuyệt đối không trong đường xử lý
        đồng bộ của `RoomActor`, không trong Netty EventLoop** (§13.2, CLAUDE.md: "Redis Cluster
        ... never inside a Netty EventLoop or on the synchronous RoomActor message path")
  - [ ] Payload đóng gói đúng `{schema_version, epoch, crc32, payload}` (§5.8); `epoch` luôn `0` ở GĐ1
  - [ ] Kích thước snapshot thực đo **< 5 KB** — test guard fail cứng nếu vượt (ràng buộc này là
        lý do `missed_step_policy: ALLOW_LATE` bị cấm ở GĐ1 — xem Task 15/`DefinitionLoader`)
  - [ ] `LastSeenSequenceTable` **bắt buộc nằm trong snapshot** (§5.2) — thiếu nó thì actor hồi
        sinh sẽ chấp nhận trùng các gói replay từ client
  - [ ] CRC32 sai hoặc snapshot thiếu → coi như state rỗng, phục hồi hoàn toàn dựa trên client
        replay (§5.8) — **không throw, không crash actor**
  - [ ] Redis không khả dụng (timeout/connection refused) → **không được chặn `ANSWER_ACK` hay
        bất kỳ message nào trên hot path** — log + metric riêng, actor tiếp tục chạy không snapshot
  - [ ] Sau khi có implementation thật: sửa `system-architecture.md` ADR-002 GĐ1-note và §6.3 —
        câu "10–50ms" chỉ được giữ nguyên nếu có số đo thật; nếu chưa đo, ghi rõ đây là mục tiêu
        chưa xác nhận (cùng tinh thần PH-1 với ngưỡng L1 IP)
- **Verification:** Test giết actor giữa chừng (restart trong `ActorTestKit` hoặc thật), spawn
  actor mới cùng `room_id`, xác nhận roster + score + `LastSeenSequenceTable` khớp state trước khi
  chết. Đo thời gian nạp thật, đối chiếu với con số 10–50ms đang ghi trong tài liệu — lệch thì sửa
  tài liệu, không sửa số đo.
- **Ghi chú:** Lần đầu Redis chạm code thật ở GĐ1 — đúng phạm vi đã chốt ở `_context.md`
  ("Redis Cluster (Ticket SETNX & Snapshot)" nằm trong scope GĐ1). Không mở rộng sang ticket
  replay dedup (`SETNX`) trong task này trừ khi được giao riêng — đó là một điểm gắn khác
  (handshake, không phải RoomActor).
- **Rollback nếu fail:** revert; `RoomActor` tiếp tục chạy không snapshot (đúng hành vi hiện tại) —
  không có gì regress vì tính năng chưa từng tồn tại trước task này.

---

### Task 15: `COMMITTED_SEQ` — tách tín hiệu discard RingBuffer khỏi `ANSWER_ACK` — đóng phát hiện B2 — ⏳ CHƯA BẮT ĐẦU (thêm 2026-09-07)

- **Mode:** sequential after [T14] · phối hợp với PH-3 (hợp đồng client, ngoài phạm vi service này)
- **Mô tả:** `_context.md` mục B2 ghi nhận: `ANSWER_ACK` gửi ngay lập tức (hot path, §4.3 bước 4),
  còn Hot Snapshot ghi Redis là async và luôn xảy ra **sau** ACK (bước 5). Client xoá submission
  khỏi RingBuffer ngay khi nhận ACK (§4.7 bước 2). Nếu pod chết giữa hai mốc đó, câu trả lời đã
  ACK nhưng chưa persist bị mất vĩnh viễn — vi phạm ngầm "mất dữ liệu = 0" (ADR-003) **kể cả khi
  PH-3 hoàn thành 100%**, vì đây là lỗ hổng phía server, không phải thiếu hụt phía client.
  **`ANSWER_ACK` giữ nguyên tức thời, không được trì hoãn để chờ Redis** — trì hoãn nó vi phạm
  trực tiếp "Redis tuyệt đối không trên hot path" và phá vỡ mục tiêu p99 < 100ms của §4.3 (đây là
  sai lầm cụ thể mà review 2026-09-07 chỉ ra ở một trong hai sơ đồ đề xuất — xem `_context.md`).
  Hướng đúng: thêm một tín hiệu riêng, gửi **sau** khi Task 14 xác nhận ghi Redis thành công, báo
  cho client biết seq nào mới thật sự an toàn để xoá khỏi RingBuffer.
- **File dự kiến:** `modules/uni-protocol/src/main/proto/game_message.proto` (thêm message/field
  mới — hình dạng cụ thể **chưa chốt**, xem Acceptance criteria), `modules/uni-engine/.../room/RoomActor.java`
  (gửi tín hiệu mới sau khi `RedisSnapshotStore` của Task 14 xác nhận ghi xong)
- **Dependency:** Task 14 (cần sự kiện "ghi Redis xong" làm trigger); PH-3 (client phải đổi điều
  kiện discard — nằm ngoài phạm vi GĐ1 server nhưng bắt buộc phối hợp trước khi công bố SLA)
- **Acceptance criteria:**
  - [ ] **`ANSWER_ACK` không bị trì hoãn dù chỉ một chút để chờ Redis** — đây là điều kiện fail
        cứng, không thương lượng, xác nhận bằng test đo latency ACK không đổi so với trước Task 15
  - [ ] Tín hiệu mới (`COMMITTED_SEQ` hoặc tên tương đương — quyết định hình dạng cụ thể trước khi
        code, giống cách Task 3 chốt G2a/G2b trước khi implement) là Best-effort, không cần bypass
        tick coalescing như Critical — mất một lần thì lần ghi Redis kế tiếp tự nâng
        `committed_seq` cao hơn, tự lành, không cần cơ chế retry riêng
  - [ ] `RoomActor` không tự gửi tín hiệu này nếu `RedisSnapshotStore` báo lỗi/timeout — im lặng
        bỏ qua lượt đó, đợi lần flush kế tiếp thử lại
  - [ ] Đổi `.proto` đi đúng quy trình đã ghi ở Rollback plan đầu file: PR riêng, codegen lại **cả
        hai** service, không chỉ một bên
  - [ ] Cập nhật `_context.md`/ADR-003: "mất dữ liệu = 0" chỉ đúng khi **cả hai** điều kiện đạt —
        PH-3 xong **và** client dùng `committed_seq` (không phải việc nhận `ANSWER_ACK`) làm điều
        kiện xoá RingBuffer
- **Verification:** Test mô phỏng Redis lỗi (`RedisSnapshotStore` trả lỗi) → xác nhận
  `COMMITTED_SEQ` không được gửi cho lượt đó. Test tích hợp (`uni-engine` hoặc `uni-e2e`) xác nhận
  `COMMITTED_SEQ` cho một `sequence` luôn tới **sau** `ANSWER_ACK` cùng `sequence` đó, không bao
  giờ tới trước hoặc thay thế nó.
- **Ghi chú:** Đây là thay đổi hợp đồng giao thức, ảnh hưởng cả PH-3 (client) — không tự quyết
  định hình dạng message mới trong lúc code, phải chốt trước (như G2a/G2b của Task 3). Nếu PH-3
  chưa có đội nhận việc, task này vẫn nên hoàn thành phần server (phát tín hiệu) trước, nhưng
  **không được công bố SLA "mất dữ liệu = 0" tới khi client đổi điều kiện discard theo đúng
  `committed_seq`**.
- **Rollback nếu fail:** revert; `ANSWER_ACK` vẫn là tín hiệu discard duy nhất như hiện tại —
  quay lại đúng trạng thái B2 đang mở, không tệ hơn hiện trạng.

---

### Task 16: `broadcast_seq` trên `RoomStateSnapshot` — đóng phát hiện B3 — ⏳ CHƯA BẮT ĐẦU (thêm 2026-09-07)

- **Mode:** sequential after [T3] (tick coalescing đã có luồng flush) · phối hợp với PH-3
- **Mô tả:** `_context.md` mục B3 ghi nhận: trường `sequence` trong envelope (`game_message.proto`
  dòng 34-36) là counter do **client** gán cho `SubmitAnswer` (dedupe, §5.2) — không phải số thứ
  tự do server gắn lên broadcast. `RoomStateSnapshot` hiện không mang bất kỳ số thứ tự/version
  nào, nên FE (kể cả sau khi PH-3 xong) không có cách nào tự phát hiện một gói delta broadcast bị
  rớt giữa đường (khác hẳn `SubmitAnswer`, đã có `sequence` + `ANSWER_ACK` để đối chiếu). Task này
  thêm một số thứ tự **do server gắn**, tăng dần mỗi lần phòng flush, để PH-3 có cơ sở thiết kế
  cơ chế phát hiện gap cho broadcast.
- **File dự kiến:** `modules/uni-protocol/src/main/proto/game_message.proto` (thêm field
  `broadcast_seq` vào `RoomStateSnapshot`), `modules/uni-engine/.../room/RoomState.java` (bộ đếm
  tăng dần mỗi lần `flush()`/`joinRoom()` phát ra một bản ghi)
- **Dependency:** Task 3 (cần luồng coalescing flush đã tồn tại để gắn số thứ tự vào đúng chỗ)
- **Acceptance criteria:**
  - [ ] `RoomStateSnapshot.broadcast_seq` tăng dần đơn điệu **mỗi phòng riêng** (không chia sẻ
        giữa các phòng), tăng ở cả full snapshot (join/resync) lẫn delta (flush thường)
  - [ ] Đổi `.proto` đi đúng quy trình đã ghi ở Rollback plan đầu file: PR riêng, codegen lại
        **cả hai** service
  - [ ] Không đổi ngữ nghĩa `sequence` hiện có (vẫn là counter do client gán cho `SubmitAnswer`) —
        `broadcast_seq` là trường **mới**, không tái dùng/đổi tên trường cũ để tránh phá dedupe
        đang hoạt động đúng (§5.2)
  - [ ] Test xác nhận: bỏ qua N lần flush liên tiếp (mô phỏng gói bị rớt) → `broadcast_seq` của
        gói kế tiếp nhận được lớn hơn gói trước đó đúng N+1, đủ để client tự tính được đã mất bao
        nhiêu gói
- **Verification:** `mvn -pl :uni-engine test -Dtest=TickCoalescingTest` mở rộng — case mới xác
  nhận `broadcast_seq` tăng đúng thứ tự qua nhiều lần flush liên tiếp và không reset giữa full
  snapshot với delta.
- **Ghi chú:** Đây là điều kiện cần, không phải đủ, cho RESYNC broadcast — PH-3 vẫn phải tự thiết
  kế phía client dùng `broadcast_seq` thế nào (gửi `RESYNC` khi phát hiện gap, hay chỉ chờ full
  snapshot định kỳ theo N=10 của G2a). Task này chỉ đảm bảo server có gì đó để client bám vào;
  không tự quyết định giao thức RESYNC cho broadcast thay PH-3.
- **Rollback nếu fail:** revert; hành vi hiện tại (không có số thứ tự trên broadcast) không đổi.

---

### Task 17: Nối `missed_step_policy` vào `RoomState`/`RoomActor` — đóng phát hiện B4 — ⏳ CHƯA BẮT ĐẦU (thêm 2026-09-07)

- **Mode:** sequential after [T11] (Game Definition đã có `MissedStepPolicy` trong schema)
- **Mô tả:** `_context.md` mục B4 ghi nhận: `RoomActor`/`RoomState` không hề tham chiếu
  `GameDefinition`/`MissedStepPolicy` — hành vi "0 điểm khi hết giờ không trả lời" hiện tại chỉ là
  tình cờ (điểm khởi tạo mặc định = 0), không phải policy `ZERO` được thực thi có chủ đích. Nếu
  cấu hình `SKIP` hoặc `ALLOW_LATE`, engine vẫn luôn hành xử như `ZERO`. Task này nối
  `GameDefinition.missedStepPolicy` vào logic chuyển câu thật.
- **File dự kiến:** `modules/uni-engine/src/main/java/.../room/RoomActor.java`,
  `.../room/RoomState.java` (đọc `missedStepPolicy` lúc chuyển sang câu kế tiếp/kết thúc game)
- **Dependency:** Task 11 (`GameDefinition`/`MissedStepPolicy` đã có trong schema)
- **Acceptance criteria:**
  - [ ] GĐ1 **chỉ hiện thực `ZERO`** (mặc định) — làm đúng, có chủ đích, thay vì tình cờ đúng như
        hiện tại. `RoomState` phải thật sự đọc `missedStepPolicy` và áp dụng, không chỉ nhận tham
        số rồi bỏ qua.
  - [ ] `SKIP`/`ALLOW_LATE` **fail-fast lúc nạp definition** ở GĐ1 — thêm guard vào
        `DefinitionLoader` theo đúng pattern đã dùng cho `tick_mode: FIXED` (Task 3 quyết định #4).
        Đây là điều kiện bắt buộc **trước hoặc cùng** task này, không để lọt qua rồi mới phát hiện
        không hoạt động đúng lúc chạy — đặc biệt vì `ALLOW_LATE` còn phá luôn ràng buộc snapshot
        < 5 KB (system-architecture.md §4.8, xem thêm Task 14)
  - [ ] Test xác nhận: học sinh không nộp bài trước deadline → điểm giữ nguyên 0 cho câu đó **vì
        `RoomState` áp dụng `ZERO`**, có test kiểm tra rõ ràng field/nhánh code chạy qua, không chỉ
        kiểm tra kết quả điểm số trùng hợp bằng 0
  - [ ] Test xác nhận: definition khai `missed_step_policy: SKIP` hoặc `ALLOW_LATE` → bị
        `DefinitionLoader` từ chối lúc nạp, không lọt tới `RoomActor`
- **Verification:** `mvn -pl :uni-engine test -Dtest=DefinitionLoaderTest` (case mới: reject
  `SKIP`/`ALLOW_LATE`) + `mvn -pl :uni-engine test -Dtest=RoomActorTest` (case mới: không trả lời
  trước deadline → 0 điểm qua đúng nhánh `missedStepPolicy`, không phải qua giá trị mặc định tình
  cờ).
- **Ghi chú:** Không mở rộng sang hiện thực `SKIP`/`ALLOW_LATE` thật ở GĐ1 — `_context.md` §7.5
  câu 1 (`missed_step_policy` mặc định) vẫn còn treo phía Product, và `ALLOW_LATE` bị cấm dùng
  trong thi đấu theo chính tài liệu (§4.8). Task này chỉ đảm bảo `ZERO` chạy đúng nghĩa và các
  giá trị khác không lọt qua trong im lặng.
- **Rollback nếu fail:** revert; hành vi hiện tại ("0 điểm" tình cờ do giá trị mặc định) không đổi
  — không regress vì `ZERO` vẫn là kết quả quan sát được giống hệt trước task.

---

## Pre-merge Checklist

- [x] Tất cả task pass verification (T6 chờ G1a/G1c ngoài tầm kiểm soát nội bộ; T9 có giới hạn
      kiến trúc đã ghi rõ; T13 một phần — xem ghi chú Task 13)
- [x] `mvn clean install` sạch từ root (119 test, BUILD SUCCESS)
- [x] Test suite chạy với `-Dio.netty.leakDetection.level=paranoid`, **không có leak**
- [x] `grep -rn "client_timestamp_ms" modules/uni-engine/src/main --include=*.java` → **không hit nào trong đường chấm điểm**
- [x] `grep -rn "\.retain()" modules/uni-gateway/src/main --include=*.java` → **không hit nào trong vòng fan-out**
- [x] Không có TODO/FIXME chưa resolve trong code mới
- [x] SPIKE đã có kết luận và T3 khớp với kết luận đó
- [ ] `_context.md` cập nhật `dev_selftest` và `phase` — vẫn `phase: dev`, `dev_selftest: pending`
      có chủ đích: T13 chưa xong theo đúng nghĩa đen AC gốc (thiếu Docker Compose + kịch bản
      giết pod thực nghiệm), nên chưa tới điểm ship-ready

> [!NOTE]
> **Các gate của framework kit không chạy được trong repo này** — `scripts/governance-check.sh`,
> `validate-sdd-gate.sh`, `validate-trace.sh` đều không tồn tại (xem `_context.md`).
> Checklist trên dùng lệnh build/test thật thay thế. Muốn có gate thật thì chạy skill
> `onboarding` trước, đừng đánh dấu các dòng đó là pass khi script không tồn tại.
