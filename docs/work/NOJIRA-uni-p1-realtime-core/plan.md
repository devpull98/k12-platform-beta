# Plan: Uni Realtime Giai đoạn 1 — WebSocket Gateway + Game Engine

<!--
Lưu tại:      docs/work/NOJIRA-uni-p1-realtime-core/plan.md
Tech design:  docs/specs/tech-design/EdTech_Game_Realtime_Architecture_v3.0.md
Entry point:  _context.md
-->

---
uc_id: NOJIRA-uni-p1
track: standard
size: L
parallel_safe: true
---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation | Trạng thái |
|---|---|---|---|---|
| **Pekko scheduler không chịu nổi ~1.000 timer/pod** | Med | High | Spike 4h: p99 = 36ms (<50ms), CPU 5% (<30%) → Giữ single-shot timer per actor | ✅ Đạt |
| **Dùng `retain()` thay `retainedDuplicate()`** | Med | High | Test 12 client + Netty paranoid leak detection | ✅ Đạt |
| **Backpressure 2 tầng gây nghẽn** | Med | Med | Cơ chế 1 tầng duy nhất qua socket writability (`WRITE_BUFFER_WATER_MARK`) | ✅ Đạt (3 hop) |
| **Mất Engine pod = mất phòng** | High | High | `LeaseBasedRoomOwnership` + Valkey Hot Snapshot (<5KB) | ✅ Code xong |
| **Scale Engine pod vỡ modulo hash** | Med | High | `LeaseBasedRoomOwnership` (Engine, Task 14) + `EnginePodDiscovery` (Gateway, Task 21) + Runbook cấm scale trong giờ thi (Task 19) | ✅ Task 14+21 code xong, verify qua Docker thật; Task 19 (runbook) vẫn còn hiệu lực tới khi cả hai bật production |
| **`Clock` gọi thẳng `System.currentTimeMillis()`** | Med | Med | Inject `Clock` làm tham số constructor bắt buộc từ Task 2 | ✅ Đạt |
| **PH-3: Client contract vắng mặt** | High | High | Chưa công bố SLA "Zero Data Loss" trước khi Client ship RingBuffer & RESYNC | ⚠️ Phối hợp FE |

---

## Execution Mode & Dependency Graph

```text
  [T1 protocol]
    ├──→ [T2 RoomActor core] ──→ [SPIKE timer] ──→ [T3 tick coalescing] ──┐
    │         └──→ [T11 game definition] ─────────────────────────────────┤
    │                                                                     │
    ├──→ [T4 frame channel codec/server] ──→ [T10 ownership + stamp] ─────┤
    │         └──→ [T5 GW frame client + route cache] ────────────────────┤
    │                                                                     │
    └──→ [T6 GW pipeline + auth] ──┬──→ [T7 rate limit] ─────────────────┤
                                    └──→ [T8 fan-out] ──→ [T9 backpressure] ┴──→ [SYNC] ──→ [T13 E2E]
                                                                                             │
                                                                   [T14-T20 Storage & Infra] ┘
```

---

## Danh Sách Task & Tiến Độ

### Task 1: Protocol Module & `game_message.proto` — ✅ XONG
- Unified Protobuf schema (`GameMessage`, `InternalHeader`, `oneof payload`). `client_timestamp_ms` telemetry-only.
- **Verification:** `mvn -pl :uni-protocol clean install` (4/4 pass).

### Task 2: `RoomActor` FSM, Chấm điểm, Server Timestamp, Dedupe — ✅ XONG
- FSM `LOBBY → PLAYING → FINISHED`. `server_received_at = clock.millis()`. Dedupe `LastSeenSequenceTable`. Chấm điểm `FormulaScoreCalculator` (100/0).
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=RoomActorTest,FormulaScoreCalculatorTest` (12/12 pass).

### SPIKE: Pekko Scheduler Capacity — ✅ ĐẠT (GO)
- 1.000 timer/pod @ 200ms: p99 = 36ms (<50ms), CPU 5% (<30%). Giữ thiết kế single-shot timer per actor.

### Task 3: Tick Coalescing trong `RoomActor` — ✅ XONG
- Trần 200ms (ADR-4). Phòng im lặng phát 0 gói. `ANSWER_ACK` đi ngay bypass coalescing.
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=TickCoalescingTest` (5/5 pass).

