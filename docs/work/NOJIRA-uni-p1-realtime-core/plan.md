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
> `uni-protocol` / `uni-observability` / `uni-websocket-gateway` / `uni-game-engine`. Chọn module bằng
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
| **Scale Engine pod (thêm/bớt) giữa ca thi đấu vỡ bảng hash `room_id % N`** — khác với hàng trên: đây là *chủ động* đổi `pod-count`/danh sách pod (HPA, `kubectl scale`, rolling update đổi số replica), không phải pod tự crash | Med — chỉ xảy ra nếu ai đó bật HPA/scale thủ công trong ca thi đấu | **Cao hơn cả pod crash** — rehash **toàn bộ phòng của mọi pod cùng lúc**, không chỉ phòng của 1 pod | **Fix thật: Task 14** (`LeaseBasedRoomOwnership` thay `ModuloRoomOwnership` tĩnh — phòng giữ nguyên chủ sở hữu qua Redis lease khi scale, không phụ thuộc `N`). Cho tới khi Task 14 triển khai **và** verify bằng chaos test thật: **Task 19** (runbook cấm auto-scale) là lưới an toàn tạm thời, đây vẫn là rủi ro *quy trình con người* trong lúc chờ |
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
- **Kết quả:** `mvn -pl :uni-game-engine test -Dtest=RoomActorTest` 9/9 pass; `mvn -pl :uni-game-engine test`
  (toàn module) 46/46 pass, leak detection `paranoid` sạch. Chi tiết: `note.md`.
- **File dự kiến:** `modules/uni-game-engine/src/main/java/.../room/RoomActor.java`, `.../room/RoomState.java`, `.../scoring/ScoreCalculator.java`
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
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=RoomActorTest` — dùng `BehaviorTestKit`, không mạng. Phải có case: gian lận `client_timestamp_ms` → điểm không đổi; gửi lại cùng `sequence` → điểm không đổi + ACK cũ trả lại.
- **Cập nhật 2026-09-06 (sau khi Product chốt công thức, system-architecture.md §2.5):**
  `PlaceholderScoreCalculator` đã bị xoá, thay bằng `FormulaScoreCalculator` (bọc
  `ScoringFormula` — cây biểu thức đóng của Task 11 — thay vì một class chấm điểm đứng riêng,
  tránh nhân đôi cơ chế). `FormulaScoreCalculator.binaryChoice()` hiện thực đúng công thức
  Phase 1: trắc nghiệm 1-trong-4, nhị phân đúng/sai, đúng = 100 điểm, sai = 0, không bonus tốc
  độ. `ScoreCalculator.award(...)` mở rộng nhận thêm `correctAnswerIds`, nối qua
  `RoomState.startQuestion(...)` (3 tham số) và `RoomActor.StartQuestion` (3 field). Test mới:
  `FormulaScoreCalculatorTest` (3 case) + `RoomActorTest.should_award0_when_answerDoesNotMatchTheCorrectChoice`.
  `mvn -pl :uni-game-engine test` 46/46 pass. Không còn "Ghi chú còn treo" nào cho Task 2.
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
- **Harness:** `modules/uni-game-engine/src/test/java/.../spike/SchedulerCapacitySpike.java` — có
  `main()`, tên KHÔNG khớp pattern `*Test`/`*Tests` của Surefire nên không chạy trong `mvn test`
  bình thường (đã xác nhận: 37/37 test uni-game-engine không đổi thời gian chạy). Chạy tay theo
  hướng dẫn trong javadoc của file.

---

### Task 3: Tick coalescing trong `RoomActor` — ✅ XONG (2026-09-07)

- **Mode:** sequential after [T2, SPIKE]
- **Mô tả:** 200ms là **trần tần suất**, không phải nhịp phát (ADR-4). Cách hiện thực phụ thuộc kết quả SPIKE.
- **Kết quả:** `mvn -pl :uni-game-engine test -Dtest=TickCoalescingTest` 5/5 pass (`ActorTestKit` +
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
- **File dự kiến:** `modules/uni-game-engine/src/main/java/.../room/RoomActor.java` (mở rộng), `.../room/CoalescingFlush.java`
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
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=TickCoalescingTest` với `ManualTime` của Pekko. Case bắt buộc: 5 giây không có input → **đếm đúng 0 gói outbound**.
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
- **Kết quả:** `mvn -pl :uni-game-engine test -Dtest=FrameCodecTest` 4/4 pass (EmbeddedChannel);
  `mvn -pl :uni-game-engine test -Dtest=FrameChannelServerTest` 1/1 pass (socket thật, port ephemeral —
  bonus so với yêu cầu, chứng minh bootstrap thật sự bindable). Toàn module 14/14, leak detection
  `paranoid` sạch. Chi tiết: `note.md`.
