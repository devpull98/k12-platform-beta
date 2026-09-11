# Progress Log — NOJIRA-uni-p1-realtime-core

| Task | Tên Task | Trạng thái | Ngày | Verification & Test Outcome |
|---|---|---|---|---|
| **T1** | Protocol Schema (`game_message.proto`) | ✅ Done | 2026-09-05 | `mvn -pl :uni-protocol test` (4/4 pass) |
| **T2** | RoomActor FSM & Server Timestamp | ✅ Done | 2026-09-06 | `RoomActorTest` (9/9 pass), `FormulaScoreCalculatorTest` (3/3) |
| **SPIKE** | Pekko Timer Capacity | ✅ Go | 2026-09-06 | 1.000 timers/pod: p99 = 36ms (<50ms), CPU 5% (<30%) |
| **T3** | Tick Coalescing (ADR-4) | ✅ Done | 2026-09-07 | `TickCoalescingTest` (5/5 pass, manual time) |
| **T4** | Internal Frame Codec / Server | ✅ Done | 2026-09-06 | `FrameCodecTest` (4/4), `FrameChannelServerTest` (1/1) |
| **T5** | RouteCache & FrameChannelClient | ✅ Done | 2026-09-06 | `RouteCacheTest` (5/5), `FrameChannelClientTest` (3/3) |
| **T6** | Gateway WS Pipeline & Auth | ⚠️ Dev Opt-in | 2026-09-08 | `GatewayPipelineTest` (7/7), `AlwaysAcceptJoinTokenVerifierTest` (4/4) |
| **T7** | Rate Limiting (L1/L2/Per-msg) | ✅ Done | 2026-09-07 | `RateLimitHandlerTest` (5/5), `IpAdmissionControllerTest` (3/3), `StudentHandshakeAdmissionControllerTest` (3/3) |
| **T8** | RoomRegistry & Zero-copy Fan-out | ✅ Done | 2026-09-06 | `FanoutTest` (4/4 - 12 clients), `RoomRegistryTest` (5/5) |
| **T9** | Backpressure 3-hop | ⚠️ Partial | 2026-09-06 | `BackpressureTest` (GW 5/5, Engine 2/2) |
| **T10** | RoomOwnership & `NOT_OWNER` | ✅ Done | 2026-09-06 | `RoomOwnershipTest` (5/5), `RoomOwnershipHandlerTest` (2/2) |
| **T11** | Game Definition & DAG Guard | ✅ Done | 2026-09-06 | `DefinitionLoaderTest` (8/8), `ScoringFormulaTest` (6/6) |
| **T12** | Observability & Prometheus Metrics | ✅ Done | 2026-09-06 | App verify, `/actuator/prometheus` metrics active |
| **T13** | Walking Skeleton & Presence | ✅ Done | 2026-09-08 | `WalkingSkeletonTest` (1/1), `RoomActorPresenceTest` (4/4) |
| **T14** | Lease Ownership & Hot Snapshot | ✅ Done | 2026-09-08 | `LeaseBasedRoomOwnershipTest` (11/11), `DockerComposeChaosIT` (24.9s recovery) |
| **T15** | `COMMITTED_SEQ` Broadcast | ⚠️ Server Done | 2026-09-07 | `RoomActorSnapshotTest` (3/3) |
| **T16** | `broadcast_seq` Counter | ✅ Done | 2026-09-07 | `TickCoalescingTest` & `RoomStateSnapshotTest` |
| **T17** | `missed_step_policy` ZERO | ✅ Done | 2026-09-08 | `RoomStateMissedStepPolicyTest` (8/8) |
| **T18** | Kafka Event Isolation | ✅ Done | 2026-09-07 | `GameEventPublisherTest` (4/4), `KafkaGameEventSinkDockerIT` |
| **T19** | Engine Scaling Freeze Runbook | ✅ Done | 2026-09-07 | Documented [`docs/runbook/engine-scaling-freeze.md`](../../runbook/engine-scaling-freeze.md) |
| **T20** | Local Docker Infra & Resync | ✅ Done | 2026-09-07 | `DockerComposeResyncIT`, `RoomActorResyncTest` |
| **T21** | Gateway Dynamic Pod Discovery | 🆕 Open | - | `EnginePodResolver` + `ValkeyEnginePodResolver` (`DockerComposeScaleUpIT` pending) |

---

## Chi tiết Các Module Thực Hiện

### 1. Game Engine Core (`uni-game-engine`)
- **FSM & Scoring (T2, T11, T17):** Pekko Typed Actor `RoomActor` quản lý FSM `LOBBY → PLAYING → FINISHED`. Đóng dấu `server_received_at` bằng `Clock` inject, chấm điểm trắc nghiệm 100/0 (`FormulaScoreCalculator`), dedupe theo `LastSeenSequenceTable`. Validate DAG Game Definition lúc nạp, bắt buộc `missed_step_policy: ZERO`.
- **Coalescing & Codec (T3, T4):** Tick coalescing ceiling 200ms (ADR-4), phòng không đổi phát 0 gói. TCP Length-prefixed Protobuf Netty server (`FrameChannelServer`).
- **Resilience & Storage (T10, T14, T15, T16, T18):** `RoomOwnership` interface (`ModuloRoomOwnership` & `LeaseBasedRoomOwnership`). Hot Snapshot Valkey nén < 5KB với Fencing Epoch. Broadcast `COMMITTED_SEQ` và `broadcast_seq` tăng đơn điệu. `GameEventPublisher` đẩy sự kiện Kafka async (`max.block.ms=0`, worker thread riêng).

### 2. WebSocket Gateway Edge (`uni-websocket-gateway`)
- **Pipeline & Security (T6, T7):** Netty WS Pipeline (`HttpServerCodec → HttpObjectAggregator(50KB) → WebSocketServerProtocolHandler → JoinTokenAuthHandler → RateLimitHandler → GameMessageDecoder → RoomRouteHandler`). Ràng buộc `room_id` theo `ChannelAttributes`. Rate limiting L1 (IP: 2000/phút, giảm từ 4000/phút ngày 2026-09-11), L2 (`student_id`: 10/phút), Per-message (`SUBMIT` 3/1s, `DRAFT` 10/10s, `HEARTBEAT` 2/30s).
- **Routing & Fan-out (T5, T8, T9):** Lazy-learned `RouteCache` (`room_id -> pod_id`). Zero-copy fan-out dùng `retainedDuplicate()` và release trong `finally`. Backpressure 1 tầng theo socket writability (`WRITE_BUFFER_WATER_MARK` 32KB/64KB).

### 3. Integration & E2E Test (`uni-e2e`)
- **Walking Skeleton (T13, T20):** Kiểm thử socket thật end-to-end từ WebSocket Client → Gateway → Frame Channel → Engine `RoomSupervisor`/`RoomActor`. 
- **Docker Test Infrastructure (T20):** `docker-compose.dev.yml` dựng 1 Gateway + 2 Engine + Valkey + Kafka KRaft. Đã verify `DockerComposeResyncIT` và `DockerComposeChaosIT` (phục hồi phòng trung bình 24.9s sau khi giết container Engine).