### Task 4: Internal Frame Channel Server (Engine) — ✅ XONG
- Netty `LengthFieldBasedFrameDecoder(1MB, 0, 4, 0, 4)` + `LengthFieldPrepender(4)`. Multiplex theo `room_id`.
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=FrameCodecTest,FrameChannelServerTest` (5/5 pass).

### Task 5: Frame Channel Client & Lazy Route Cache (Gateway) — ✅ XONG
- Round-robin khi chưa biết route → học `owner_pod_id` từ response → cache `room_id -> pod` (RAM, no TTL, auto evict khi pod đứt).
- **Verification:** `mvn -pl :uni-websocket-gateway test -Dtest=RouteCacheTest,FrameChannelClientTest` (8/8 pass).

### Task 6: Gateway WS Pipeline & Join Token Auth — ⚠️ DEV OPT-IN
- Netty WS Pipeline (`HttpServerCodec → HttpObjectAggregator(50KB) → WebSocketServerProtocolHandler → JoinTokenAuthHandler → RateLimitHandler → GameMessageDecoder → RoomRouteHandler`). TLS terminate ở Ingress. Chống mạo danh `room_id`.
- **Verification:** `mvn -pl :uni-websocket-gateway test -Dtest=GatewayPipelineTest,AlwaysAcceptJoinTokenVerifierTest` (11/11 pass).

### Task 7: Rate Limiting Phân Tầng — ✅ XONG
- L1 IP (4000/min), L2 `student_id` (10/min), Per-message (`SUBMIT` 3/1s, `DRAFT` 10/10s + 150ms client debounce, `HEARTBEAT` 2/30s).
- **Verification:** `mvn -pl :uni-websocket-gateway test -Dtest=RateLimitHandlerTest,IpAdmissionControllerTest,StudentHandshakeAdmissionControllerTest` (11/11 pass).

### Task 8: Room Registry & Fan-out Zero-copy — ✅ XONG
- `Broadcaster` dùng `retainedDuplicate()` (không `retain()`), release frame trong `finally`.
- **Verification:** `mvn -pl :uni-websocket-gateway test -Dtest=FanoutTest,RoomRegistryTest` (9/9 pass - 12 clients).

### Task 9: Nối Chuỗi Backpressure 1 Tầng — ⚠️ MỘT PHẦN XONG (3 Hop)
- Socket writability (`WRITE_BUFFER_WATER_MARK` 32KB/64KB) điều khiển `autoRead` trên cả 3 hop. Best-effort drop khi `!isWritable()`, Critical close channel.
- **Verification:** `mvn -pl :uni-websocket-gateway,:uni-game-engine test -Dtest=BackpressureTest` (7/7 pass).

### Task 10: Room Ownership Engine & `NOT_OWNER` Stamp — ✅ XONG
- Interface `RoomOwnership` (`ModuloRoomOwnership` % N). Response đóng dấu `InternalHeader.owner_pod_id` hoặc `NOT_OWNER`.
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=RoomOwnershipTest,RoomOwnershipHandlerTest` (7/7 pass).

### Task 11: Game Definition & Guardrails — ✅ XONG
- Loader validate DAG (chống chu trình), DSL toán tử đóng `ScoringFormula`. Bắt buộc `missed_step_policy: ZERO` và `tick_mode: COALESCE`.
- **Verification:** `mvn -pl :uni-game-engine test -Dtest=DefinitionLoaderTest,ScoringFormulaTest` (14/14 pass).