- **File dự kiến:** `modules/uni-game-engine/src/main/java/.../net/FrameChannelServer.java`, `.../net/FrameCodec.java`
- **Dependency:** Task 1
- **Acceptance criteria:**
  - [x] `LengthFieldPrepender(4)` + `LengthFieldBasedFrameDecoder(1MB, 0, 4, 0, 4)`
  - [x] **Một connection dùng chung cho mọi phòng** giữa mỗi cặp (GW pod, Engine pod) — multiplex bằng `room_id`, không phải một connection mỗi phòng
  - [x] Gói bị chia thành nhiều mảnh TCP vẫn ráp đúng
  - [x] Gói vượt `maxFrameLength` → đóng connection + log, không OOM
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=FrameCodecTest` dùng `EmbeddedChannel`, **bắt buộc có case ghi từng byte một** để chứng minh framing đúng khi phân mảnh.
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
- **Kết quả:** `mvn -pl :uni-websocket-gateway test -Dtest=RouteCacheTest` 5/5 pass (plain JUnit, logic thuần).
  `mvn -pl :uni-websocket-gateway test -Dtest=FrameChannelClientTest` 3/3 pass (socket thật, 2 fake Engine
  pod trên loopback — ngoài yêu cầu Verification, chứng minh round-robin/learn/evict thật hoạt
  động cùng nhau, không chỉ đúng ở mức map). Toàn module 15/15, toàn reactor xanh. Chi tiết: `note.md`.
- **File dự kiến:** `modules/uni-websocket-gateway/src/main/java/.../routing/FrameChannelClient.java`, `.../routing/RouteCache.java`
- **Dependency:** Task 1, Task 4
- **Acceptance criteria:**
  - [x] Gateway giữ connection tới **tất cả** Engine pod
  - [x] Chưa biết phòng → gửi **round-robin**; response mang `owner_pod_id` → cache `room_id → pod`
  - [x] Lần gửi sau đi **thẳng** tới đúng pod, không qua hop nội bộ
  - [x] Connection tới một Engine pod đứt → **xoá mọi entry cache trỏ tới pod đó**
  - [x] Cache **không có TTL** — entry sai tự sửa ở lần dùng kế tiếp (§8.2)
  - [x] `RouteCache` **không biết gì về `room_id % N`** — quy tắc sở hữu nằm hoàn toàn bên Engine
- **Verification:** `mvn -pl :uni-websocket-gateway test -Dtest=RouteCacheTest`. Case bắt buộc: sau khi pod A đứt, không entry nào còn trỏ tới A; gói kế tiếp quay lại round-robin.
- **Ghi chú:** Cần một `InternalFrameCodec` riêng ở `uni-websocket-gateway` (không tái dùng `FrameCodec`
  package-private của `uni-game-engine` — hai service triển khai độc lập, chỉ dùng chung schema
  `uni-protocol`, không dùng chung code Netty). `FrameChannelClient` nhận `EventLoopGroup` từ
  bên ngoài (dùng chung với `GatewayBootstrap`), không tự tạo group riêng — giữ đúng bất biến
  "EventLoop cố định = cores × 2" của Task 6 cho toàn bộ pod, không nhân đôi số thread.
- **Rollback nếu fail:** revert. Không có fallback tạm — thiếu task này thì Gateway không gửi được gì tới Engine.

---

### Task 6: Netty pipeline Gateway + WS handshake + join-token auth — ⚠️ MỘT PHẦN XONG (2026-09-06)

- **Mode:** sequential after [T1] · parallel with [T2, T4]
- **Mô tả:** Biên WebSocket. Spring Boot chỉ lo bootstrap/actuator, đường đi gói tin là Netty thuần (quyết định A1).
- **Kết quả:** `mvn -pl :uni-websocket-gateway test -Dtest=GatewayPipelineTest` 6/6 pass (`EmbeddedChannel`),
  toàn module 7/7, không leak, không deprecation warning. Chi tiết: `note.md`.
- **File dự kiến:** `modules/uni-websocket-gateway/src/main/java/.../net/GatewayBootstrap.java`, `.../auth/JoinTokenAuthHandler.java`, `.../net/RoomRouteHandler.java`
- **Dependency:** Task 1
- **Acceptance criteria:**
  - [x] Pipeline đúng thứ tự: `HttpServerCodec → HttpObjectAggregator(50KB) → WebSocketServerProtocolHandler → JoinTokenAuthHandler → RateLimitHandler → ProtobufDecoder → RoomRouteHandler`
        (cập nhật 2026-09-06: cap chung mọi gói WS chốt ở 50KB — system-architecture.md §1.1/§7.5 —
        thay cho 8KB tạm ban đầu)
  - [x] `JoinTokenAuthHandler` **tự gỡ khỏi pipeline** sau handshake — mỗi gói sau đó không verify lại chữ ký
  - [x] Join token hợp lệ → ghi `ChannelAttributes{student_id, room_id, session_id}`
  - [x] **`room_id` luôn đọc từ `ChannelAttributes`, không bao giờ từ payload** (§10.6)
  - [x] Payload mang `room_id` khác attribute → **đóng channel + log cảnh báo bảo mật**
  - [x] Netty EventLoop cố định = cores × 2; **không** DB/Redis/HTTP call nào trong EventLoop (§13.2)
  - [x] **Không có `SslHandler` trong pipeline** — TLS terminate ở LB/ingress (quyết định #3),
        pod nhận WS plaintext. Không thêm cờ config bật TLS tại pod ở GĐ1
  - [ ] Ràng buộc lên ingress phải ghi thành văn bản trong `docker-compose.dev.yml` / manifest:
        **passthrough WebSocket upgrade** và **idle-timeout > chu kỳ heartbeat** (30s),
        nếu không connection sẽ bị LB cắt giữa chừng — **vẫn chưa làm**. Cập nhật 2026-09-07
        (Task 20): `docker-compose.dev.yml` **đã tồn tại**, nhưng đó là compose cho test cục bộ,
        không có LB/ingress nào trong đó (Gateway nhận traffic trực tiếp) — AC này thực chất nói
        về **manifest triển khai thật** (K8s ingress hoặc LB trước Gateway), thứ vẫn chưa tồn tại
        ở bất kỳ đâu trong repo. Vẫn để `[ ]`.
- **Verification:** `mvn -pl :uni-websocket-gateway test -Dtest=GatewayPipelineTest`. Case bắt buộc: gói có `room_id` giả mạo → channel bị đóng.
- **Ghi chú quan trọng — thuật toán ký join token KHÔNG được hiện thực ở đây:** tech-design.md
  G1a/G1c (thuật toán ký, phân phối khoá, dung sai lệch đồng hồ) **vẫn chưa chốt** — phải hỏi
  đội dịch vụ nền tảng, không tự bịa. `JoinTokenAuthHandler` vì vậy nhận một `JoinTokenVerifier`
  (interface only, **không có implementation thật cho staging/production**) qua constructor;
  test dùng fake verifier. Khi G1a/G1c chốt, chỉ cần viết một implementation thật của
  `JoinTokenVerifier` và wire vào `GatewayBootstrap` — không phải sửa `JoinTokenAuthHandler`.
  **Không dùng verifier tạm này ở staging/production.** Cập nhật 2026-09-07 (Task 20): đã có một
  implementation **dev-only** (`DevJoinTokenVerifier`, HMAC tự bịa cho test cục bộ, khoá kép bằng
  Spring profile + property) để mở WS port test qua Docker — không liên quan gì tới thuật toán
  ký thật G1a/G1c sẽ chốt, không đổi kết luận task này vẫn "một phần xong" vì chờ bên ngoài.
- **Rollback nếu fail:** revert; nhánh T2/T4 không bị ảnh hưởng.

---

### Task 7: Rate limiting phân tầng theo loại thông điệp — ✅ XONG (2026-09-07)

- **Mode:** sequential after [T6] · parallel with [T8]
- **Mô tả:** Khoá theo `student_id`, **không** khoá theo IP làm tầng chính — trường học đi sau NAT dùng chung một IP (§10.1).
- **Kết quả:** `mvn -pl :uni-websocket-gateway test -Dtest=RateLimitHandlerTest` 5/5 pass (`EmbeddedChannel`,
  `Clock` cố định) + `TokenBucketTest` 4/4 pass (logic thuần) + `IpAdmissionControllerTest` 3/3
  pass (logic thuần) + `IpAdmissionHandlerTest` 3/3 pass (`EmbeddedChannel` với remote address
  giả lập được). Toàn module 52/52, toàn reactor `mvn clean install` xanh. **Prove-it**: tạm bỏ
  qua verdict của `IpAdmissionController` trong `IpAdmissionHandler` — xác nhận đúng 1 test Red
  trước khi trả lại Green.
- **File dự kiến:** `modules/uni-websocket-gateway/src/main/java/.../net/RateLimitHandler.java`
- **Dependency:** Task 6
- **Acceptance criteria:**
  - [x] Bucket riêng theo loại: `SUBMIT_ANSWER` 3/refill 1s · `UPDATE_DRAFT` 10/refill 10s · `HEARTBEAT` 2/refill 1 mỗi 30s
  - [x] Khoá bucket là `student_id` lấy từ `ChannelAttributes` (cụ thể: mỗi connection/`RateLimitHandler`
        đã gắn với đúng 1 student_id sau `JoinTokenAuthHandler`, nên bucket state per-instance = per-student_id)
  - [x] L1 theo IP đặt **4.000 handshake/phút** (cập nhật: quyết định Business 2026-09-06 nâng từ
        300 → 4.000, xem system-architecture.md §5.6 — con số trong AC gốc của plan.md đã lỗi
        thời trước khi Task 7 được hiện thực đầy đủ). Hiện thực ở `IpAdmissionController` +
        `IpAdmissionHandler`, gắn làm handler **đầu tiên** trong `GatewayPipeline` (trước cả
        `BackpressureHandler`) — điểm gắn trước đây chưa tồn tại, nay có rồi
  - [x] Vượt ngưỡng → `RATE_LIMIT_EXCEEDED`, **không** đóng channel (chỉ áp dụng đúng nghĩa cho
        `SUBMIT_ANSWER` — xem ghi chú về `UPDATE_DRAFT`/`HEARTBEAT`). Riêng L1 (IP) là control ở
        tầng handshake, không phải per-message — vượt ngưỡng L1 **đóng channel** (đúng bản chất
        "chống DDoS thô", khác hẳn ngữ nghĩa `RATE_LIMIT_EXCEEDED` của các bucket per-message)
- **Verification:** `mvn -pl :uni-websocket-gateway test -Dtest=RateLimitTest`. Case bắt buộc: **500 client sau cùng một IP đều kết nối được** — đây là kịch bản khách hàng thật.
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
  - **Cập nhật 2026-09-07 — L2 (khoá theo `student_id`, 10 handshake/phút) nay đã hiện thực:**
    `StudentHandshakeAdmissionController` (cùng khuôn `IpAdmissionController` — một
    `TokenBucket`/`student_id`, chia sẻ qua `ConcurrentHashMap` toàn pod, capacity=10,
    refill=1 phút), chạy trong `JoinTokenAuthHandler` **ngay sau** `joinTokenVerifier.verify(...)`
    thành công (lúc `student_id` vừa xác định được) và **trước** khi bind `ChannelAttributes` —
    vượt ngưỡng thì đóng channel, cùng kiểu xử lý với join token bị từ chối. Phát hiện khi người dùng
    hỏi lại "NAT hoạt động thế nào" và soát lại thấy AC này có trong tài liệu nhưng chưa từng có
    code — không phải việc quên từ trước, mà là khoảng trống thật sự tồn tại từ Task 7 gốc tới
    giờ. Test mới: `StudentHandshakeAdmissionControllerTest` (3 case, cùng khuôn
    `IpAdmissionControllerTest`) + `GatewayPipelineTest.should_closeChannel_when_studentExceedsL2HandshakeAdmissionControl`
    (10 handshake đầu tiên của 1 student đều được admit, cái thứ 11 bị đóng channel). `mvn clean
    install` toàn reactor: BUILD SUCCESS, **184 test**, không leak.
  - **Vẫn không hiện thực L3 (admission control toàn pod, §6.5)** — L3 chưa nằm trong bất kỳ task
    nào của `plan.md` tính đến giờ, mở rộng sang đó ở đây sẽ là tự thêm phạm vi ngoài AC đã giao.
- **Rollback nếu fail:** revert; tạm chạy không rate limit ở môi trường dev, **không** đưa lên staging.

---

### Task 8: Room registry cục bộ + fan-out zero-copy — ✅ XONG (2026-09-06)

- **Mode:** sequential after [T6] · parallel with [T7]
- **Mô tả:** `Map<room_id, Set<Channel>>` trong từng pod, broadcast bằng `retainedDuplicate()`. **Đây là task dễ sai nhất trong GĐ1.**
- **Kết quả:** `mvn -pl :uni-websocket-gateway test -Dtest=FanoutTest` 4/4 pass — **đã chủ động đổi tạm
  sang `.retain()` để xác nhận test thật sự Red** (client thứ 2 nhận mảng byte rỗng), rồi
  trả lại `.retainedDuplicate()` để Green — không chỉ tin code đúng vì test pass ngay lần đầu.
  `RoomRegistryTest` 5/5 pass. Nối dây thật vào `JoinTokenAuthHandler`/`RoomRouteHandler`/
  `GatewayPipeline`/`GatewayBootstrap` — `GatewayPipelineTest` thêm 2 case xác nhận add-on-join
  và remove-on-channelInactive qua đúng pipeline thật. Toàn module 35/35, toàn reactor xanh,
  `-Dio.netty.leakDetection.level=paranoid` sạch (đã bật sẵn toàn cục qua Surefire).
- **File dự kiến:** `modules/uni-websocket-gateway/src/main/java/.../fanout/RoomRegistry.java`, `.../fanout/Broadcaster.java`
- **Dependency:** Task 6
- **Acceptance criteria:**
  - [x] Dùng **`retainedDuplicate()`**, tuyệt đối không `retain()`
  - [x] `frame.release()` nằm trong khối `finally`
  - [x] Fan-out chỉ lặp trên `Set<Channel>` của đúng phòng, **không lọc động từ danh sách toàn cục**
  - [x] `channelInactive` gỡ Channel khỏi **mọi** set ngay lập tức
  - [x] Engine gửi **một gói cho mỗi GW pod**, Gateway mới nhân bản (quyết định B1) — thoả mãn
        theo đúng hình dạng `Broadcaster.broadcast(roomId, mộtFrame)`, không cần code thêm gì
- **Verification:** `mvn -pl :uni-websocket-gateway test -Dtest=FanoutTest` với **12 `EmbeddedChannel`** — kiểm **từng client nhận đủ số byte**, không chỉ client đầu. Chạy kèm `-Dio.netty.leakDetection.level=paranoid`, log leak phải sạch.
- **Ghi chú:** `JoinTokenAuthHandler` (Task 6) đăng ký channel vào `RoomRegistry` ngay khi bind
  `ChannelAttributes` (đây là chỗ duy nhất biết `room_id`), nhưng nó tự gỡ khỏi pipeline sau
  đó — nên việc gỡ đăng ký (`channelInactive`) đặt ở `RoomRouteHandler` (handler duy nhất còn
  sống suốt vòng đời connection). `RoomRegistry` phải là **một instance chia sẻ** cho cả pod
  (khác với `JoinTokenAuthHandler`/`RateLimitHandler` — mỗi channel một instance riêng) —
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
- **Kết quả:** `mvn -pl :uni-websocket-gateway,:uni-game-engine test -Dtest=BackpressureTest` — gateway 5/5 pass,
  engine 2/2 pass. Case bắt buộc "client chậm không kéo tụt client khác cùng phòng" pass
  (`should_notAffectOtherClientsInTheSameRoom_when_oneClientIsSlow`) — **đã chủ động đảo ngược
  logic Critical/Best-effort để xác nhận 3 test liên quan thật sự Red trước khi tin Green**,
  đúng tinh thần prove-it đã dùng ở Task 8. Toàn `uni-websocket-gateway` 40/40, toàn `uni-game-engine` 23/23,
  toàn reactor xanh, không leak, không deprecation warning.
- **File dự kiến:** `modules/uni-websocket-gateway/src/main/java/.../net/BackpressureHandler.java`, `modules/uni-game-engine/src/main/java/.../net/FrameChannelServer.java` (sửa)
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
- **Verification:** `mvn -pl :uni-websocket-gateway,:uni-game-engine test -Dtest=BackpressureTest` — client chậm không kéo tụt client khác cùng phòng.
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
- **Kết quả:** `mvn -pl :uni-game-engine test -Dtest=RoomOwnershipTest` 5/5 pass (plain JUnit, logic
  thuần) + `RoomOwnershipHandlerTest` 2/2 pass (`EmbeddedChannel`, thêm ngoài yêu cầu — chứng
  minh handler thật sự forward/đóng dấu NOT_OWNER đúng, không chỉ đúng thuật toán). Grep bắt
  buộc đã chạy, chỉ khớp `ModuloRoomOwnership`. Toàn module 21/21, toàn reactor xanh.
- **File dự kiến:** `modules/uni-game-engine/src/main/java/.../room/RoomOwnership.java`, `.../net/FrameChannelServer.java` (sửa)
- **Dependency:** Task 4
- **Acceptance criteria:**
  - [x] Quy tắc sở hữu nằm sau **một interface duy nhất** (`RoomOwnership`), có đúng một implementation `ModuloRoomOwnership`
  - [x] Mọi response đóng dấu `InternalHeader.owner_pod_id`
  - [x] Nhận gói của phòng không thuộc pod này → forward hoặc trả `NOT_OWNER` kèm owner hiện tại
        (chọn trả `NOT_OWNER` — đúng cơ chế §4.5 đã tài liệu hoá; không hiện thực forward
        Engine-to-Engine vì không nằm trong bất kỳ task nào)
  - [x] **Không class nào ngoài `RoomOwnership` biết tới phép `% N`** (grep được)
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=RoomOwnershipTest` + `grep -rn "% N\|modulo" modules/uni-game-engine/src/main --include=*.java` chỉ trả về `ModuloRoomOwnership`.
- **Ghi chú:** `FrameChannelServer` đổi constructor để nhận thêm `RoomOwnership` (đã sửa
  `FrameChannelServerTest` theo — dùng `ModuloRoomOwnership` 1-pod cho kịch bản "sở hữu mọi
  phòng" của test cũ). Logic ownership + NOT_OWNER tách thành `RoomOwnershipHandler` riêng
  (`engine.net`) để test được qua `EmbeddedChannel` mà không cần bind socket thật.
- **Rollback nếu fail:** revert.

---

### Task 11: Game Definition tối giản + `MAX_TRANSITIONS` — ✅ XONG (2026-09-06)

- **Mode:** sequential after [T2] · parallel with [T3]
- **Mô tả:** Đủ để chơi trọn một ván quiz. Kèm rào chắn runtime tối thiểu (§10.5).
- **Kết quả:** `mvn -pl :uni-game-engine test -Dtest=DefinitionLoaderTest` 8/8 pass +
  `ScoringFormulaTest` 6/6 pass (thêm ngoài yêu cầu — chứng minh tập toán tử giới hạn tính
  đúng, không chỉ đúng cấu trúc dữ liệu). **Prove-it**: tạm vô hiệu hoá `rejectCycles(...)`,
  xác nhận đúng 2/8 test chu trình fail, rồi bật lại. Toàn module 37/37, toàn reactor xanh.
- **File dự kiến:** `modules/uni-game-engine/src/main/java/.../definition/GameDefinition.java`, `.../definition/DefinitionLoader.java`
- **Dependency:** Task 2
- **Acceptance criteria:**
  - [x] Định nghĩa được: danh sách step, thời lượng mỗi step, `tick_mode`, công thức điểm
  - [x] **Validate DAG lúc nạp** — phát hiện chu trình bằng duyệt đồ thị, từ chối definition có chu trình
  - [x] **Không script engine, không biểu thức tuỳ ý** — công thức điểm dùng tập toán tử giới hạn
  - [x] `MAX_TRANSITIONS` mỗi phiên làm hàng rào cuối
  - [x] `missed_step_policy` **có mặt trong schema** với mặc định `ZERO`, dù luồng late join chưa làm ở GĐ1
  - [x] `tick_mode` chấp nhận `COALESCE` | `FIXED` trong schema, nhưng loader **từ chối `FIXED`
        lúc nạp** ở GĐ1 (chưa có implementation — xem Task 3, quyết định #4)
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=DefinitionLoaderTest` — definition có chu trình bị từ chối **lúc nạp**, không phải lúc chạy.
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
- **Kết quả:** Đã CHẠY THẬT cả hai app (`mvn -pl :uni-websocket-gateway spring-boot:run` /
  `mvn -pl :uni-game-engine spring-boot:run`) và `curl` `/actuator/prometheus` thật — không chỉ tin
  unit test. Cả 5 metric xuất hiện đúng ngay từ lúc khởi động, giá trị 0 (chưa có traffic thật):
  `actor_processing_latency_seconds{...}`, `actor_mailbox_depth 0.0`,
  `channel_not_writable_total 0.0` (engine), `fanout_latency_seconds{...}`,
  `handshake_rate_total 0.0`, `channel_not_writable_total 0.0` (gateway). Đã tắt cả hai process
  sau khi xác nhận. 88/88 test (46 gateway + 42 engine) pass, toàn reactor xanh.
- **File dự kiến:** `modules/uni-game-engine/src/main/java/.../metrics/EngineMetrics.java`, `modules/uni-websocket-gateway/src/main/java/.../metrics/GatewayMetrics.java`
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
    `ChannelAttributes.TRACE_ID`, sinh bằng `UUID.randomUUID()` tại `JoinTokenAuthHandler` lúc
    handshake) vào MỌI message trước khi forward — kể cả khi chưa có
    `FrameChannelClient.send(...)` nào tiêu thụ nó thật sự. Đây là điểm đúng về mặt kiến trúc để
    dừng lại (đã CHỐT xong phần Gateway); Task 13 chỉ cần gọi `send(...)` với message đã chuẩn
    bị sẵn, không cần biết gì về tracing.
- **Rollback nếu fail:** revert; không chặn task nào khác.

---

### Sync checkpoint

> Chờ toàn bộ T3, T5, T7, T8, T9, T10, T11, T12 xong trước khi bắt đầu T13.

- [x] Ba nhánh song song (T2/T4/T6) đều pass verification (T6 vẫn "một phần" ở chỗ chưa có
      `JoinTokenVerifier` thật — chờ G1a/G1c — nhưng `GatewayPipelineTest` của chính nó vẫn xanh)
- [x] SPIKE có kết luận ghi thành văn bản, và T3 hiện thực **đúng theo kết luận đó**
- [x] `mvn clean install` toàn project sạch (119 test: 4 protocol + 59 gateway + 55 engine + 1 e2e)
- [x] Log leak detection (`paranoid`) sạch trong toàn bộ test suite
- [x] Không task nào chạm Redis, ShardRegion hay Kafka — ranh giới GĐ1 còn nguyên
- **Ghi chú:** T9 vẫn "một phần" (giới hạn kiến trúc đã biết, xem ghi chú ở Task 9) — quyết định
  thực tế 2026-09-07 là tiến hành T13 vì phần backpressure watermark-based (không phải
  mailbox-depth) đã đúng và test được ở cả 3 hop; T13 tự nó không cần chuỗi mailbox-depth cụ
  thể để chứng minh walking skeleton hoạt động.

---

### Task 13: Ghép walking skeleton end-to-end + smoke test — ✅ XONG (2026-09-07; 2 AC còn treo đóng nốt bằng chaos test thật 2026-09-08 — xem cập nhật bên dưới)

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
  - [x] Chạy với ≥ 2 Engine pod: route cache học đúng `owner_pod_id` — **Cập nhật 2026-09-07
        (Task 20):** giờ đã ghép vào một kịch bản `uni-e2e` chung, qua Docker thật thay vì chỉ
        đơn vị: `DockerComposeResyncIT` join 2 phòng băm về 2 pod khác nhau (`room-docker-a` →
        `engine-0`, `room-docker-b` → `engine-1`) qua `docker-compose.dev.yml`, cả hai route đúng.
        Nhân tiện lộ ra một giới hạn thật: JOIN_ROOM đầu tiên của phòng mới có thể trúng round-
        robin sai pod (§4.5 bước 2, xem `RoomOwnershipHandler`'s javadoc) — client phải tự retry,
        đã thêm `SimulatedStudentClient.joinRoomWithRetry` cho việc này, không sửa Gateway/Engine.
  - [x] Giết một Engine pod → client nhận `CONNECTION_DEGRADED`, WebSocket không đóng (§9.7) —
        **Cập nhật 2026-09-08:** đóng hẳn bằng `DockerComposeChaosIT.should_notCloseTheSocket_when_itsOwningEngineIsKilled`
        — `docker compose kill <pod thật đang giữ phòng>` (xác định qua `redis-cli GET room:owner:<room_id>`,
        không đoán theo hash vì `LeaseBasedRoomOwnership` không còn cố định theo `room_id % N`),
        xác nhận client nhận đúng `CONNECTION_DEGRADED` và socket còn mở. Cơ chế production
        (`RouteCache.evictPod`, `FrameChannelClient.onPodDisconnected`,
        `EngineResponseRouter.broadcastConnectionDegraded`) không đổi — chỉ thêm bằng chứng qua
        container thật thay vì chỉ unit test với fake.
  - [x] `docker-compose.dev.yml` với 2 GW + 2 Engine thật — **Cập nhật 2026-09-08:** thêm service
        `gateway-1` (cùng cấu hình `gateway`, khác host port `9001`/`8081`) — đủ 2 Gateway + 2
        Engine như AC gốc đòi. `gateway` gốc giữ nguyên port 9000 (DockerComposeResyncIT vẫn hardcode nó).
- **Verification:** `mvn -pl :uni-e2e -am test` (bắt buộc `-am`, xem ghi chú build) — 119 test cũ vẫn
  xanh. Cả 2 sub-AC còn treo phía trên nay đã đóng qua `DockerComposeChaosIT` (`RUN_DOCKER_IT=true
  mvn -pl :uni-e2e test -Dtest=DockerComposeChaosIT`, chạy thật ~2 phút do phải chờ hết lease TTL
  20s ở kịch bản Task 14).
- **Phát hiện phụ khi viết `DockerComposeChaosIT` (2026-09-08):** 2 bug thật trong code Task 14
  (`SnapshotEnvelope.unwrap()` không được gọi trước restore; `LeaseBasedRoomOwnership` cache vĩnh
  viễn kết quả thua race) chặn đứng chính kịch bản pod-crash-recovery — chi tiết đầy đủ ở ghi chú
  tương ứng trong Task 14 bên dưới, cả hai đã sửa + có test hồi quy.
- **Ghi chú quan trọng — kiến trúc mới phải xây (Task 2/9 đều đã ghi rõ đây là việc của Task 13):**
  - **`RoomSupervisor`** (`modules/uni-game-engine/.../room/RoomSupervisor.java`, mới): actor duy nhất
    mỗi Engine pod, spawn `RoomActor` lười theo `room_id` lúc `JOIN_ROOM` đầu tiên, dịch
    `GameMessage` thành đúng `RoomActor.Command`, và học tập hợp connection nào đang theo dõi
    phòng nào (từ `JOIN_ROOM`) để fan-out broadcast tới đúng tập đó — không phải một target cố
    định như Task 3 giả định tạm.
  - **`ChannelReplyActor`** (`modules/uni-game-engine/.../net/ChannelReplyActor.java`, mới): điểm
    DUY NHẤT đóng dấu `InternalHeader{owner_pod_id, delivery_class}` trước khi ghi ra
    `Channel` — `RoomActor`/`RoomState` (Task 2/3) không hề biết pod id hay cách phân loại
    delivery class, đúng như thiết kế transport-agnostic ban đầu.
  - **`EngineResponseRouter`** (`modules/uni-websocket-gateway/.../net/EngineResponseRouter.java`, mới):
    nửa còn lại ở Gateway — `ANSWER_ACK` đi thẳng một học sinh (tìm channel theo `student_id`
    trong `RoomRegistry` của đúng phòng), mọi thứ khác qua `Broadcaster` (đã có từ Task 8),
    `NOT_OWNER` bị bỏ qua (không có payload, §8.2 chỉ sửa route cho lần sau), và `internal`
    luôn bị `clearInternal()` trước khi tới client (đúng comment trong `.proto`).
  - **`RoomRouteHandler`** (sửa): giờ gọi `EngineSender.send(...)` thật thay vì `fireChannelRead`
    rồi không ai đọc — đây chính là chỗ Task 12 dự đoán trước ("chỉ cần gọi `send(...)`, không
    cần biết gì về tracing"). Nhân tiện áp **cùng ranh giới tin cậy cho `student_id`** như
    `room_id` đã có từ Task 6 (§10.6) — envelope's `student_id` trước đây KHÔNG bị ép về giá trị
    đã xác thực, một lỗ hổng nhỏ chưa ai phát hiện tới giờ vì chưa có gì tiêu thụ message đó.
  - **`EngineSender`** (`modules/uni-websocket-gateway/.../routing/EngineSender.java`, mới): interface tách
    khỏi `FrameChannelClient` cụ thể, đúng khuôn `JoinTokenVerifier` — để test `RoomRouteHandler`
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
    có `@ConditionalOnBean(JoinTokenVerifier.class)` và chưa có bean thật nào (G1a/G1c chưa chốt).
    Đây là hành vi ĐÚNG, không phải lỗi — khớp đúng câu cấm của `JoinTokenAuthHandler`: "Không dùng
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
  `<classifier>`) thay artifact chính của `uni-websocket-gateway`/`uni-game-engine` bằng jar thực thi (class nằm
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
  - `PAUSE` (TeacherCommand) — vẫn không wire, có chủ đích: schema không có lệnh RESUME nào, và
    không tài liệu nào (kể cả v3.0) định nghĩa "pause" có đóng băng deadline/điểm hay không — làm
    bây giờ nghĩa là tự bịa luật chơi. `KICK_STUDENT` **đã wire (2026-09-08)**, xem ngay dưới.
  - ~~Luồng "rời phòng" (`connected=false`)~~ — **đã đóng (2026-09-08)**, xem mục mới ngay dưới.
- **Cập nhật 2026-09-08 — đóng nốt luồng rời phòng + `KICK_STUDENT`:**
  - **Luồng rời phòng:** thêm `MessageType.STUDENT_LEFT` (không payload, tái dùng `room_id`/
    `student_id` sẵn có trong envelope). `RoomRouteHandler.channelInactive` gửi nó cho Engine nếu
    channel đã từng JOIN_ROOM thành công (bỏ qua nếu chưa). `RoomSupervisor` dispatch tới
    `RoomActor.StudentDisconnected(studentId)` (command mới) → `RoomState.markDisconnected(...)`
    (mirror `joinRoom`, set `connected=false` + đánh dấu dirty, no-op nếu student không có trong
    roster) → party khác thấy `connected=false` ở delta kế tiếp.
  - **`KICK_STUDENT`:** thêm `MessageType.STUDENT_KICKED` (không payload). `RoomActor.KickStudent`
    gọi `markDisconnected` rồi gửi ngay `STUDENT_KICKED` qua `broadcastTarget` (bypass coalescing,
    giống `ANSWER_ACK`) — fan-out tới MỌI Gateway pod đang subscribe phòng (quyết định B1), pod
    nào thật sự giữ channel của học sinh đó mới xử lý (không cần Engine biết học sinh đang ở pod
    nào). `EngineResponseRouter` thêm nhánh riêng cho `STUDENT_KICKED`: gửi xong thì đóng channel
    ngay (`ChannelFutureListener.CLOSE`) bất kể writability — khác hẳn `ANSWER_ACK` (không đóng)
    và `CONNECTION_DEGRADED` (cố tình không đóng).
  - Test mới: `RoomActorPresenceTest` (4 case, engine), `RoomSupervisorTest` (+3 case: dispatch
    STUDENT_LEFT, no-op khi phòng chưa tồn tại, dispatch KICK_STUDENT), `GatewayPipelineTest` (+2
    case: gửi STUDENT_LEFT khi channel đã join rồi đóng, không gửi gì khi chưa từng join — sửa
    luôn 2 test cũ về security event vì giờ đóng channel do vi phạm trust boundary CŨNG kích hoạt
    luồng rời phòng, đúng vì học sinh đó thật sự đang rời), `EngineResponseRouterTest` (+2 case:
    đóng channel sau khi gửi kick, release frame an toàn khi học sinh không ở pod này),
    `GameMessageRoundTripTest` (+1 case). `mvn clean install` toàn reactor: BUILD SUCCESS.
- **Rollback nếu fail:** không revert — đây là task tích hợp, fail nghĩa là một task thượng nguồn sai. Truy về task đó.

---

### Task 14: `LeaseBasedRoomOwnership` + Hot Snapshot thật — thay `ModuloRoomOwnership` tĩnh, đóng B1 + cho phép auto-scale an toàn — ⚠️ MỘT PHẦN XONG (viết lại 2026-09-07, thay bản Task 14 gốc; bắt đầu code 2026-09-07; chaos test + 2 bug fix thật 2026-09-08)

- **Kết quả (phần ownership — 2026-09-07):** `RoomLease`/`RoomLeaseStore` (interface async,
  `CompletableFuture`) + `LeaseBasedRoomOwnership implements RoomOwnership` — cache RAM
  (`ConcurrentHashMap`), `ensureAcquired()` giành lease async qua `computeIfAbsent` (đúng 1 lần
  gọi store dù nhiều frame trùng lúc cho phòng mới — **prove-it**: tạm bỏ `computeIfAbsent` thay
  bằng gọi thẳng, xác nhận đúng 1/11 test Red trước khi trả lại Green), `renewAll()` (renew thất
  bại/exception → coi như mất lease, gỡ khỏi cache), fallback về `ModuloRoomOwnership` khi store
  lỗi kết nối. `RoomOwnership` thêm default method `ensureAcquired()` (no-op cho
  `ModuloRoomOwnership`) — **phát hiện giữa chừng quan trọng**: `RoomOwnershipHandler.isOwner()`
  chạy TRÊN Netty EventLoop (mỗi frame), nên `isOwner`/`ownerPodId` của `LeaseBasedRoomOwnership`
  tuyệt đối không được chạm Redis — chỉ đọc cache, không bao giờ block (ADR-005). `ownerPodId()`
  trả `""` khi chưa xác định được (lease đang giành dở) — sửa `RoomOwnershipHandler` để DROP
  frame đó thay vì trả lời `NOT_OWNER` bịa chủ phòng. `DistributedRoomLeaseStore` (Lettuce, 2 key
  `room:owner:{id}`/`room:epoch:{id}`, Lua script cho renew atomic) — **CHƯA verify được với
  Redis thật** (không có Redis/Docker trong môi trường viết code này, giống hệt caveat của
  `JoinTokenVerifier`). Thêm dependency `io.lettuce:lettuce-core` vào `uni-game-engine/pom.xml` (version
  quản lý transitively qua BOM Spring Boot). Test mới: `LeaseBasedRoomOwnershipTest` (11 case,
  logic thuần + fake `RoomLeaseStore`) + 2 case mới trong `RoomOwnershipHandlerTest`
  (drop-khi-chưa-biết-owner, `ensureAcquired` được gọi trước khi check). `mvn clean install`
  toàn reactor: BUILD SUCCESS, 145 test, không leak.
- **Kết quả (phần Hot Snapshot — tiếp tục 2026-09-07):** `RoomState.serializeSnapshot()` +
  `RoomState.restore(...)` (định dạng nhị phân tự viết bằng `DataOutputStream`, KHÔNG tái dùng
  `RoomStateSnapshot` của `uni-protocol` — đây là persistence nội bộ Engine, không phải wire
  schema, cố tình tách khỏi ADR-1 để không kéo theo PR đổi `.proto` mỗi lần đổi định dạng lưu
  trữ). `SnapshotEnvelope` (schema_version/epoch/crc32/size-guard, §5.8). `RoomSnapshotStore`
  interface (async) + `DistributedRoomSnapshotStore` (Lettuce, **chưa verify Redis thật**, dùng chung key
  epoch với `DistributedRoomLeaseStore` qua Lua script để fencing). Nối vào `RoomActor`: overload
  `create(...)` mới nhận `RoomSnapshotStore` + `epoch` + `restoreFromSnapshot` (overload 6-tham-số
  cũ giữ nguyên, gọi overload mới với `NoopRoomSnapshotStore` — không phá bất kỳ test/call site
  nào đang có: `RoomSupervisor`, `RoomActorTest`, `TickCoalescingTest`, `SchedulerCapacitySpike`
  không cần sửa). `maybeSnapshot()` gate 2s (`SNAPSHOT_MIN_INTERVAL_MS`, khớp "2-3s" của §4.3),
  không bao giờ block actor kể cả khi store treo vĩnh viễn. **Bug đã tìm và sửa kèm theo** (xem
  AC): `RoomState.submitAnswer` thiếu nhánh dedupe an toàn khi không có ack gốc — dùng
  `RejectReason.DUPLICATE_SEQUENCE` (đã có sẵn trong schema, chưa ai dùng tới giờ). Test mới:
  `RoomStateSnapshotTest` (5) + `SnapshotEnvelopeTest` (6) + `RoomActorSnapshotTest` (5) = 16
  test, cả 2 đều có prove-it riêng (bug dedupe + gate 2s). `mvn clean install` toàn reactor:
  BUILD SUCCESS, **161 test, không leak**.
- **Kết quả (wiring production — tiếp tục 2026-09-07):** Nối `LeaseBasedRoomOwnership` +
  `DistributedRoomSnapshotStore` vào `EngineNetworkLifecycle` thật, sau cờ cấu hình mới
  `uni.engine.room-store.enabled` (mặc định **`false`** — `application.yml`). Lý do bắt buộc phải có
  cờ này: `RedisClient.connect()` của Lettuce là **đồng bộ, throw nếu không kết nối được** — bật
  vô điều kiện sẽ làm `EngineApplicationTests`/`mvn spring-boot:run` fail ngay trong chính môi
  trường không có Redis này. Khi `false` (mặc định), hành vi **giống hệt trước Task 14**:
  `ModuloRoomOwnership`, không snapshot — đã xác nhận bằng `EngineApplicationTests` (Spring
  context thật) **và** chạy thật `mvn spring-boot:run` + `curl /actuator/health` +
  `/actuator/prometheus` + `netstat` xác nhận cổng 9100 bind đúng, không chạm Redis. Khi `true`:
  dựng `RedisClient`, 2 connection Lettuce (String cho lease, byte[] cho snapshot — dùng
  `DistributedRoomSnapshotStore.CODEC`), `LeaseBasedRoomOwnership` (fallback `ModuloRoomOwnership`), một
  `ScheduledExecutorService` daemon gọi `renewAll()` mỗi `ttl/3` giây (đóng nốt gap "chưa có gì
  gọi renewAll định kỳ"), đóng hết trong `destroy()`.

  **Phát hiện kiến trúc quan trọng khi nối dây, đã xử lý:** `RoomSupervisor.spawnRoom` chạy trên
  chính actor thread của `RoomSupervisor` — load snapshot từ Redis ở đó cũng sẽ block y hệt lý
  do `RoomOwnershipHandler` không được chạm Redis trên Netty EventLoop. Đã sửa
  `RoomSupervisor`: `JOIN_ROOM` cho phòng chưa tồn tại giờ kích hoạt `snapshotStore.load(roomId)`
  **bất đồng bộ** qua `getContext().pipeToSelf(...)` (không bao giờ `.get()`/`.join()`), các join
  khác tới cùng lúc **xếp hàng** chờ chung 1 lần load (không load N lần), phòng chỉ thật sự spawn
  (với bytes phục hồi nếu có) sau khi load xong — `onGetRoomActor` (hook test/ops-only) vẫn spawn
  ngay không chờ load, đúng vai trò của nó. Test mới: 1 case trong
  `RoomSupervisorTest` (dùng `ControllableSnapshotStore`, prove-it xác nhận dedupe đúng 1 test
  Red). `RoomOwnership` thêm `epochOf()` default (trả `0`) để `RoomSupervisor` lấy epoch mà
  không cần biết cụ thể là `LeaseBasedRoomOwnership`.

  `mvn clean install` toàn reactor: BUILD SUCCESS, **162 test**, không leak.
- **Kết quả (fix zombie-actor — tiếp tục 2026-09-07):** `RoomActor` giờ **tự dừng** khi bị
  fencing. Vấn đề thiết kế phát hiện giữa chừng: `RoomSnapshotStore.save()` trước đây trả
  `boolean` — `false` **không phân biệt được** "bị fencing thật" (phải dừng) khỏi "snapshot đang
  tắt" (`NoopRoomSnapshotStore`, mặc định GĐ1, trả `false` ở **mọi** flush). Cho `false` kích hoạt
  tự dừng vô điều kiện sẽ làm **mọi phòng tự dừng ngay sau lần flush đầu** trong cấu hình mặc
  định hiện tại — một regression nghiêm trọng hơn cả lỗ hổng đang vá. Sửa bằng cách nâng
  `RoomSnapshotStore.save()` từ `CompletableFuture<Boolean>` lên `CompletableFuture<SnapshotWriteResult>`
  (enum 3 trạng thái: `ACCEPTED`/`FENCED`/`DISABLED`) — `NoopRoomSnapshotStore` trả `DISABLED`
  (không hành động gì), `DistributedRoomSnapshotStore` trả `ACCEPTED`/`FENCED` tuỳ kết quả Lua script.
  `RoomActor` lưu `ActorRef<Command> self` (lấy lúc khởi tạo, trên đúng actor thread — an toàn để
  `.tell()` từ thread khác sau này, khác `getContext()` không an toàn ngoài actor thread) — khi
  nhận `FENCED`, gửi `LeaseLost.INSTANCE` cho chính mình, actor tự `Behaviors.stopped()`.
  `RoomSupervisor` không cần sửa gì — cơ chế `watchWith`/`RoomTerminated` đã dọn dẹp
  `roomsByRoomId` cho MỌI lý do actor dừng từ Task 13, không riêng `EndGame`. Một exception khi
  ghi (lỗi mạng/timeout) **không** kích hoạt dừng — chỉ epoch bị fencing thật (bộ đếm đơn điệu,
  không bao giờ giảm) mới là tín hiệu dứt khoát; lỗi mạng có thể là tạm thời.

  Test mới: `should_stopTheActor_when_theSnapshotWriteIsFencedOut` +
  `should_notStopTheActor_when_theSnapshotStoreIsMerelyDisabled` (dùng đúng overload 6-tham-số
  mặc định, xác nhận cấu hình GĐ1 hiện tại không bị ảnh hưởng) — prove-it xác nhận đúng 1 test Red.
  Cập nhật toàn bộ fake trong `RoomActorSnapshotTest`/`RoomSupervisorTest` theo enum mới.
  `mvn clean install` toàn reactor: BUILD SUCCESS, **179 test**, không leak.
- **Cập nhật 2026-09-07 (Task 20, Docker daemon lần đầu khả dụng trong phiên này):**
  `DistributedRoomLeaseStore`/`DistributedRoomSnapshotStore` **đã verify được với Redis thật** lần đầu tiên, qua
  `docker-compose.dev.yml` (Redis 7 thật, 2 engine pod thật, `ENGINE_ROOM_STORE_ENABLED=true`) —
  `DockerComposeResyncIT` join 2 phòng khác pod, sau đó `redis-cli KEYS "*"` xác nhận cả
  `room:owner:*`, `room:epoch:*`, `room:snap:*` đều được ghi đúng. Đây vẫn là verify thủ công qua
  Docker cục bộ, **không phải chaos test/staging thật** — AC "Scale thêm pod"/"Pod crash thật"
  phía trên vẫn cần môi trường staging thật để đóng hẳn, nhưng phần "chưa từng chạm Redis thật"
  đã không còn đúng nữa. Xem Task 20 để biết toàn bộ hạ tầng Docker này.
- **Mode:** sequential after [T13]
- **Mô tả:** Gộp hai việc luôn phải đi cùng nhau: (1) Hot Snapshot thật lên Redis (B1 — tài liệu
  nói pod crash → phòng phục hồi trong 10–50ms từ Redis Snapshot, nhưng `RoomActor.flush()` hiện
  chỉ gửi tới `broadcastTarget`, không có nhánh persist nào), và (2) thay `ModuloRoomOwnership`
  (`room_id % N` tĩnh, Task 10) bằng `LeaseBasedRoomOwnership` — một pod **giành** quyền sở hữu
  phòng qua Redis thay vì được **gán** cố định bằng phép chia dư. Đây đúng là điểm mở rộng
  ADR-007 đã thiết kế sẵn (`RoomOwnership` là interface duy nhất, GĐ2 chỉ thay một class) — chỉ
  thay sớm hơn dự kiến, ngay ở GĐ1, để đóng rủi ro "scale Engine giữa ca thi đấu vỡ hash" (xem
  Risk Assessment, Task 19) mà không cần đợi Pekko Cluster Sharding đầy đủ của GĐ2.

  **Vì sao không chọn Consistent Hashing (phương án đã cân nhắc và loại):** giảm tỷ lệ vỡ phòng
  khi scale từ "toàn bộ" xuống còn "~1/N" — đúng, đây là tính chất thật của consistent hashing.
  Nhưng (a) **không giải quyết được phục hồi khi crash** — vẫn cần y hệt Redis Hot Snapshot,
  nên không tiết kiệm được phụ thuộc Redis nào; (b) muốn ring cập nhật động lúc pod chết/thêm mà
  không redeploy toàn bộ cấu hình tĩnh thì **mọi** pod (Gateway lẫn Engine) phải đồng bộ đúng một
  view membership tại mọi thời điểm — tức phải tự xây một cơ chế phát hiện pod sống/chết và phát
  tán thay đổi ring, chính là bài toán Pekko Cluster Sharding đã giải sẵn (có SBR, có kiểm chứng
  thực tế). Tự làm lại bằng tay rủi ro cao hơn dùng Redis (đã có `SETNX` atomic sẵn) hoặc dùng
  thẳng Sharding. Không triển khai Consistent Hashing ở GĐ1.
- **File dự kiến:** `modules/uni-game-engine/src/main/java/.../room/LeaseBasedRoomOwnership.java`
  (mới, implement `RoomOwnership`), `.../persistence/DistributedRoomSnapshotStore.java` (mới),
  `RoomSupervisor.java`/`RoomActor.java` (sửa: renew lease định kỳ, nạp snapshot khi giành được lease)
- **Dependency:** Task 13 (cần `RoomSupervisor`/`RoomActor` đã nối dây thật)
- **Acceptance criteria — phần ownership (mới):**
  - [x] `RoomOwnership` vẫn là **interface duy nhất** biết cách gán chủ phòng —
        `LeaseBasedRoomOwnership` thay `ModuloRoomOwnership` làm implementation mặc định của
        GĐ1. **Không xoá `ModuloRoomOwnership`** — giữ làm fallback khi Redis không khả dụng
        (xem AC fallback bên dưới) — chữ ký interface không đổi, `ModuloRoomOwnership` không sửa
  - [x] Giành quyền sở hữu bằng `SET room:owner:{room_id} "{pod_id}" EX <ttl> NX` + `INCR` một
        key epoch riêng (`room:epoch:{room_id}`) khi thắng — tách 2 key thay vì nhồi
        `"{pod_id}:{epoch}"` vào 1 giá trị, để epoch sống sót qua việc lease hết hạn rồi được
        giành lại (xem `DistributedRoomLeaseStore`, **chưa verify với Redis thật**). `ttl` là tham số
        constructor, không hardcode — khuyến nghị 15–30s vẫn phải áp dụng lúc **wiring thật**
        (chưa làm, xem "Chưa làm" ở trên)
  - [x] `renewAll()` hiện thực: renew thất bại (exception hoặc trả `false`) → gỡ khỏi cache, lần
        `ensureAcquired` kế tiếp tự giành lại từ đầu. **Chưa có gì gọi `renewAll()` định kỳ** —
        xem "Chưa làm" ở trên, đây là lỗi cụ thể nếu triển khai mà quên nối scheduler
  - [x] Mang theo **epoch tăng dần mỗi lần giành lease thành công** — `RoomLease.epoch()` +
        `LeaseBasedRoomOwnership.epochOf()` lộ ra cho phần Hot Snapshot dùng sau. Việc RoomActor
        **dùng** epoch đó để từ chối ghi snapshot cũ (§5.8) thuộc phần Hot Snapshot, chưa làm
  - [x] `ownerPodId(roomId)`/`isOwner(roomId)` đọc từ **cache RAM**, không bao giờ chạm Redis —
        xác nhận bằng test `should_callTheStoreExactlyOnce_when_ensureAcquiredIsCalledRepeatedlyAfterResolving`.
        An toàn Netty EventLoop: `RoomOwnershipHandler.isOwner()` chạy trên EventLoop mỗi frame,
        nên đây là điều kiện đúng-hay-sai, không phải tối ưu — xác nhận qua thiết kế
        `ensureAcquired()` async tách biệt khỏi `isOwner`/`ownerPodId`
  - [x] Redis không khả dụng lúc giành lease lần đầu (lỗi kết nối) → fallback về
        `ModuloRoomOwnership`, log cảnh báo — test `should_fallBackToProvidedOwnership_when_theStoreFailsWithAnException`
  - [x] Scale thêm pod giữa ca thi: cơ chế `NOT_OWNER` phía Gateway không cần sửa (đã đúng từ
        Task 5/10). **Cập nhật 2026-09-07 (Task 20):** `LeaseBasedRoomOwnership` **đã nối và chạy
        thật** qua `docker-compose.dev.yml` (`ENGINE_ROOM_STORE_ENABLED=true`, không phải giả định nữa
        — `EngineNetworkLifecycle` thật sự dựng `LeaseBasedRoomOwnership` khi cờ bật, xác nhận qua
        `redis-cli KEYS "room:owner:*"` sau khi 2 pod thật chạy). Production mặc định (`application.yml`)
        **vẫn** `ModuloRoomOwnership` — cờ chỉ bật ở compose test cục bộ, không đổi mặc định. Điều
        CHƯA test: thêm pod thứ 3 **giữa lúc** 2 pod kia đang chạy phòng thật (mid-session
        scale-up) — Task 20 khởi động cả 2 pod cùng lúc từ đầu, không mô phỏng scale động.
        **Cập nhật 2026-09-08 (rà lại Task 14, xem `_context.md` phát hiện B5):** đây **không chỉ
        là "chưa test"** — đã xác nhận bằng cách đọc code rằng kịch bản này **không thể hoạt động**
        với Gateway hiện tại, độc lập với Task 14. `GatewayNetworkLifecycle.run()` đọc
        `uni.gateway.engine.pods` đúng một lần lúc khởi động; `FrameChannelClient` không có cơ chế
        thêm pod mới sau đó. Một pod Engine mới hoàn toàn (không có trong `ENGINE_PODS` lúc Gateway
        boot) không nhận được gói tin nào, dù `LeaseBasedRoomOwnership` đã bật + đúng 100%. Đã mở
        `plan.md` Task 21 (mới) cho đúng gap này; AC "Scale thêm pod giữa ca thi" của Task 14 **chỉ
        tính là đóng cho phần Engine** (N không còn ý nghĩa với ownership) — phần Gateway (route
        được tới pod mới) thuộc phạm vi Task 21, không phải Task 14.
  - [x] Pod crash thật → pod khác giành lại + nạp snapshot — **Cập nhật 2026-09-08:** đóng bằng
        `DockerComposeChaosIT.should_recoverRoomOnAnotherPod_when_itsOwningEngineIsKilled` —
        join thật, xác nhận Hot Snapshot đã ghi (`redis-cli EXISTS room:snap:*`), `docker compose
        kill` đúng pod đang giữ lease, đợi hết lease TTL (20s, xem docker-compose.dev.yml), rồi
        join lại: pod sống sót giành được lease + full snapshot trả về đúng roster/display_name
        cũ (không phải phòng rỗng mới tinh). Quá trình viết chaos test này tự nó lộ ra **2 bug
        thật** chặn đứng chính kịch bản này — xem ghi chú riêng ngay dưới, cả hai đã sửa. Không
        verify được phục hồi ĐIỂM SỐ (Phase 1 không có wire message đưa phòng vào PLAYING từ
        ngoài JVM engine, giống hạn chế `DockerComposeResyncIT` đã nêu cho SUBMIT_ANSWER) — đúng
        phạm vi AC này (lease + roster), không phải toàn bộ chuỗi chấm điểm.
- **Acceptance criteria — phần Hot Snapshot (2026-09-07, tiếp tục code):**
  - [x] Ghi async, không trong đường xử lý đồng bộ của `RoomActor`/Netty EventLoop —
        `RoomActor.maybeSnapshot()` gọi `snapshotStore.save(...)` (trả `CompletableFuture`) và
        **không bao giờ `.get()`/`.join()`** — test
        `should_neverBlockOnASlowOrFailingStore` dùng store không bao giờ resolve, xác nhận
        message kế tiếp vẫn xử lý bình thường
  - [x] Payload đóng gói `{schema_version, epoch, crc32, payload}` đúng §5.8 —
        `SnapshotEnvelope.wrap`/`unwrap`, epoch lấy từ tham số `epoch` của `RoomActor` (nguồn:
        `LeaseBasedRoomOwnership.epochOf()`, chưa nối — xem "Chưa làm")
  - [x] Kích thước < 5 KB — `SnapshotEnvelope.MAX_ENVELOPE_BYTES`, `wrap()` trả `Optional.empty()`
        nếu vượt (không throw). Test `should_stayUnderFiveKilobytes_forARealisticFullRoom` (12
        học sinh, tên dài, câu hỏi dài) đo thật, không giả định
  - [x] `LastSeenSequenceTable` nằm trong `RoomState.serializeSnapshot()` — **và một bug đã sửa
        kèm theo**: `RoomState.submitAnswer` trước đây chỉ dedupe đúng khi có sẵn
        `lastAckByStudent` (ack gốc); một phòng phục hồi từ snapshot có `lastSeenSequence`
        nhưng KHÔNG có lịch sử ack đầy đủ (cố tình, để giữ < 5KB) → nộp trùng `sequence` sau khi
        phục hồi sẽ **chấm điểm lại lần hai**. Đã sửa: dùng `RejectReason.DUPLICATE_SEQUENCE`
        (đã có sẵn trong schema, chưa ai dùng) làm câu trả lời an toàn khi không có ack gốc.
        **Prove-it**: tạm bỏ nhánh sửa, xác nhận đúng 1 test Red trước khi trả lại Green
  - [x] CRC32 sai/thiếu/schema mismatch → `SnapshotEnvelope.unwrap` trả `Optional.empty()`,
        không throw, không crash actor — 4 test riêng (`SnapshotEnvelopeTest`)
  - [x] Redis không khả dụng → không chặn hot path — `maybeSnapshot()` chỉ `.exceptionally(...)`
        log cảnh báo, không có đường nào quay lại chặn actor
  - [x] Fencing bằng epoch (§5.8 "ghi snapshot cũ hơn → từ chối"): `DistributedRoomSnapshotStore.save`
        dùng Lua script so epoch với **cùng key** `room:epoch:{room_id}` mà
        `DistributedRoomLeaseStore` đã tăng — một nguồn sự thật epoch duy nhất cho cả lease lẫn
        snapshot, không tách hai bộ đếm riêng. **Chưa verify với Redis thật.**
  - [ ] Sau khi có implementation thật: sửa `system-architecture.md` ADR-002 GĐ1-note và §6.3 —
        **đã sửa một phần ở lượt review trước** (bỏ câu "10-50ms" vô căn cứ); số đo thật (10-50ms
        hay khác) vẫn cần benchmark thật, chưa làm — không tự ý điền số vào lại tài liệu
- **Cập nhật 2026-09-08 — 2 bug thật tìm ra khi viết `DockerComposeChaosIT` (chaos test kill+phục
  hồi qua Docker + Redis thật), cả hai đã sửa kèm test hồi quy + prove-it:**
  1. **`RoomSupervisor` chưa từng gọi `SnapshotEnvelope.unwrap()`.** `RoomSnapshotStore#load` trả
     nguyên envelope (schema_version/epoch/crc32 header + payload), nhưng `RoomSupervisor.onSnapshotLoaded`
     đưa thẳng bytes đó vào `RoomActor.create(...)`'s `restoreFromSnapshot` — tham số này đúng ra
     phải là payload BÊN TRONG (`RoomState.restore` không biết gì về envelope). Hậu quả: **mọi lần
     phục hồi thật từ Redis đều crash actor** (`ActorInitializationException`, `GamePhase` đọc ra
     chuỗi rỗng do lệch offset byte) — vô hình với `RoomActorSnapshotTest` vì bộ test đó luôn tự
     tạo `restoreFromSnapshot` bằng `RoomState.serializeSnapshot()` trực tiếp, chưa từng đi qua
     `SnapshotEnvelope.wrap`/`unwrap`. Sửa tại `RoomSupervisor.onSnapshotLoaded`: unwrap trước khi
     spawn, envelope lỗi/sai schema vẫn coi như rỗng (đúng §5.8, không throw). Test mới:
     `RoomSupervisorTest.should_restoreRosterFromAWrappedSnapshot_when_loadResolvesWithRealEnvelopeBytes`
     (dùng đúng `SnapshotEnvelope.wrap` + `RoomState.serializeSnapshot()` thật, không giả lập tắt).
  2. **`LeaseBasedRoomOwnership` cache một lần thua race giành lease là vĩnh viễn.**
     `ensureAcquired()` chỉ hỏi `cache.containsKey(roomId)` để quyết định bỏ qua — không phân biệt
     "mình đang giữ" với "người khác đang giữ, có thể đã hết hạn từ lâu". Một pod thua race một
     lần thì **không bao giờ thử giành lại**, kể cả sau khi pod thắng cuộc đã chết hẳn và TTL đã
     hết từ lâu — phá vỡ đúng lời hứa cốt lõi của Task 14 ("pod khác giành lại lease"). Sửa:
     `renewAll()` (đã chạy định kỳ mỗi `ttl/3` từ `EngineNetworkLifecycle`) giờ evict luôn các
     entry sở hữu bởi pod khác, không chỉ renew entry của chính mình — buộc `ensureAcquired` thử
     lại thật ở lần gọi kế tiếp, tái dùng đúng nhịp định kỳ sẵn có thay vì phát minh thêm cơ chế
     TTL/hẹn giờ riêng cho việc này. Test mới:
     `LeaseBasedRoomOwnershipTest.should_retryAcquisition_when_renewAllRunsAgain_forARoomOwnedByAnotherPod`.
  Cả hai lỗi chỉ lộ ra khi test thật qua Docker + Redis thật — 179 test đơn vị của Task 14 (kể cả
  bộ rất kỹ `LeaseBasedRoomOwnershipTest`/`RoomActorSnapshotTest`) chưa từng chạm tổ hợp "ghi qua
  store thật rồi đọc lại" hay "thua race → chờ hết hạn → giành lại", đúng đúng giới hạn mà
  `_context.md` mục B1 đã cảnh báo trước ("có code" ≠ "hết rủi ro", cần chaos test thật).
- **Verification:** Test giành lease đồng thời từ 2 "pod" giả (2 client Redis trỏ cùng key) →
  đúng 1 thắng, khớp `SETNX` semantics. Test hết hạn không renew (TTL cực ngắn trong test) → pod
  thứ hai giành được, epoch tăng đúng 1. Test crash + phục hồi: giết actor, spawn actor mới trên
  "pod" khác, xác nhận nạp đúng roster/score/`LastSeenSequenceTable` kèm epoch mới hơn epoch cũ.
  Đo thời gian phục hồi thật (phải tính bằng giây theo `ttl` đã chọn, không phải "chờ đúng pod cũ
  lên lại" như hiện tại), đối chiếu con số 10–50ms trong tài liệu — lệch thì sửa tài liệu.
- **Ghi chú:** Lần đầu Redis chạm code thật ở GĐ1, gộp cả B1 lẫn phần ownership mới — không tách
  2 task riêng vì dùng chung một Valkey Cluster và luôn đi cùng nhau về vận hành (giành lease xong
  luôn phải nạp snapshot ngay). Không mở rộng sang join token replay dedup (`SETNX` cho join token JWT) —
  đó là điểm gắn khác (handshake, không phải `RoomActor`). `plan.md` Task 19 (runbook cấm
  auto-scale) **vẫn cần thiết cho tới khi task này triển khai và verify bằng chaos test thật**
  (kill pod giữa trận, đo thời gian phục hồi) — không tự động gỡ ràng buộc vận hành chỉ vì code
  đã viết xong, phải chứng minh bằng thực nghiệm trước.
- **Rollback nếu fail:** revert; `ModuloRoomOwnership` (Task 10) tiếp tục là implementation duy
  nhất, đúng hành vi GĐ1 hiện tại — không regress.

---

### Task 15: `COMMITTED_SEQ` — tách tín hiệu discard RingBuffer khỏi `ANSWER_ACK` — đóng phát hiện B2 — ⚠️ PHẦN SERVER XONG, chờ PH-3 (2026-09-07)

- **Kết quả:** Thêm `CommittedSeq` (message mới, `repeated Entry {student_id, sequence}`) vào
  `game_message.proto`, `MessageType.COMMITTED_SEQ = 26`, field `committed_seq = 46` trong
  `oneof payload`. **Không phải `DeliveryClass.CRITICAL` như AC gốc dự tính** — đổi thành
  **Best-effort** (mặc định `DeliveryClassifier`, không thêm case riêng): mất một `CommittedSeq`
  tự lành ở lần ghi snapshot kế tiếp (epoch/sequence cao hơn sẽ bao trùm luôn), không cần đảm bảo
  gửi bằng mọi giá như `ANSWER_ACK`. `RoomState.lastSeenSequenceSnapshot()` (bản copy bất biến,
  lấy trên actor thread) + `RoomState.buildCommittedSeq(...)` (static, thuần) — tách biệt vì
  callback báo kết quả ghi Redis chạy trên **thread khác** actor thread, không được chạm lại
  `state`. `RoomActor.maybeSnapshot()` chỉ phát `CommittedSeq` khi `snapshotStore.save(...)` trả
  đúng `true`; `false`/exception đều im lặng bỏ qua lượt đó (đợi flush kế tiếp).

  **Bug tìm ra và sửa kèm theo, quan trọng**: `NoopRoomSnapshotStore` (mặc định khi Redis tắt) lúc
  đầu trả `true` cho mọi `save()` — nghĩa là ngay cả khi KHÔNG ghi gì cả, `RoomActor` vẫn phát
  `CommittedSeq` tuyên bố dữ liệu đã bền vững! Đây là **tín hiệu bền vững giả**, tệ hơn cả việc
  không có `CommittedSeq`. Sửa: `NoopRoomSnapshotStore.save()` giờ trả `false` (đúng nghĩa "chưa
  cam kết gì"), và log ở `RoomActor` đổi từ `warn` xuống `debug` (vì với cấu hình mặc định GĐ1,
  nhánh này chạy ở MỌI flush — `warn` sẽ spam log sản xuất cho một hành vi đúng-theo-thiết-kế).
  Test mới: 3 case trong `RoomActorSnapshotTest` (broadcast đúng khi thành công, im lặng khi lỗi,
  im lặng khi bị fencing) — **prove-it xác nhận đúng 2 test Red** khi tạm phát `CommittedSeq` vô
  điều kiện.

  `mvn clean install` toàn reactor: BUILD SUCCESS, **173 test**, không leak — xác nhận thêm là fix
  Noop **không** làm hỏng bất kỳ test nào khác đang dùng overload mặc định (`RoomActorTest`,
  `TickCoalescingTest`, `RoomSupervisorTest`, `WalkingSkeletonTest`) vốn có thể đã âm thầm nhận
  thêm message `CommittedSeq` giả nếu không sửa kịp.
- **Chưa làm:** Toàn bộ phần PH-3 thật (client sản xuất đổi điều kiện discard RingBuffer từ
  `ANSWER_ACK` sang `committed_seq`) — ngoài phạm vi service này, chưa có đội nhận việc. **Không
  được công bố SLA "mất dữ liệu = 0" tới khi client đổi điều kiện discard.** Task 20 (2026-09-07)
  đã dựng một client giả lập (`SimulatedStudentClient`, chỉ để test cục bộ) có RingBuffer + gửi
  `RESYNC` thật, và đã thêm xử lý `RESYNC` thật ở `RoomActor` (trước đây hoàn toàn chưa có handler
  nào) — nhưng client giả lập đó KHÔNG implement chính sách discard theo `committed_seq` (để
  `markAcked` cho caller tự quyết, xem javadoc), nên "Chưa làm" ở trên vẫn đúng nguyên văn.
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
  mới — hình dạng cụ thể **chưa chốt**, xem Acceptance criteria), `modules/uni-game-engine/.../room/RoomActor.java`
  (gửi tín hiệu mới sau khi `DistributedRoomSnapshotStore` của Task 14 xác nhận ghi xong)
- **Dependency:** Task 14 (cần sự kiện "ghi Redis xong" làm trigger); PH-3 (client phải đổi điều
  kiện discard — nằm ngoài phạm vi GĐ1 server nhưng bắt buộc phối hợp trước khi công bố SLA)
- **Acceptance criteria:**
  - [x] **`ANSWER_ACK` không bị trì hoãn dù chỉ một chút để chờ Redis** — `maybeSnapshot()` gọi
        sau `broadcastTarget.tell(state.flush())` trong `doFlush()`, không nằm trên đường xử lý
        `SubmitAnswer` → `ANSWER_ACK` (đường đó vẫn trả lời ngay trong `onSubmitAnswer`, không đổi)
  - [x] ~~bắt buộc `DeliveryClass.CRITICAL`~~ — **đổi quyết định**: dùng Best-effort (mặc định),
        không thêm case `COMMITTED_SEQ` vào `DeliveryClassifier`. Lý do: mất một `CommittedSeq`
        tự lành ở lần ghi thành công kế tiếp (chứa `lastSeenSequence` mới nhất, bao trùm cả những
        gì gói trước lẽ ra báo) — không cần cơ chế "không bao giờ drop" như `ANSWER_ACK`, vốn dành
        cho tín hiệu không tự lành được
  - [x] `RoomActor` không tự gửi tín hiệu này nếu `RoomSnapshotStore` báo lỗi/timeout hoặc bị
        fencing (`false`) — im lặng bỏ qua lượt đó, đợi lần flush kế tiếp thử lại. Test riêng cho
        cả hai nhánh (`should_notBroadcastCommittedSeq_whenTheSnapshotWriteFails`/`...WhenTheWriteIsFencedOut`)
  - [x] Đổi `.proto` đi đúng quy trình: thêm message/field mới, không sửa/xoá field cũ nào —
        `mvn -pl :uni-protocol clean install` xanh, không phá `GameMessageRoundTripTest`
  - [x] Cập nhật `_context.md`/ADR-003: "mất dữ liệu = 0" chỉ đúng khi **cả hai** điều kiện đạt —
        PH-3 xong **và** client dùng `committed_seq` làm điều kiện xoá RingBuffer. Đã thêm ghi chú
        `[!CAUTION]` vào ADR-003 (`system-architecture.md`) và sửa nguyên văn §4.7 bước 2 (đã bị
        một chỉnh sửa tay khác ghi nhầm `COMMITTED_SEQ` là Critical — hỏi lại người dùng, xác nhận
        **giữ Best-effort như code đã implement**, sửa tài liệu khớp code thay vì đảo ngược code)
- **Verification:** Test mô phỏng Redis lỗi (`DistributedRoomSnapshotStore` trả lỗi) → xác nhận
  `COMMITTED_SEQ` không được gửi cho lượt đó. Test tích hợp (`uni-game-engine` hoặc `uni-e2e`) xác nhận
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

### Task 16: `broadcast_seq` trên `RoomStateSnapshot` — đóng phát hiện B3 — ✅ XONG (2026-09-07)

- **Kết quả:** Thêm field `broadcast_seq = 7` vào `RoomStateSnapshot` (`game_message.proto`).
  `RoomState` giữ một counter `broadcastSeq` riêng mỗi phòng, tăng trong `buildSnapshotMessage()`
  — điểm dùng chung của cả `buildFullSnapshot()` (join) và `buildDeltaSnapshot()` (flush thường),
  nên full/delta/join đều chia sẻ đúng một dãy số liên tục, không reset theo loại gói. Đã nối vào
  Hot Snapshot (`serializeSnapshot()`/`restore()`) để **sống sót qua phục hồi** — phòng chuyển
  pod không bị "lùi" số thứ tự về 0 (test riêng `should_continueBroadcastSeq_notResetItToZero_afterRestore`).
  Test mới: 1 case trong `TickCoalescingTest` (tăng đơn điệu qua join + 2 flush, xác nhận
  `ANSWER_ACK` không tiêu tốn số) + 1 case trong `RoomStateSnapshotTest`. **Prove-it**: tạm dừng
  tăng counter, xác nhận đúng 2 test Red trước khi trả lại Green.
- **Mode:** sequential after [T3] (tick coalescing đã có luồng flush) · phối hợp với PH-3
- **Mô tả:** `_context.md` mục B3 ghi nhận: trường `sequence` trong envelope (`game_message.proto`
  dòng 34-36) là counter do **client** gán cho `SubmitAnswer` (dedupe, §5.2) — không phải số thứ
  tự do server gắn lên broadcast. `RoomStateSnapshot` hiện không mang bất kỳ số thứ tự/version
  nào, nên FE (kể cả sau khi PH-3 xong) không có cách nào tự phát hiện một gói delta broadcast bị
  rớt giữa đường (khác hẳn `SubmitAnswer`, đã có `sequence` + `ANSWER_ACK` để đối chiếu). Task này
  thêm một số thứ tự **do server gắn**, tăng dần mỗi lần phòng flush, để PH-3 có cơ sở thiết kế
  cơ chế phát hiện gap cho broadcast.
- **File dự kiến:** `modules/uni-protocol/src/main/proto/game_message.proto` (thêm field
  `broadcast_seq` vào `RoomStateSnapshot`), `modules/uni-game-engine/.../room/RoomState.java` (bộ đếm
  tăng dần mỗi lần `flush()`/`joinRoom()` phát ra một bản ghi)
- **Dependency:** Task 3 (cần luồng coalescing flush đã tồn tại để gắn số thứ tự vào đúng chỗ)
- **Acceptance criteria:**
  - [x] `RoomStateSnapshot.broadcast_seq` tăng dần đơn điệu **mỗi phòng riêng** (field riêng mỗi
        `RoomState` instance), tăng ở cả full snapshot (join/resync) lẫn delta (flush thường)
  - [x] Đổi `.proto` đi đúng quy trình: thêm field mới, không sửa field cũ
  - [x] Không đổi ngữ nghĩa `sequence` hiện có — `broadcast_seq` là trường **mới**, số 7, không
        đụng tới field `sequence` (số 5) hay bất kỳ field nào khác
  - [x] Test xác nhận tăng đơn điệu qua nhiều lần flush liên tiếp, không tiêu tốn số cho
        `ANSWER_ACK` (không phải `ROOM_STATE_SNAPSHOT`) — **chưa làm riêng case "bỏ N gói liên
        tiếp rồi kiểm tra client tính được khoảng cách"** vì đó là logic phía client (PH-3), Task
        này chỉ đảm bảo server phát đúng dãy số liên tục để client làm việc đó
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=TickCoalescingTest` mở rộng — case mới xác
  nhận `broadcast_seq` tăng đúng thứ tự qua nhiều lần flush liên tiếp và không reset giữa full
  snapshot với delta.
- **Ghi chú:** Đây là điều kiện cần, không phải đủ, cho RESYNC broadcast — PH-3 vẫn phải tự thiết
  kế phía client dùng `broadcast_seq` thế nào (gửi `RESYNC` khi phát hiện gap, hay chỉ chờ full
  snapshot định kỳ theo N=10 của G2a). Task này chỉ đảm bảo server có gì đó để client bám vào;
  không tự quyết định giao thức RESYNC cho broadcast thay PH-3.
- **Rollback nếu fail:** revert; hành vi hiện tại (không có số thứ tự trên broadcast) không đổi.

---

### Task 17: Nối `missed_step_policy` vào `RoomState`/`RoomActor` — đóng phát hiện B4 — ⚠️ MỘT PHẦN XONG (2 trong 2 lớp fail-fast, 2026-09-07)

- **Kết quả:** `DefinitionLoader` giờ từ chối lúc nạp bất kỳ `missed_step_policy` nào khác `ZERO`
  (`SKIP`/`ALLOW_LATE`), đúng pattern đã dùng cho `tick_mode: FIXED`. 2 test mới
  (`should_rejectAtLoadTime_when_missedStepPolicyIsSkip`/`...IsAllowLate`). Đóng đúng rủi ro cụ
  thể B4 nêu: từ nay **không thể** cấu hình một policy mà engine sẽ âm thầm không tuân theo.

  **Thêm lớp fail-fast thứ hai** (tiếp tục 2026-09-07), đúng đối xứng với `tickMode`:
  `RoomActor.create(...)` có overload mới (10 tham số, thêm `MissedStepPolicy`) từ chối bất kỳ
  giá trị nào khác `ZERO` — phòng trường hợp một caller tự spawn `RoomActor` bỏ qua
  `DefinitionLoader`. Không lưu thành field (giống hệt `tickMode` — vai trò duy nhất ở GĐ1 là cái
  cổng chặn này, chưa có nhánh logic nào cần đọc lại giá trị). Overload 6-tham-số và 9-tham-số cũ
  giữ nguyên, tự động mặc định `MissedStepPolicy.ZERO` khi gọi xuống — không phá bất kỳ call site
  nào (`RoomSupervisor`, `RoomActorTest`, spike). Test mới trong `TickCoalescingTest`
  (`should_rejectNonZeroMissedStepPolicy_when_creatingRoomActor_because_onlyZeroIsImplemented`) +
  prove-it xác nhận đúng 1 test Red. `mvn clean install` toàn reactor: BUILD SUCCESS, 174 test.
- **Chưa làm, có chủ đích, không phải quên — lý do:** grep xác nhận `GameDefinition` hoàn toàn
  **chưa được `RoomActor`/`RoomState` tiêu thụ ở bất kỳ điểm nào** (không chỉ `missedStepPolicy` —
  cả `steps`, `scoringFormula`, `maxTransitions` cũng vậy, đúng như Task 11 đã ghi chú). "Nối
  `RoomState` đọc và áp dụng `missedStepPolicy`" theo đúng nghĩa đen đòi hỏi phải có luồng
  late-join thật (§4.8) để áp dụng chính sách vào — luồng đó **chưa tồn tại** (không phân biệt
  "vào muộn" với "vào lần đầu" ở `RoomState.joinRoom`). Bịa ra một tham số `MissedStepPolicy`
  truyền vào `RoomActor.create(...)` chỉ để có gì đó "đọc" mà không có nhánh logic thật dùng tới
  sẽ là nối dây nửa vời — đúng loại việc dự án này đã nhất quán từ chối làm (Task 3/9's "không nối
  dây nửa vời trước khi có điểm gắn thật"). Việc còn lại của Task 17 (nối `RoomState` thật) nên
  gộp chung với một task tương lai wiring toàn bộ `GameDefinition` vào `RoomActor` — chưa có trong
  `plan.md`, cần thêm khi ai đó nhận việc late-join.
- **Mode:** sequential after [T11] (Game Definition đã có `MissedStepPolicy` trong schema)
- **Mô tả:** `_context.md` mục B4 ghi nhận: `RoomActor`/`RoomState` không hề tham chiếu
  `GameDefinition`/`MissedStepPolicy` — hành vi "0 điểm khi hết giờ không trả lời" hiện tại chỉ là
  tình cờ (điểm khởi tạo mặc định = 0), không phải policy `ZERO` được thực thi có chủ đích. Nếu
  cấu hình `SKIP` hoặc `ALLOW_LATE`, engine vẫn luôn hành xử như `ZERO`. Task này nối
  `GameDefinition.missedStepPolicy` vào logic chuyển câu thật.
- **File dự kiến:** `modules/uni-game-engine/src/main/java/.../room/RoomActor.java`,
  `.../room/RoomState.java` (đọc `missedStepPolicy` lúc chuyển sang câu kế tiếp/kết thúc game)
- **Dependency:** Task 11 (`GameDefinition`/`MissedStepPolicy` đã có trong schema)
- **Acceptance criteria:**
  - [ ] GĐ1 **chỉ hiện thực `ZERO`** (mặc định) — làm đúng, có chủ đích, thay vì tình cờ đúng như
        hiện tại. `RoomState` phải thật sự đọc `missedStepPolicy` và áp dụng, không chỉ nhận tham
        số rồi bỏ qua. **Chưa làm — xem "Chưa làm" ở trên, chờ luồng late-join thật (§4.8)**
  - [x] `SKIP`/`ALLOW_LATE` **fail-fast lúc nạp definition** ở GĐ1 — `DefinitionLoader` từ chối cả
        hai, đúng pattern `tick_mode: FIXED`
  - [ ] Test xác nhận điểm 0 đi qua đúng nhánh `missedStepPolicy` — **chưa làm**, phụ thuộc AC đầu
        tiên (chưa có nhánh nào để test qua)
  - [x] Test xác nhận: definition khai `missed_step_policy: SKIP` hoặc `ALLOW_LATE` → bị
        `DefinitionLoader` từ chối lúc nạp, không lọt tới `RoomActor`
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=DefinitionLoaderTest` (case mới: reject
  `SKIP`/`ALLOW_LATE`) + `mvn -pl :uni-game-engine test -Dtest=RoomActorTest` (case mới: không trả lời
  trước deadline → 0 điểm qua đúng nhánh `missedStepPolicy`, không phải qua giá trị mặc định tình
  cờ).
- **Ghi chú:** Không mở rộng sang hiện thực `SKIP`/`ALLOW_LATE` thật ở GĐ1 — `_context.md` §7.5
  câu 1 (`missed_step_policy` mặc định) vẫn còn treo phía Product, và `ALLOW_LATE` bị cấm dùng
  trong thi đấu theo chính tài liệu (§4.8). Task này chỉ đảm bảo `ZERO` chạy đúng nghĩa và các
  giá trị khác không lọt qua trong im lặng.
- **Rollback nếu fail:** revert; hành vi hiện tại ("0 điểm" tình cờ do giá trị mặc định) không đổi
  — không regress vì `ZERO` vẫn là kết quả quan sát được giống hệt trước task.

---

### Task 18: Kafka Event Streaming cách ly khỏi `RoomActor` — đóng §9.3 Rủi ro 6 — ✅ XONG (nối dây thật, 2026-09-07)

- **Kết quả:** `GameEventSink` (interface, async-agnostic — chạy trên worker thread riêng nên
  KHÔNG cần async như `RoomLeaseStore`/`RoomSnapshotStore`) + `GameEventPublisher` (hàng đợi
  `ArrayBlockingQueue` bounded + 1 thread daemon riêng tiêu thụ). `publish()` chỉ làm đúng 1 việc
  trên thread gọi: `queue.offer()` không chặn — hàng đầy thì drop + log + đếm
  (`droppedEventCount()`), không throw, không block. `KafkaGameEventSink` (thật, `max.block.ms=0`,
  `acks=1`, **chưa verify Kafka thật** — không có broker trong môi trường này, cùng caveat
  `DistributedRoomLeaseStore`). `kafka-clients` hoá ra **đã có sẵn transitively** qua
  `uni-observability` (dùng bởi `KafkaLogAppender` có từ trước) — khai báo tường minh thêm trong
  `uni-game-engine/pom.xml` vì code dùng trực tiếp, không chỉ kế thừa.

  Test: 4 case trong `GameEventPublisherTest` — đặc biệt `should_returnImmediately_evenWhenTheSinkIsStuck`
  (sink treo vĩnh viễn qua `CountDownLatch`, đo thời gian `publish()` phải < 500ms) và
  `should_dropAndReportFalse_whenTheQueueIsFull` (capacity=1, xác nhận đúng ngữ nghĩa "một cái
  đang xử lý, một cái xếp hàng, cái thứ ba bị drop"). **Prove-it**: tạm hardcode `offered=true` bất
  kể kết quả `offer()` thật, xác nhận đúng 1 test Red.
- **Kết quả (nối dây thật — tiếp tục 2026-09-07):** Hỏi người dùng cách xử lý việc `RoomActor`
  chưa có khái niệm `GameEvent`/`session_id` — quyết định: **dùng `room_id` làm partition key
  tạm** (thay `session_id` — trường không tồn tại trong data model hiện tại) và **serialize chính
  `AnswerAck`** (đã có sẵn) làm nội dung sự kiện, không bịa schema `GameEvent` mới. Nối vào
  `RoomActor.onSubmitAnswer`: publish đúng khi `ack.getAnswerAck().getAccepted() == true` (bỏ qua
  submit bị từ chối — không đáng đưa vào analytics). Thêm overload `RoomActor.create(...)` +
  `RoomSupervisor.create(...)` mới (additive, không phá overload cũ) nhận `GameEventPublisher`
  (nullable — bỏ qua publish nếu `null`, tránh phải tạo một publisher "no-op" giữ 1 thread nền vô
  ích). Nối vào `EngineNetworkLifecycle` sau cờ `uni.engine.kafka.enabled` (mặc định `false`,
  cùng lý do `room-store.enabled` — không có Kafka broker trong môi trường này để verify).
  `NoopRoomSnapshotStore` đổi từ package-private sang `public` để `EngineNetworkLifecycle` (khác
  package) có thể dùng làm `RoomSnapshotStore` mặc định khi chỉ bật Kafka mà không bật Redis.

  Test mới: `RoomActorGameEventTest` (3 case — publish đúng khi accept, im lặng khi reject, không
  throw khi publisher = null) + prove-it xác nhận đúng 1 test Red. Xác nhận bằng
  `EngineApplicationTests` (Spring context thật) — cả 2 cờ tắt log đúng, không chạm Redis/Kafka.
  `mvn clean install` toàn reactor: BUILD SUCCESS, **177 test**, không leak.
- **Chưa làm/giới hạn đã biết:** Dùng `room_id` thay `session_id` là quyết định tạm — cần sửa lại
  khi `session_id` trở thành khái niệm thật (tích hợp với dịch vụ nền tảng/matchmaking). Publish
  mỗi lần accept (kể cả nộp trùng `sequence` được replay ack cũ) có thể double-publish cho phân
  tích — chấp nhận được vì downstream consumer có thể dedupe theo `(student_id, acked_sequence)`.
- **Cập nhật 2026-09-07 (Task 20):** `KafkaGameEventSink` **đã verify với Kafka thật** lần đầu
  (single-broker KRaft qua `docker-compose.dev.yml`), qua `KafkaGameEventSinkDockerIT`. Verify này
  phát hiện một bug thật, đã sửa: `max.block.ms=0` khiến **sự kiện ĐẦU TIÊN sau mỗi lần khởi động
  producer luôn bị rớt âm thầm** (metadata của topic chưa có sẵn, không có 0ms nào để chờ fetch) —
  không phải hiện tượng riêng của môi trường Docker mà xảy ra ở MỌI lần engine pod (re)start thật.
  Sửa bằng `KafkaGameEventSink.warmUpMetadata()`: gọi `partitionsFor(topic)` lặp lại (tối đa 5s,
  một lần lúc khởi tạo, không đụng thread dispatcher hay thread publisher) để buộc lần fetch
  metadata nền của producer hoàn tất trước khi có sự kiện thật nào cần gửi. Đồng thời thêm
  callback log cho `producer.send(...)` — trước đó lỗi bất đồng bộ (khác lỗi đồng bộ
  `GameEventPublisher.runLoop()` đã bắt) hoàn toàn im lặng, không có log nào.
- **Mode:** sequential after [T13] · song song được với Task 14–17
- **Mô tả:** `system-architecture.md` §9.3 "Rủi ro 6" đã mô tả đúng cơ chế nghẽn nếu làm sai:
  `RoomActor` gọi `KafkaProducer.send()` trực tiếp để đẩy `GameEvent` (§4.3 bước 5) → nếu Kafka
  cluster lag, buffer producer đầy → cấu hình mặc định sẽ **block** `send()` → actor dispatcher
  thread bị chặn → **mailbox đóng băng → cả phòng đứng hình**. Đây chính là lý do đưa Kafka vào
  đường xử lý đồng bộ của `RoomActor`/Netty EventLoop là anti-pattern (đã giải thích chi tiết
  trong hội thoại review kiến trúc 2026-09-07). Task này hiện thực đúng 3 lớp cách ly mà §9.3 đã
  yêu cầu, trước khi `RoomActor` lần đầu chạm Kafka thật.
- **File dự kiến:** `modules/uni-game-engine/pom.xml` (thêm dependency `kafka-clients` — chưa có),
  `modules/uni-game-engine/src/main/java/.../events/GameEventPublisher.java` (mới, hàng đợi bounded +
  thread tiêu thụ riêng), `.../room/RoomActor.java` (sửa: gọi `enqueue(...)` non-blocking thay vì
  gọi Kafka API trực tiếp)
- **Dependency:** Task 13 (cần `RoomActor`/`RoomSupervisor` đã nối dây thật để có sự kiện thật mà
  đẩy)
- **Acceptance criteria:**
  - [x] `KafkaProducer` cấu hình **`max.block.ms = 0`, `acks = 1`** — `KafkaGameEventSink`
  - [x] `RoomActor` **không bao giờ gọi Kafka API trực tiếp** — `onSubmitAnswer` chỉ gọi
        `gameEventPublisher.publish(roomId, ack.toByteArray())` (non-blocking); thread riêng
        (`GameEventPublisher`'s worker) mới thật sự gọi `KafkaProducer.send()`
  - [x] Hàng đợi đầy → drop, log, đếm (`droppedEventCount()`) — không throw, không block
  - [x] ~~`partition key = session_id`~~ — **đổi quyết định** (hỏi người dùng 2026-09-07): dùng
        `room_id` làm partition key tạm, vì `session_id` không tồn tại trong data model hiện tại.
        Ghi rõ trong code + `plan.md` là quyết định tạm, cần sửa khi có khái niệm session thật
  - [x] Đo `actor_processing_latency` không đổi khi có Kafka — gián tiếp qua
        `RoomActorGameEventTest` dùng `BehaviorTestKit` (đồng bộ): nếu `publish()` từng block, các
        test này sẽ treo/timeout thay vì pass trong < 1s như kết quả thật
  - [x] Test riêng: hàng đợi đầy → drop có log + đếm tăng, publisher xử lý sự kiện kế tiếp bình
        thường (`should_swallowASinkException_andKeepProcessingLaterEvents`)
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=GameEventPublisherTest` (logic hàng đợi
  thuần, không cần Kafka thật) + test tích hợp dùng fake/mock producer chặn `send()` để chứng
  minh `RoomActor` không bị kéo theo — đúng tinh thần prove-it đã dùng ở Task 8/9 (chủ động gây
  lỗi trước khi tin code đúng).
- **Ghi chú:** Đây là lần đầu Kafka chạm code thật ở GĐ1 — đúng phạm vi đã chốt ở `_context.md`
  ("Kafka Cluster (Event Streaming)" nằm trong scope GĐ1, `game.events.v1`). Không mở rộng sang
  viết PostgreSQL batch writer hay Teacher Dashboard consumer đọc từ Kafka — cả hai nằm trong lộ
  trình GĐ2 (`system-architecture.md` §18, mục 2–3), không phải task này.
- **Rollback nếu fail:** revert; `RoomActor` tiếp tục không đẩy `GameEvent` nào ra Kafka (đúng
  hành vi hiện tại) — không regress vì tính năng chưa từng tồn tại trước task này.

---

### Task 19: Runbook vận hành — cấm Auto-scaling Engine trong ca thi đấu — đóng §9.2 Rủi ro 4 phần "Scale pod làm vỡ Modulo Hash" — ✅ XONG (2026-09-07)

- **Kết quả:** [`docs/runbook/engine-scaling-freeze.md`](../../runbook/engine-scaling-freeze.md)
  viết xong — khung giờ cấm (18h50-21h30), checklist DevOps ký xác nhận, và điều kiện nới lỏng rõ
  ràng (cả 2: Task 14 chạy ổn định với Redis thật + chaos test xác nhận). Đã nối link 2 chiều với
  `_context.md` (mục cảnh báo pod-loss).
- **Mode:** parallel — thuần tài liệu vận hành, không phụ thuộc/chặn task code nào
- **Mô tả:** **Đây không phải bug sửa được bằng code trong GĐ1.** `ModuloRoomOwnership`
  (`room_id % N`) dùng danh sách pod **tĩnh** (`ENGINE_POD_COUNT`/`ENGINE_POD_ID`, cố định lúc
  khởi động — xem `application.yml` của `uni-game-engine`), cô lập có chủ đích sau interface
  `RoomOwnership` (ADR-007) đúng để GĐ2 thay bằng Cluster Sharding. Việc "sửa" thật là chuyển
  sang Redis Lease hoặc Cluster Sharding — nằm ngoài phạm vi GĐ1 theo chính `_context.md`. Rủi ro
  cụ thể bạn nêu (scale Engine giữa ca thi đấu làm vỡ hash) **đã được ghi nhận sẵn** ở
  `system-architecture.md` §9.3 Rủi ro 6 và ADR-002 ("Cấm HPA theo CPU cho Engine"), chủ sở hữu
  đã ghi là DevOps/SRE — nhưng hiện tại **chưa có gì trong repo để enforce nó về mặt hạ tầng**:
  không có Dockerfile, không có K8s manifest, không có `docker-compose.dev.yml` nào (Docker
  daemon không chạy được trong môi trường viết code này — xác nhận ở Task 13). Task này biến quy
  tắc từ một dòng trong bảng rủi ro thành một **checklist go/no-go cụ thể** mà DevOps phải xác
  nhận trước khi mở ca thi đấu.
- **File dự kiến:** `docs/runbook/engine-scaling-freeze.md` (mới)
- **Dependency:** none
- **Acceptance criteria:**
  - [x] Runbook nêu rõ khung giờ cấm tuyệt đối 18h50–21h30 và 4 hành vi cấm cụ thể
  - [x] Runbook giải thích vì sao bằng đúng cơ chế kỹ thuật (config tĩnh, rehash toàn bộ)
  - [x] Checklist DevOps ký xác nhận trước ca thi đấu (3 mục)
  - [x] Link 2 chiều với `_context.md`
  - [x] Không viết K8s manifest/HPA YAML nào — đúng như cam kết
- **Verification:** Đọc lại bằng mắt (tài liệu vận hành thuần, không có test tự động) + xác nhận
  link hai chiều giữa runbook và `_context.md` đúng, không link chết.
- **Ghi chú:** Khi nào có K8s manifest thật cho Engine (task tương lai, cần cluster để verify),
  manifest đó nên nêu rõ bằng comment là **cố tình không có** tài nguyên `HorizontalPodAutoscaler`
  nào — nhưng viết manifest đó không thuộc phạm vi task này. **Task này là lưới an toàn tạm
  thời** — một khi Task 14 (`LeaseBasedRoomOwnership`) triển khai xong **và** được verify bằng
  chaos test thật (kill/scale pod giữa trận, đo thời gian phục hồi), ràng buộc "cấm auto-scale"
  có thể nới lỏng; runbook lúc đó nên đổi thành hướng dẫn vận hành lease (theo dõi
  `zombie_actor_stopped_total`, TTL/renewal) thay vì cấm tuyệt đối.
- **Rollback nếu fail:** revert; chỉ là tài liệu, không ảnh hưởng code hay build.

---

### Task 20: Hạ tầng Docker test cục bộ — giả lập PH-1 (đa pod), PH-3 (client resync), G1a/G1c (dev join token) — ✅ XONG (2026-09-07, yêu cầu người dùng: "Dựng dockerfile giả lập PH-1, PH-3, G1a/G1 để test ở local")

- **Bối cảnh:** Docker daemon lần đầu chạy được trong phiên này (trước đó luôn không sẵn sàng —
  Task 13/19 đều ghi nhận điều này). Người dùng yêu cầu dựng hạ tầng Docker để test cục bộ toàn hệ
  thống, chấp nhận bỏ qua test tải (PH-1's load-test harness), nhưng code phải đáp ứng đúng nghiệp
  vụ (không phải giả lập rỗng).
- **Kết quả:**
  - **G1a/G1c (dev-only, KHÔNG bao giờ dùng ở staging/production):**
    `modules/uni-websocket-gateway/src/main/java/.../auth/dev/DevJoinTokenCodec.java` (HMAC-SHA256, ký/verify
    join token dev cục bộ, không liên quan gì tới thuật toán ký thật mà platform team sẽ chốt),
    `DevJoinTokenReplayGuard.java` (in-memory, single-pod, KHÔNG phải cơ chế chống replay thật —
    xem javadoc), `DevJoinTokenVerifier.java` (bean `JoinTokenVerifier` đầu tiên từng tồn tại trong
    repo, khoá kép `@Profile("dev-docker")` **và** `uni.gateway.dev-join-token.enabled=true`). Đăng
    ký bean này tự động mở `GatewayNetworkLifecycle` (vốn `@ConditionalOnBean(JoinTokenVerifier.class)`)
    — đúng như javadoc của lớp đó đã hứa "chỉ cần thêm 1 bean".
  - **Bug tiềm ẩn phát hiện khi bean `JoinTokenVerifier` đầu tiên xuất hiện:** `application.yml` của
    `uni-websocket-gateway` định nghĩa `uni.engine.pods` (ngang cấp `uni.gateway`), nhưng
    `GatewayNetworkLifecycle` đọc `@Value("${uni.gateway.engine.pods}")` — sai đường dẫn, chưa bao
    giờ lộ ra vì `GatewayNetworkLifecycle` chưa từng được khởi tạo. Đã sửa: lồng `engine.pods` vào
    đúng `uni.gateway.engine.pods`.
  - **PH-3 (RESYNC thật ở server, không phải state FSM mới):** `RoomActor` thêm command `Resync`
    + handler `onResync` — replay từng `pending` qua đúng `RoomState.submitAnswer` (dedupe theo
    `lastSeenSequence` tái dùng nguyên vẹn, không code mới), rồi trả `RoomState.resyncSnapshot`
    (full snapshot cá nhân). `RoomSupervisor.onDispatch` thêm case `RESYNC`. **Không thêm
    `GamePhase.RESYNCING`** — đúng ghi chú kiến trúc "GĐ1 FSM chỉ có LOBBY→PLAYING→FINISHED".
    Test `RoomActorResyncTest` (in-process, real socket) chứng minh: replay không double-score,
    ack trùng `sequence` trả về đúng ack gốc nguyên văn (§5.2), học sinh khác không bị ảnh hưởng.
  - **PH-3 (client giả lập, chỉ để test, không phải sản phẩm):**
    `modules/uni-e2e/.../support/SimulatedStudentClient.java` — trích xuất + mở rộng
    `WalkingSkeletonTest`'s `WsTestClient` cũ (đã xoá, thay bằng lớp dùng chung này): RingBuffer
    10 phần tử, `sequence` client-side tăng dần, `simulateDisconnectAndReconnect` gửi `RESYNC`
    thật. `joinRoomWithRetry` — xem "Phát hiện quan trọng" bên dưới.
  - **Dockerfiles + compose:** `modules/uni-websocket-gateway/Dockerfile`, `modules/uni-game-engine/Dockerfile`
    (runtime-only, COPY jar `-exec` đã build sẵn trên host, không build Maven trong Docker) +
    `docker-compose.dev.yml` ở root (project name riêng `uni-realtime-dev`, 1 gateway + 2 engine
    pod thật + Redis 7 + Kafka KRaft đơn broker, healthcheck TCP `/dev/tcp` trên port 9100 vì
    image JRE không có curl/wget và actuator port lên trước ApplicationRunner mở frame-channel).
  - **Đã CHẠY THẬT, không chỉ viết:** `mvn clean package -DskipTests` → `docker compose up -d
    --build` → cả 5 container `Up`/`healthy` → `DockerComposeResyncIT` (gated `RUN_DOCKER_IT=true`,
    không chạy trong `mvn clean install` thường) join 2 phòng băm về 2 pod khác nhau, disconnect +
    RESYNC thật qua mạng Docker thật — **PASS**. `redis-cli KEYS "*"` xác nhận `room:owner:*`,
    `room:epoch:*`, `room:snap:*` — **lần đầu `DistributedRoomLeaseStore`/`DistributedRoomSnapshotStore` chạm
    Redis thật**. `KafkaGameEventSinkDockerIT` (mới, `uni-game-engine`) xác nhận `KafkaGameEventSink`
    gửi được tới Kafka thật — **lần đầu chạm Kafka thật**.
  - **Phát hiện quan trọng #1 (đã có giải pháp, KHÔNG sửa code sản phẩm):** JOIN_ROOM đầu tiên của
    một phòng mới có thể rơi vào sai pod (gateway đoán round-robin khi chưa học route, §4.5 bước
    2). `RoomOwnershipHandler`'s javadoc **đã ghi rõ, có chủ đích**: GĐ1 không forward nội bộ giữa
    2 pod Engine (khác với câu chữ literal của system-architecture.md §4.5 bước 2 — "pod nhận sẽ
    tự forward" — code đã chọn phương án khác: "client/Gateway retry cycle đã dung thứ điều này"),
    dựa vào giả định client tự retry. Với chỉ 1 pod Engine (mọi test real-socket trước Task 20),
    tình huống này **không thể xảy ra** — Task 20 là lần đầu 2+ pod Engine chạy cùng lúc trong bất
    kỳ test nào của repo, nên đây là lần đầu bug này lộ ra. Giải pháp: `joinRoomWithRetry` trong
    `SimulatedStudentClient` (client tự gửi lại JOIN_ROOM tối đa 5 lần, mỗi lần chờ 2s) — đúng với
    giả định "client retry" mà code đã chọn, không sửa `RoomOwnershipHandler`/Gateway.
  - **Phát hiện quan trọng #2 (đã sửa code sản phẩm):** `KafkaGameEventSink` với `max.block.ms=0`
    khiến **sự kiện đầu tiên sau mỗi lần khởi tạo producer luôn rớt âm thầm** (metadata topic chưa
    có, 0ms không đủ để fetch) — xảy ra ở MỌI lần engine pod thật (re)start, không chỉ Docker cục
    bộ. Sửa: `warmUpMetadata()` (retry `partitionsFor` tối đa 5s lúc khởi tạo, không đụng thread
    actor/publisher) + callback log lỗi bất đồng bộ trên `producer.send(...)` (trước đó im lặng
    hoàn toàn — xem chi tiết ở mục "Chưa làm" của Task 18).
  - **Sự cố ngoài ý muốn (đã báo cho người dùng ngay khi phát hiện):** Lần chạy `docker compose up`
    đầu tiên dùng project name mặc định (trùng tên thư mục `k12-platform-beta`) — cờ
    `--remove-orphans` đã xoá vài container KHÔNG liên quan tới repo này (mysql, mongodb, kafka-ui,
    4 exporter, đã dừng 10-13 ngày, không có compose file nào của chúng trong repo). Đã xác nhận
    volume dữ liệu (`k12-platform-beta_mysql-data`, `_mongodb-data`) và toàn bộ image vẫn còn
    nguyên — chỉ container bị xoá, dữ liệu chưa mất. Đã sửa gốc: `docker-compose.dev.yml` giờ có
    `name: uni-realtime-dev` cố định, không bao giờ đụng namespace mặc định của thư mục nữa.
  - `mvn clean install` toàn reactor: BUILD SUCCESS, **180 test**, không leak (2 Docker IT tách
    riêng, không chạy trong build thường).
- **Chưa làm / giới hạn đã biết:**
  - PH-1 (load-test harness thật) — **cố tình bỏ qua theo đúng yêu cầu người dùng**. Compose file
    2 pod Engine chỉ là bằng chứng hạ tầng đa pod tồn tại được, không phải công cụ đo tải.
  - PH-3 client giả lập KHÔNG implement chính sách discard RingBuffer theo `COMMITTED_SEQ` (Task
    15's "Chưa làm" vẫn còn nguyên) — `markAcked` để caller tự quyết định gọi khi nào.
  - `DockerComposeResyncIT` không thể lái phòng vào `PLAYING` (không có wire message nào cho việc
    này ở GĐ1 — xem `WalkingSkeletonTest`), nên không chứng minh lại "không double-score qua
    RESYNC" bằng hạ tầng Docker thật — phần đó do `RoomActorResyncTest` (in-process) đảm nhiệm.
  - Vẫn không có K8s manifest nào — ngoài phạm vi "test cục bộ".
- **File mới/sửa chính:** xem "Kết quả" ở trên; danh sách đầy đủ:
  `docker-compose.dev.yml`, `modules/uni-websocket-gateway/Dockerfile`, `modules/uni-game-engine/Dockerfile`,
  `modules/uni-websocket-gateway/src/main/java/.../auth/dev/{DevJoinTokenCodec,DevJoinTokenReplayGuard,DevJoinTokenVerifier}.java`,
  `modules/uni-websocket-gateway/src/main/resources/{application.yml,application-dev-docker.yml}`,
  `modules/uni-game-engine/src/main/java/.../room/{RoomActor,RoomState,RoomSupervisor}.java`,
  `modules/uni-game-engine/src/main/java/.../persistence/KafkaGameEventSink.java`,
  `modules/uni-e2e/src/test/java/.../support/SimulatedStudentClient.java` (mới),
  `modules/uni-e2e/src/test/java/.../{WalkingSkeletonTest,RoomActorResyncTest}.java`,
  `modules/uni-e2e/src/test/java/.../docker/DockerComposeResyncIT.java` (mới),
  `modules/uni-game-engine/src/test/java/.../persistence/KafkaGameEventSinkDockerIT.java` (mới).
- **Verification:** xem "Kết quả" — mọi bước đều CHẠY THẬT (build jar → docker compose up →
  actuator health → 2 Docker IT chạy pass → `redis-cli`/Kafka consumer xác nhận dữ liệu thật),
  không dừng ở mức viết code/đọc bằng mắt.
- **Rollback nếu fail:** revert toàn bộ file Task 20; `RoomActor`/`RoomSupervisor` quay lại không
  xử lý `RESYNC` (hành vi cũ), Gateway quay lại không mở port WS nếu không có `JoinTokenVerifier`
  bean nào khác được thêm.

---

### Task 21: Gateway dynamic Engine-pod discovery — đóng phát hiện B5, phần Gateway của §9.2 Rủi ro 4 — 🆕 CHƯA LÀM (mở ra 2026-09-08, rà lại Task 14)

- **Mô tả:** `_context.md` mục B5 ghi nhận: `LeaseBasedRoomOwnership` (Task 14) làm quyền sở hữu
  phòng ở Engine hết phụ thuộc vào `N`, nhưng Gateway vẫn chặn đúng kịch bản "SRE thêm pod Engine
  giữa ca thi" theo một cơ chế hoàn toàn khác. `GatewayNetworkLifecycle.run()` đọc
  `uni.gateway.engine.pods` (cấu hình tĩnh, comma-separated `host:port`) đúng **một lần** lúc Spring
  Boot khởi động, gọi `FrameChannelClient.connect(...)` **đồng bộ** cho từng entry — một pod không
  có trong danh sách lúc boot **không bao giờ** được dial. `FrameChannelClient.knownPods`/
  `podChannels` chỉ co lại khi pod ngắt kết nối (`handleDisconnect`), không có chiều ngược lại.
  Muốn thêm năng lực Engine thật sự hiện chỉ có một cách: khởi động lại Gateway với danh sách pod
  mới — nhưng khởi động lại Gateway tự đóng mọi WebSocket đang mở trên đúng pod đó (vi phạm tinh
  thần §9.7: mất 1 Engine pod không được đóng socket; mất 1 Gateway pod thì đương nhiên đóng, vì
  socket vật lý nằm trên chính process đó).
- **File dự kiến:** `modules/uni-websocket-gateway/src/main/java/.../boot/GatewayNetworkLifecycle.java`,
  `.../routing/FrameChannelClient.java` (cần thêm đường thêm pod mới sau khi đã `run()`, không chỉ
  lúc khởi tạo), cấu hình mới (ví dụ polling `uni.gateway.engine.pods` định kỳ, hoặc service
  discovery thật qua DNS/K8s API — chưa chốt phương án, xem "Phương án cân nhắc" bên dưới).
- **Dependency:** Task 14 (đã xong phần Engine — Task 21 chỉ còn thiếu phần Gateway để hai thứ
  cộng lại mới thành "scale-up giữa phiên an toàn" theo đúng nghĩa vận hành).
- **Phương án cân nhắc (chưa chốt, cần quyết định trước khi code):**
  1. **Poll lại config định kỳ:** Gateway tự đọc lại `uni.gateway.engine.pods` mỗi N giây, dial
     thêm pod mới xuất hiện trong danh sách. Đơn giản nhất, không cần hạ tầng ngoài, nhưng vẫn cần
     ai đó cập nhật config (file/env var) và Gateway phải hỗ trợ đọc lại config đang chạy (Spring
     `@RefreshScope` hoặc tự poll file) — **không tự phát hiện** pod mới nếu không ai sửa config.
  2. **Service discovery thật (DNS/K8s Endpoints API):** Gateway tự hỏi K8s "Service nào có
     Endpoint nào" theo chu kỳ, tự dial pod mới xuất hiện mà không cần sửa config thủ công. Đúng
     hướng dài hạn nhưng kéo theo phụ thuộc K8s API/DNS mới, không nhẹ.
  3. **Cluster Sharding đầy đủ (ADR-002, GĐ2):** giải quyết đồng thời cả bài toán Gateway lẫn
     Engine bằng một cơ chế cluster-native duy nhất, nhưng chi phí triển khai lớn hơn nhiều so với
     (1)/(2) chỉ để đóng riêng gap này — xem `system-architecture.md` §7.6 ghi chú 2026-09-08.
  **Không tự chọn phương án ở đây** — cần quyết định kỹ thuật (ai vận hành, có K8s API access từ
  Gateway pod hay không) trước khi bắt đầu code, giống tinh thần G1a/G1c/G2a/G2b đã áp dụng cho
  các quyết định ngoài tầm kiểm soát nội bộ.
- **Acceptance criteria:**
  - [ ] Gateway phát hiện được (bằng phương án đã chốt ở trên) một Engine pod khởi động **sau**
        khi Gateway đã chạy, không cần khởi động lại Gateway
      - [ ] Pod mới bắt đầu nhận được lưu lượng (round-robin cho room mới) trong một khoảng thời
            gian giới hạn, đo được cụ thể (không phải "cuối cùng thì có")
      - [ ] Room đang chạy trên pod cũ **không bị gián đoạn** trong lúc Gateway phát hiện pod mới
            (không đóng socket, không mất route đang có trong `RouteCache`)
  - [ ] `DockerComposeScaleUpIT.should_routeNewRoomsToAFreshlyStartedEnginePod_withoutRestartingGateway`
        (`modules/uni-e2e`, đã viết sẵn ở dạng `@Disabled` — xem javadoc class đó) **bỏ `@Disabled`,
        chạy pass thật** qua `docker-compose.dev.yml` (`engine-2` đã có sẵn trong compose, cố tình
        không nằm trong `ENGINE_PODS` của `gateway`/`gateway-1`) — không sửa assertion, chỉ gỡ annotation
  - [ ] Sau khi AC trên pass: cập nhật `system-architecture.md` §9.2 Rủi ro 4 + §7.6 (đánh dấu gap
        Gateway đã đóng) và `_context.md` phát hiện B5 (đổi trạng thái từ "chưa xử lý" sang "xong")
  - [ ] `plan.md` Task 19 (runbook cấm auto-scale) chỉ được nới lỏng sau khi **cả** Task 14 (bật
        production + verify staging thật) **và** Task 21 đều xong — không tự động gỡ ràng buộc chỉ
        vì một trong hai đã xong
- **Verification:** chạy `DockerComposeScaleUpIT` thật (không phải chỉ đọc code), đo thời gian từ
  lúc `docker compose up -d engine-2` tới lúc room đầu tiên route được sang pod đó.
- **Rollback nếu fail:** revert; Gateway giữ hành vi tĩnh hiện tại (danh sách pod cố định lúc boot),
  Task 19 (runbook cấm scale) tiếp tục là lưới an toàn duy nhất — không regress so với hiện trạng.

---

## Pre-merge Checklist

- [x] Tất cả task pass verification (T6 chờ G1a/G1c ngoài tầm kiểm soát nội bộ; T9 có giới hạn
      kiến trúc đã ghi rõ; T13 một phần — xem ghi chú Task 13)
- [x] `mvn clean install` sạch từ root (**180 test**, BUILD SUCCESS — cập nhật 2026-09-07, số
      119 cũ đã lỗi thời từ trước Task 14; 2 test tích hợp Docker của Task 20 tách riêng, không
      tính vào con số này vì không chạy trong build thường)
- [x] Test suite chạy với `-Dio.netty.leakDetection.level=paranoid`, **không có leak**
- [x] `grep -rn "client_timestamp_ms" modules/uni-game-engine/src/main --include=*.java` → **không hit nào trong đường chấm điểm** (tái xác nhận 2026-09-07)
- [x] `grep -rn "\.retain()" modules/uni-websocket-gateway/src/main --include=*.java` → **không hit nào trong vòng fan-out** (tái xác nhận 2026-09-07)
- [x] Không có TODO/FIXME chưa resolve trong code mới
- [x] SPIKE đã có kết luận và T3 khớp với kết luận đó
- [ ] `_context.md` cập nhật `dev_selftest` và `phase` — vẫn `phase: dev`, `dev_selftest: pending`
      có chủ đích: T13 chưa xong theo đúng nghĩa đen AC gốc (kịch bản giết pod thực nghiệm + 2
      Gateway pod thật vẫn thiếu — xem Task 13 đã cập nhật 2026-09-07), nên chưa tới điểm
      ship-ready. Docker Compose (một phần AC gốc) **đã có** từ Task 20, nhưng đó một mình không
      đủ để đổi `phase` — hai sub-AC còn lại (kill pod, 2 GW) mới là điều kiện thật.

> [!NOTE]
> **Cập nhật 2026-09-07:** framework kit (`scripts/governance-check.sh` và 5 gate con) **đã được
> cài** (skill `onboarding`, xem `CLAUDE.md` mục Governance) và **chạy PASS cả 5** —
> `bash scripts/governance-check.sh` xác nhận lại ngày này. Đoạn ghi chú gốc bên dưới ("không
> chạy được") đã lỗi thời, viết từ trước khi kit được cài; giữ nguyên văn bên dưới chỉ để tránh
> mất lịch sử, không phải hiện trạng. Lưu ý governance gate xanh **không thay thế** checklist
> build/test thật ở trên — 2 gate (`trace`, `ship`) xanh phần lớn vì "chưa có gì để kiểm" (BDD
> mới chỉ có cho INCLASS-GAME, chưa có `@trace` cho code GĐ1; ship gate chỉ đọc `phase=dev`).

> [!NOTE]
> **Các gate của framework kit không chạy được trong repo này** — `scripts/governance-check.sh`,
> `validate-sdd-gate.sh`, `validate-trace.sh` đều không tồn tại (xem `_context.md`).
> Checklist trên dùng lệnh build/test thật thay thế. Muốn có gate thật thì chạy skill
> `onboarding` trước, đừng đánh dấu các dòng đó là pass khi script không tồn tại.
