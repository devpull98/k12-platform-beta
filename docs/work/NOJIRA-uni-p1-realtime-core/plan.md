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

---

## Pre-merge Checklist

- [x] Tất cả các task 1–20 pass verification (180/180 unit/integration tests PASS).
- [x] `mvn clean test` sạch từ root từ JDK 25.
- [x] Test suite chạy với `-Dio.netty.leakDetection.level=paranoid` 100% không leak.
- [x] `client_timestamp_ms` tuyệt đối không dùng tính điểm (grep sạch trong engine).
- [x] `retainedDuplicate()` dùng đúng trong vòng fan-out (grep `.retain()` sạch).
- [x] Governance check `bash scripts/governance-check.sh` PASS cả 5 gates.
- [x] Runbook cấm auto-scaling Engine đã thiết lập an toàn.