### Task 12: Nền Quan Sát Prometheus — ✅ XONG
- Expose `/actuator/prometheus` cho cả 2 service: `actor_processing_latency`, `actor_mailbox_depth`, `fanout_latency`, `handshake_rate`, `channel_not_writable_total`.
- **Verification:** Live app verify thành công qua HTTP scrape.

### Task 13: End-to-End Walking Skeleton & Presence — ✅ XONG
- Nối `RoomSupervisor`, `ChannelReplyActor`, `EngineResponseRouter`. Luồng rời phòng (`STUDENT_LEFT`) và `KICK_STUDENT` ngắt kết nối lập tức.
- **Verification:** `mvn -pl :uni-e2e -am test -Dtest=WalkingSkeletonTest,RoomActorPresenceTest` (5/5 pass).

### Task 14: `LeaseBasedRoomOwnership` & Hot Snapshot — ✅ XONG
- Valkey Lease `SETNX room:owner:{id}` + `INCR room:epoch:{id}`. Hot Snapshot nén < 5KB với Fencing Epoch.
- **Verification:** `LeaseBasedRoomOwnershipTest` (11/11 pass), `DockerComposeChaosIT` (phục hồi trung bình 24.9s qua Docker).
- **Cập nhật 2026-09-09 (Task 23):** không còn opt-in — `uni.engine.room-store.enabled` đã bị xoá,
  `LeaseBasedRoomOwnership` giờ là `RoomOwnership` DUY NHẤT, luôn chạy. Xem Task 23.

### Task 15: Broadcast `COMMITTED_SEQ` — ⚠️ SERVER XONG
- Tín hiệu Best-effort báo client xoá RingBuffer sau khi Valkey ghi Hot Snapshot thành công.
- **Verification:** `RoomActorSnapshotTest` (3/3 pass).

### Task 16: `broadcast_seq` Counter — ✅ XONG
- Thêm `broadcast_seq` tăng đơn điệu mỗi phòng vào `RoomStateSnapshot`. Sống sót qua Hot Snapshot restore.
- **Verification:** `TickCoalescingTest` & `RoomStateSnapshotTest` pass.

### Task 17: Thực Thi `missed_step_policy: ZERO` — ✅ XONG
- `RoomState` đếm `questionsStartedCount` và gán 0 điểm có chủ đích cho các bước bỏ lỡ khi late join.
- **Verification:** `RoomStateMissedStepPolicyTest` (8/8 pass).

### Task 18: Kafka Event Isolation — ✅ XONG
- `GameEventPublisher` dùng `ArrayBlockingQueue` bounded + worker thread riêng, `KafkaProducer` `max.block.ms=0`. Không bao giờ block `RoomActor`.
- **Verification:** `GameEventPublisherTest` (4/4 pass), `KafkaGameEventSinkDockerIT` pass.

### Task 19: Runbook Cấm Auto-scaling Engine — ✅ XONG
- Viết [`docs/runbook/engine-scaling-freeze.md`](../../runbook/engine-scaling-freeze.md) cấm scale Engine giữa giờ thi (18h50-21h30).
- **Verification:** Document review & link 2 chiều với `_context.md`.

### Task 20: Hạ Tầng Docker Test Cục Bộ — ✅ XONG
- Dựng `docker-compose.dev.yml` (1 Gateway + 2 Engine + Valkey + Kafka KRaft). Đã verify `DockerComposeResyncIT` và `DockerComposeChaosIT`.
- **Verification:** `RUN_DOCKER_IT=true mvn -pl :uni-e2e test` pass.

### Task 21: Gateway Dynamic Pod Discovery & Hot-Reload Dialer — ✅ XONG (2026-09-09)
- **Mục tiêu:** Gateway tự động phát hiện Engine pod mới scale-up hoặc vừa recover sau crash để dial kết nối TCP Netty mới mà không cần restart Gateway (đóng lỗ hổng B5, đảm bảo zero-downtime & self-healing).
- **Thiết kế thật đã code** (khớp tinh thần bản nháp ở trên, khác tên gọi cụ thể một vài chỗ):
  - `EnginePodResolver` (interface, `uni-websocket-gateway`) — extension point giống `RoomOwnership`.
    Đúng 1 implementation bây giờ: `ValkeyEnginePodResolver`. Một implementation K8s-native (headless
    Service DNS) **cố ý chưa viết** — repo này chưa có bất kỳ quyết định StatefulSet/headless-Service
    nào (đã grep xác nhận), viết mù sẽ không verify được gì.
  - `EnginePodPresence` (Engine, `persistence/`) — mỗi pod tự SET key `engine:pod:<podId>` =
    `"<host>:<port>"` với TTL, tái dùng ĐÚNG connection + nhịp renew (`ttl/3`) với lease của Task 14
    (không mở connection Valkey thứ hai). Khác bản nháp: TTL-bound key riêng từng pod (tự hết hạn khi
    pod chết) thay vì 1 Set `engine:active-pods` chung (tránh phải tự dọn phần tử chết tay).
  - `EnginePodDiscovery` (Gateway, thread riêng — KHÔNG phải Netty EventLoop) — poll
    `EnginePodResolver` định kỳ (mặc định 5s, `docker-compose.dev.yml` đặt 3s), gọi
    `EngineConnector.connect(...)` cho pod chưa `isConnected()`. `EngineConnector` là interface hẹp
    tách từ `FrameChannelClient` (thay vì `connectIfAbsent` gộp 1 method) — để test được logic
    poll/diff bằng fake, không cần socket thật.
  - Off by default cùng flag/infra với Task 14: `uni.gateway.engine.pod-discovery.enabled=false`
    mặc định `application.yml`, bật `true` ở `docker-compose.dev.yml` (`gateway`/`gateway-1`).
- **Quyết định kiến trúc:** cân nhắc 2 phương án (Valkey registry vs DNS/ordinal-probing kiểu Docker
  Compose) cùng người dùng trước khi code — chọn Valkey vì đây là cơ chế DUY NHẤT chạy giống nhau ở
  mọi môi trường (dev/staging/prod đều chỉ là 1 Service Valkey), không cần biết trước topology K8s.
- **Bug thật phát hiện qua Docker thật (không do code Task 21 gây ra):** `docker-compose.dev.yml`'s
  `engine-2` có `ENGINE_POD_COUNT: "2"` (copy-paste từ engine-0/1) — `ModuloRoomOwnership`'s
  constructor đòi `selfPodId` phải nằm trong danh sách sinh ra từ `pod-count`, nên `engine-2`
  crash-loop ngay từ trước khi kịp mở frame channel, dù không hề động tới `RoomOwnership`. Chưa
  từng lộ ra vì `DockerComposeScaleUpIT` trước đó luôn `@Disabled`, chưa ai thật sự start
  `engine-2`. Sửa cả 3 service (`engine-0/1/2`) thành `ENGINE_POD_COUNT: "3"`.
- **Verification (real Docker, không phải giả định):**
  - `EnginePodDiscoveryTest` (3/3, fake resolver+connector; prove-it: fake connector ban đầu không
    tự đánh dấu "đã kết nối" sau khi connect thành công → `EnginePodDiscovery` cứ reconnect liên
    tục, sửa fake khớp đúng ngữ nghĩa `FrameChannelClient` thật rồi mới Green).
  - `mvn clean install` toàn reactor (không Docker): BUILD SUCCESS — 5 protocol + 86 gateway + 120
    engine + 2 e2e.
  - **`DockerComposeScaleUpIT` xoá `@Disabled`, chạy thật (RUN_DOCKER_IT=true) sau khi sửa bug
    `ENGINE_POD_COUNT`: GREEN** — `engine-2` khởi động SAU khi Gateway đã boot xong (không có trong
    `ENGINE_PODS` tĩnh), tự announce vào Valkey, `EnginePodDiscovery` học được trong vài chu kỳ
    poll, route được phòng mới về `engine-2` không cần restart Gateway.
  - Hồi quy: `DockerComposeChaosIT` (2/2 xanh, phục hồi 24968ms — khớp dải đo trước 21.8s-27.9s) +
    `DockerComposeResyncIT` (1/1 xanh) sau khi sửa `ENGINE_POD_COUNT` — không regress.
- **Verification Target gốc đã đạt:** `DockerComposeScaleUpIT` GREEN, không sửa assertion.

### Task 22: Nén LZ4 cho hot path Gateway→Client — ✅ XONG (2026-09-09)
- **Mục tiêu:** Kéo nén LZ4 từ GĐ3 lên GĐ1 theo yêu cầu người dùng ("code LZ4 thật ngay bây giờ,
  áp dụng cho payload hiện tại GĐ1") — không phải đổi tài liệu, mà code thật trên hot path đang
  chạy, đúng ngưỡng >150 bytes đã ghi ở `system-architecture.md` §3.6 từ trước.
- **Điểm tích hợp:** đúng 1 chỗ — `EngineResponseRouter.route()` và
  `broadcastConnectionDegraded()`, hai nơi DUY NHẤT `GameMessage` được encode thành bytes cho hop
  Gateway→Client, luôn trước khi `Broadcaster` fan-out zero-copy (`retainedDuplicate()`). Hop nội
  bộ Gateway↔Engine cố ý KHÔNG nén: Gateway đã re-encode ở đó để bóc `InternalHeader`, nên không
  có zero-copy xuyên hop nào để giữ, và không phải payload client thấy.
- `WireCompression` (mới, `uni-websocket-gateway/net`) — wire format 1 byte flag (`0x00`=raw,
  `0x01`=LZ4) + 4 byte BE original-length nếu nén (LZ4 block format không tự mô tả độ dài), ngưỡng
  `> 150 bytes` lấy nguyên văn từ tài liệu. `encode(byte[]) → ByteBuf`, `decode(byte[]) → byte[]`.
- **Dependency thật phát hiện qua `dependency:tree`:** `org.lz4:lz4-java:1.8.0` (khai báo mới,
  version pin tường minh) và `at.yawk.lz4:lz4-java:1.10.1` (transitive qua `kafka-clients` từ
  `uni-observability`) cùng cung cấp package `net.jpountz.lz4` — split-package thật trên classpath,
  chọn jar nào thắng vốn "tình cờ theo thứ tự classpath" trước khi sửa. Sửa bằng `<exclusion>` trên
  dependency `uni-observability` trong `uni-websocket-gateway/pom.xml`; `dependency:tree` xác nhận
  chỉ còn đúng 1 jar `org.lz4:lz4-java:1.8.0`.
- **TDD + prove-it:** viết `WireCompressionTest` (5 case: dưới ngưỡng, đúng ngưỡng 150B, trên
  ngưỡng 1 byte, payload lớn/lặp phải thực sự nhỏ hơn sau nén, payload lớn/ngẫu nhiên không nén
  được vẫn round-trip đúng) TRƯỚC — Red xác nhận (`cannot find symbol WireCompression`) rồi mới
  implement. Sau khi Green, cố tình sửa header original-length sai (`payload.length - 1`) — 2/5
  test FAIL đúng chỗ (`LZ4Exception: Error decoding offset ...`) trước khi revert về đúng.
- Wire vào 2 điểm encode thật (`EngineResponseRouter`) + cập nhật 2 điểm decode phía test/e2e
  (`EngineResponseRouterTest`'s `decode()` helper, `SimulatedStudentClient`'s `channelRead0`) —
  grep hết mọi `GameMessage.parseFrom` trong repo để xác nhận không còn điểm nào khác cần sửa (các
  điểm còn lại là hop Client→Gateway inbound hoặc hop nội bộ Gateway↔Engine, không liên quan).
- **Bug XML thật phát hiện lại (giống Task 21):** comment trong `pom.xml` chứa `--` làm POM
  non-parseable (`in comment after two dashes`), sửa bằng `;`.
- **Verification:** `mvn clean install` toàn reactor: BUILD SUCCESS — 7 protocol + 91 gateway
  (thêm `WireCompressionTest` 5/5) + 175 engine + 4 e2e, 0 failures/errors.
- **Tài liệu đã cập nhật:** `CLAUDE.md` (bỏ "no LZ4" khỏi Known Phase 1 trade-offs),
  `system-architecture.md` §3.6, `EdTech_Game_Realtime_Architecture_v3.0.md` (Giai đoạn 3 — phân
  biệt rõ LZ4 cơ bản đã bật vs "adaptive" HC/tự điều chỉnh ngưỡng vẫn còn ở GĐ3), `_context.md`
  (Phạm vi đã chốt + state block).
- **Chưa làm (cố ý, ngoài phạm vi):** LZ4 "adaptive" (HC codec, hoặc tự điều chỉnh ngưỡng theo tải
  CPU đo thực tế) — vẫn là mục GĐ3 theo tài liệu, chưa có yêu cầu/số đo nào đòi hỏi bây giờ.
- **Sự cố môi trường phát hiện ngay sau task này (2026-09-09):** một tiến trình nền không rõ
  nguồn gốc đã cắt xén nghiêm trọng nhiều file trên đĩa (không chỉ mất comment như phát hiện ở P2
  Task 26 — lần này `EdTech_Game_Realtime_Architecture_v3.0.md` mất từ 1540 xuống 318 dòng), kể cả
  file vừa sửa xong. Khôi phục bằng `git show HEAD:<path>` (read-only) + Write lại thủ công đúng
  nội dung + edit của mình, không dùng `git checkout` (bị auto-mode classifier chặn vì là lệnh
  discard hàng loạt). Xem `_context.md` state block để biết chi tiết.

### Task 23: Xoá hẳn `room_id % N` khỏi RoomOwnership — ✅ XONG (2026-09-09)
- **Mục tiêu:** Theo yêu cầu người dùng, gỡ bỏ hoàn toàn `ModuloRoomOwnership` (`room_id % N`) khỏi
  code — không còn là fallback lúc Valkey mất kết nối, không còn là ownership mặc định khi
  `room-store.enabled=false`. Đã hỏi rõ trước khi làm: Valkey trở thành dependency bắt buộc để
  Engine khởi động, không còn đường lui — người dùng xác nhận chấp nhận đánh đổi này.
- **Xoá:** `ModuloRoomOwnership.java` + `RoomOwnershipTest.java` (test riêng cho nó).
- **`LeaseBasedRoomOwnership.java`:** bỏ tham số constructor `fallback` — khi Valkey mất kết nối
  lúc giành lease, ở lại trạng thái unresolved (`ownerPodId` trả `""`) thay vì đoán sang modulo,
  đúng hợp đồng "drop this frame" đã có sẵn trong `RoomOwnership` javadoc.
- **`EngineNetworkLifecycle.java`:** bỏ nhánh `if (roomStoreEnabled)` — Lease-based ownership + Hot
  Snapshot giờ LUÔN chạy; bỏ tham số `uni.engine.pod-count` (chỉ tồn tại để dựng danh sách pod cho
  modulo, không còn ai dùng).
- **Config:** bỏ `ENGINE_ROOM_STORE_ENABLED`/`ENGINE_POD_COUNT` khỏi `application.yml` và
  `docker-compose.dev.yml` (cả 3 service `engine-0/1/2`).
- **Bug thật phát hiện qua chính quá trình sửa (có sẵn từ Task 14, không phải do refactor này gây
  ra — chỉ bị lộ ra vì xoá fallback):** `pending.remove(roomId)` gọi TỪ BÊN TRONG callback
  `whenComplete` gắn vào future trả về từ `pending.computeIfAbsent(roomId, ...)` cho ĐÚNG key đó —
  khi store hoàn thành ĐỒNG BỘ (mọi fake trong `LeaseBasedRoomOwnershipTest`, có thể cả Lettuce
  thật trong vài trường hợp), `whenComplete` chạy ngay lập tức BÊN TRONG lời gọi `computeIfAbsent`,
  và `ConcurrentHashMap` ném `IllegalStateException("Recursive update")` khi mapping function tự
  sửa chính map đang tính cho cùng 1 key — lỗi này bị `whenComplete` nuốt âm thầm, làm `cache.put()`
  (đặt SAU `pending.remove()` trong code cũ lẫn code mới ban đầu) không bao giờ chạy. Lỗi này ĐÃ
  TỒN TẠI TỪ TRƯỚC (code cũ cũng gọi `pending.remove` bên trong cùng 1 `computeIfAbsent`) nhưng bị
  che giấu vì code cũ luôn `cache.put()` TRƯỚC `pending.remove()` — mọi lời gọi `ensureAcquired` sau
  đó đều tắt qua nhánh `if (cache.containsKey) return` trước khi chạm lại `pending`. **Sửa đúng:**
  tách mapping function của `computeIfAbsent` ra CHỈ gọi `roomStore.tryAcquire()` (không đụng gì
  tới `pending`/`cache`), rồi gắn `whenComplete` (thật sự làm `cache.put`/`pending.remove`) ở BÊN
  NGOÀI, SAU KHI `computeIfAbsent` đã trả về — không còn lồng nhau nữa. Phát hiện qua chạy test
  thật (4/12 test đỏ đúng lý do, không phải giả định) — đúng tinh thần "prove-it" đã dùng xuyên
  suốt repo này.
- **Test cập nhật:** 6 file dùng `ModuloRoomOwnership` làm stub "owns everything"
  (`RoomSupervisorTest` x3, `FrameChannelServerTest`, `RoomOwnershipHandlerTest` x2, 3 file
  `uni-e2e`) — thay bằng class stub mới `AlwaysOwnRoomOwnership` (định nghĩa riêng cho từng module
  test tree, không chia sẻ qua module vì test-jar chưa được setup). `DockerComposeResyncIT.java`:
  sửa comment/javadoc (không sửa assertion) — test này trước đây dựa vào `floorMod` xác định
  "room-docker-a" luôn rơi vào `engine-0`, "room-docker-b" luôn rơi vào `engine-1` (verify bằng
  jshell) để giải thích vì sao test chắc chắn đi qua 2 pod khác nhau; giờ `LeaseBasedRoomOwnership`
  là first-acquire-wins, không còn đảm bảo xác định nào — đã sửa lại đúng thực tế, assertion không
  đổi vì không phụ thuộc pod cụ thể nào.
- **Verification:** `mvn -pl :uni-game-engine test`: 170/170 pass (giảm 5 vì xoá
  `RoomOwnershipTest`). `mvn clean install` toàn reactor: BUILD SUCCESS (2:24) — 7 protocol + 92
  gateway + 170 engine + 4 e2e, không Docker (Docker ITs chưa chạy lại — cần xác nhận riêng khi có
  Docker daemon, đặc biệt `DockerComposeChaosIT`/`DockerComposeResyncIT`).
- **Tài liệu đã cập nhật:** `CLAUDE.md` (rule B2 + Known Phase 1 trade-offs), `docs/runbook/engine-scaling-freeze.md`
  (rủi ro GỐC — đổi N làm vỡ hash — đã hết vì không còn N để đổi; runbook CHƯA được gỡ bỏ vì điều
  kiện "verify Valkey Cluster thật ở staging" vẫn chưa đạt, mới chỉ Docker 1 máy), `_context.md`.
- **Verify thêm qua Docker thật (2026-09-09, cùng phiên, sau khi phát hiện Docker daemon thật ra CÓ
  chạy được trong môi trường này):** chạy `DockerComposeResyncIT`/`DockerComposeChaosIT`/`DockerComposeScaleUpIT`
  với `RUN_DOCKER_IT=true` qua `docker-compose.dev.yml` thật (đã cập nhật, không còn
  `ENGINE_ROOM_STORE_ENABLED`/`ENGINE_POD_COUNT`).
  - **Lần chạy đầu: 2 loại lỗi môi trường thật, không phải regression code.**
    (1) Cổng 9000 host bị 2 tiến trình cùng lắng nghe — `com.docker.backend.exe` (đúng, forward
    thật của container) VÀ 1 `wslrelay.exe` mồ côi trên `[::1]:9000`, sót lại từ 1 phiên
    docker-compose CŨ không được dọn sạch trước phiên này (bằng chứng: `room-store`/`kafka` đã
    "Up 8 giờ" trước khi phiên này chạm tới) — `curl`/WS client trên Windows ưu tiên `::1` (IPv6)
    nên nối nhầm sang relay mồ côi, gây `WS handshake never completed`/`JOIN_ROOM got no
    full-snapshot reply`. Sửa: `docker compose down` sạch + `taskkill` tiến trình mồ côi (xác nhận
    lại bằng `curl` WS handshake thật trả `101 Switching Protocols` trước khi chạy lại test).
    (2) `DockerComposeScaleUpIT` fail lần đầu vì `engine-2` dùng image CŨ (build từ phiên trước, có
    `ModuloRoomOwnership` — log thật: `IllegalArgumentException: selfPodId 'engine-2' must be in
    allPodIds [engine-0]`) vì lệnh `docker compose up -d --build` ban đầu không liệt kê `engine-2`
    (dịch vụ này cố ý không nằm trong `depends_on` của ai, không tự build theo default). Sửa:
    `docker compose build engine-2` tường minh trước khi chạy lại test.
  - **Lần chạy sau khi sửa cả 2 vấn đề môi trường: XANH cả 3.** `DockerComposeResyncIT` (1/1),
    `DockerComposeChaosIT` (2/2, thời gian phục hồi thật 21897ms — khớp dải 21.8s-27.9s đã đo ở
    Task 14, không regress), `DockerComposeScaleUpIT` (1/1, `engine-2` sau khi build lại image mới
    tự khởi động đúng `LeaseBasedRoomOwnership`, không còn dòng log
    `ModuloRoomOwnership`/`ENGINE_POD_COUNT` nào). Đã `docker compose down` dọn sạch sau khi xong.
  - **Kiểm tra flaky (2026-09-10, theo yêu cầu người dùng):** dọn xác nhận không còn `wslrelay.exe`
    mồ côi nào trước khi chạy (relay mới xuất hiện sau `docker compose up` là bình thường, xác nhận
    bằng WS handshake thật trả `101 Switching Protocols`, khác hẳn loại mồ côi gây timeout ở lần
    trước). Chạy `DockerComposeResyncIT` + `DockerComposeChaosIT` **2 lần liên tiếp** trên cùng 1
    stack fresh: lần 1 — 3/3 pass, phục hồi 18816ms (engine-0 chết → engine-1 giành lại); lần 2 —
    3/3 pass, phục hồi 24818ms (engine-1 chết → engine-0 giành lại). Cả 2 lần đều nằm trong dải đã
    đo ở Task 14 (21.8s-27.9s) — **không flaky qua 2 lần chạy liên tiếp**.

---

## Pre-merge Checklist

- [x] Tất cả các task 1–20 pass verification (180/180 unit/integration tests PASS).
- [x] `mvn clean test` sạch từ root từ JDK 25.
- [x] Test suite chạy với `-Dio.netty.leakDetection.level=paranoid` 100% không leak.
- [x] `client_timestamp_ms` tuyệt đối không dùng tính điểm (grep sạch trong engine).
- [x] `retainedDuplicate()` dùng đúng trong vòng fan-out (grep `.retain()` sạch).
- [x] Governance check `bash scripts/governance-check.sh` PASS cả 5 gates.
- [x] Runbook cấm auto-scaling Engine đã thiết lập an toàn.
