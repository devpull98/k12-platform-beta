# NOJIRA — Uni Realtime Giai đoạn 1: WebSocket Gateway + Game Engine

## Classification
- **Type:** feature
- **Module:** uni-realtime (gateway + engine + protocol)
- **Jira:** `NOJIRA`
- **Artifact key:** `NOJIRA-uni-p1-realtime-core`

## Spec anchor
- **File:** `docs/specs/tech-design/EdTech_Game_Realtime_Architecture_v3.0.md`
- **Sections:** §5.3 (envelope), §5.4 (Critical/Best-effort), §6.2 (tick coalescing),
  §7.1–7.3 (luồng dữ liệu), §8.2 (lazy-learned routing), §9.4 (server timestamp),
  §9.5 (dedupe), §9.7 (Engine mất kết nối), §10.1–10.3 (rate limit, backpressure, fan-out),
  §10.5 (guardrails), §10.6 (chống đẩy nhầm phòng), §13 (mô hình thực thi), §18 (lộ trình)

## Read order (ONLY — do not glob elsewhere)
1. `_context.md` (file này)
2. `plan.md`
3. `docs/specs/tech-design/NOJIRA-uni-p1-tech-design.md` — hợp đồng còn trống trước T2/T4/T6.
   **Bắt buộc đọc trước khi code T3 / T6 / T7**; §9 liệt kê thứ đang chặn
4. Kiến trúc: `docs/architect/system-architecture.md` (đặc tả toàn diện & các quyết định ADR).
   **Đây là nguồn chính**, không phải v3.0
5. Các mục v3.0 liệt kê ở trên — chỉ khi cần chiều sâu (phân tích tải, benchmark, phương án đã
   loại). **Không đọc toàn bộ 1528 dòng**

## Dependencies
- **Code:** chưa có — đây là greenfield. Xem "Việc còn mở" bên dưới về vị trí project.
- **Cross-module:** không
- **Rules:** `rules/spring/` (cài 2026-09-06) — viết theo Netty/Pekko/Protobuf thật, **không** phải convention Spring MVC/JPA mặc định của kit

## Impact radius
- **Stores:** Redis Cluster (chặn replay ticket lúc handshake, lưu Hot Snapshot < 5 KB bất đồng bộ). Tuyệt đối không nằm trên hot path.
- **Messaging:** Kafka Cluster (async event streaming sau khi nộp bài cho Teacher Dashboard & DB writer).
- **APIs:** `WS /ws` (biên realtime) · `POST /session/{id}/join` (cấp ticket, đã có ở dịch vụ nền tảng)

## Phạm vi đã chốt

**Trong Giai đoạn 1:**
| Có | Vì sao |
|---|---|
| Server-authoritative timestamp (§9.4) | Là tính năng của game engine, nằm trên đường chấm điểm |
| `LastSeenSequenceTable` (§9.5) | Định hình `state` của actor — nhét vào sau phải sửa lại cả hai |
| Tick coalescing (§6.2) | ADR-4, quyết định hình dạng đường broadcast |
| Lazy-learned routing (§8.2) | **PH-2** — làm ngay thì Giai đoạn 2 không phải sửa Gateway |
| FSM `LOBBY → PLAYING → FINISHED` | Tối thiểu để chơi được một ván |
| **Redis Cluster (Ticket SETNX & Snapshot)** | Hạ tầng có sẵn: chặn ticket replay và bảo hiểm Zero Data Loss cho Engine |
| **Kafka Cluster (Event Streaming)** | Hạ tầng có sẵn: đẩy sự kiện sau trận cho Dashboard và PostgreSQL |

**Ngoài Giai đoạn 1:** Cluster Sharding đa node + SBR (§9.1) · trạng thái `RESYNCING` đa node tự động · nén LZ4.

## Quyết định đã chốt (2026-09-05) — Task 1 không còn bị chặn

| # | Câu hỏi | Quyết định | Hệ quả |
|---|---|---|---|
| 1 | Project `uni-realtime` nằm ở repo nào? | **Thay thế code ngay trong repo hiện tại**, giữ lại observability (chốt lại sau lần quyết đầu — không tách repo mới nữa) | Toàn bộ `src/`, `docker/`, `docker-compose.yml`, `pom.xml` cũ đã xoá trên nhánh `feat/uni-realtime-p1-scaffold`. Giữ: `observability/`, `docs/`, `scripts/daily-report.mjs`. Code alerting + log JSON port sang module `modules/uni-observability/`. `CLAUDE.md` và `README.md` đã viết lại cho hệ thống mới |
| 2 | Maven hay Gradle? | **Maven** | Đúng giả định của `plan.md` — không phải sửa lệnh verification nào |
| 3 | TLS terminate ở đâu? | **LB/Ingress terminate** — Gateway pod nhận WS plaintext | Task 6: pipeline Netty **không có `SslHandler`**. §10.4: bỏ phần CPU handshake TLS khỏi ước tính pod. Ràng buộc mới: ingress phải passthrough WebSocket upgrade và không đặt idle-timeout ngắn hơn heartbeat |
| 4 | Game nào cần `tick_mode: FIXED`? | **Không — GĐ1 chỉ có quiz, COALESCE là đủ** | Task 3 chỉ hiện thực đường COALESCE. `FIXED` **vẫn có trong schema** Game Definition (Task 11) nhưng chưa có implementation — nạp definition có `tick_mode: FIXED` phải **fail nhanh lúc nạp** |

> Đường dẫn trong `plan.md` tính tương đối so với root repo hiện tại. Module Maven nằm dưới
> `modules/` với prefix `uni-`: `uni-protocol` / `uni-observability` / `uni-gateway` /
> `uni-engine`. Ngoài 3 module plan giả định, **`uni-observability`** là module thứ tư giữ phần
> observability kế thừa — không nằm trong task nào của GĐ1.

## Quyết định đã chốt (2026-09-06) — Product/Business trả lời 3/5 câu ở system-architecture.md §7.5

| # | Câu hỏi | Quyết định | Hệ quả |
|---|---|---|---|
| 1 | Công thức điểm Quiz GĐ1 (Product) | Trắc nghiệm 1/4 đáp án, đúng = 100đ, sai = 0đ, không bonus tốc độ | **Đã hiện thực 2026-09-06**: `FormulaScoreCalculator.binaryChoice()` (T2, bọc `ScoringFormula` của T11) thay `PlaceholderScoreCalculator` (đã xoá). 46/46 test `uni-engine` pass. Chi tiết: [system-architecture.md §2.5](../../architect/system-architecture.md#25-game-definition--guardrails) |
| 2 | Hệ thống chạy bao nhiêu giờ/ngày (Business) | Chạy **cả ngày**; ca điểm/thi đấu chỉ 18h50–21h30 | Rủi ro "chỉ chạy 4–6 tiếng/ngày" ở ADR-002 **không xảy ra** — giữ nguyên Pekko Cluster Sharding luôn-bật, không cần đảo ngược |
| 3 | Quy mô trường lớn nhất sau 1 NAT IP (Business) | Ước lượng **4.000** (theo quy mô phiên/lớp lớn nhất thực tế đang chạy — không phải số đo IP trực tiếp) | Ngưỡng L1 rate-limit theo IP nâng từ 300 → **4.000 handshake/phút**. Cần PH-1 xác nhận lại bằng số đo thật. **Chưa có code** — L1 admission control vẫn chưa có điểm gắn trong repo (xem Task 7) |
| — | (Ngoài 5 câu ở §7.5) Trần `HttpObjectAggregator` ở Gateway | Nâng **8KB → 50KB** — trần chung mọi gói WS, tách biệt với ràng buộc cứng `RoomStateSnapshot < 5KB` (giữ nguyên) | **Đã sửa code 2026-09-06**: `GatewayPipeline.MAX_HTTP_AGGREGATED_CONTENT_BYTES` = 50KB |

**Còn treo:** câu 1 (`missed_step_policy` mặc định) và câu 3 (ngân sách hạ tầng hàng tháng) của
system-architecture.md §7.5 — Product/Business chưa trả lời.

> [!NOTE]
> Cập nhật 2026-09-06: công thức điểm Quiz và trần `HttpObjectAggregator` **đã có code thật**
> (xem hàng tương ứng ở trên). Vẫn còn treo: ngưỡng L1 IP 4000 mới ở tài liệu, chưa có code
> (chưa có điểm gắn — Task 7); `missed_step_policy` mặc định và ngân sách hạ tầng vẫn chờ trả lời.

## Phụ thuộc ngoài phạm vi — đã biết, chưa xử lý

| ID | Nội dung | Hệ quả nếu không làm |
|---|---|---|
| **PH-1** | Load-test harness (§16) chưa khởi động | Mọi con số capacity vẫn là giả thuyết. Ngưỡng `max_handshake_per_sec` cho admission control (§10.4) chưa có cơ sở |
| **PH-3** | Client contract: ring buffer + `sequence` + RESYNC (§9.3) | SLA *"mất dữ liệu = 0"* (§15.2) **không có cơ sở** dù server làm đúng. Chaos test §16.5 bước 5 không thể pass |

> [!WARNING]
> **Rủi ro vận hành đã biết của Giai đoạn 1:** không có Cluster Sharding nghĩa là mất một
> Engine pod = các phòng trên pod đó **chết cho tới khi pod lên lại**. Đây là đánh đổi có chủ
> đích ở mức 2–3k CCU, **phải bỏ trước Giai đoạn 2** và phải hiển thị rõ trên UI.

## Governance — trạng thái thật

Kit đã cài (skill `onboarding`, 2026-09-06): `project-context.yaml`, `rules/spring/`,
`scripts/governance-check.sh` + 5 gate. `bash scripts/governance-check.sh` hiện **xanh cả 5**.

> [!CAUTION]
> **Hai gate xanh vì chưa có gì để kiểm, không phải vì đã phủ.** `validate-trace` skip toàn bộ
> (`docs/specs/bdd/` không tồn tại → không đòi `@trace` tag nào của code) và ship gate mới chỉ
> đọc thấy `phase=dev`. Checklist build/test thật trong `plan.md` vẫn là thứ chứng minh code
> chạy đúng — gate không thay được nó.
>
> `docs/principles.md` vẫn chưa có. `validate-skill-graph` được vá để skip ở repo đích (nó kiểm
> tính toàn vẹn của repo kit: `router.yaml`, `skills/`).

## State (machine-readable)
```yaml
phase: dev
track: standard
last_skill: tdd
next_skill: tdd
progress: "T1, T2, T4, T5, T10 xong. T6 MOT PHAN xong (GatewayPipeline + WS handshake +
  ChannelAttributes + room_id trust boundary - 7/7 test); TicketVerifier van la interface
  KHONG CO implementation that vi G1a/G1c chua chot - khong duoc tu bien verifier gia dua len
  staging/production. T5 = RouteCache (lazy-learned, khong TTL) + FrameChannelClient (round-robin
  -> hoc tu owner_pod_id -> gui thang, evict khi pod dut ket noi) - 15/15 test o uni-gateway.
  T10 = RoomOwnership/ModuloRoomOwnership (Math.floorMod, khong dung % truc tiep) + 
  RoomOwnershipHandler (tra NOT_OWNER dung dinh dang de FrameChannelClient cua T5 hoc duoc) -
  21/21 test o uni-engine, grep '% N|modulo' chi khop dung 1 file. T7 MOT PHAN xong: TokenBucket
  (fixed-window) + RateLimitHandler that (SUBMIT_ANSWER 3/1s, UPDATE_DRAFT 10/10s, HEARTBEAT
  2/30s, khoa theo student_id qua 1-instance-per-connection) - 24/24 test o uni-gateway; L1
  IP admission control (300 handshake/phut) CHUA lam vi chua co diem gan trong repo. T8 xong:
  RoomRegistry (Map room_id -> Set Channel, ConcurrentHashMap) + Broadcaster
  (retainedDuplicate() - da prove-it bang cach doi tam sang retain() de xac nhan FanoutTest
  thuc su Red truoc khi tin Green) - noi day that vao TicketAuthHandler (add khi join) +
  RoomRouteHandler (remove khi channelInactive, vi TicketAuthHandler tu go khoi pipeline sau
  join). T9 MOT PHAN xong: BackpressureHandler (channelWritabilityChanged -> toggle autoRead +
  Counter channel_not_writable_total) ap dung dong nhat o ca 3 hop (WS client, GW->Engine,
  Engine accepted channel), WRITE_BUFFER_WATER_MARK 32/64KB; Broadcaster mo rong DeliveryClass
  (!isWritable + BEST_EFFORT -> drop, CRITICAL -> close, da prove-it bang cach dao logic) -
  40/40 test o uni-gateway, 23/23 o uni-engine, toan reactor xanh. CHUA noi duoc chuoi cu the
  "mailbox RoomActor day -> Engine tu dung doc dung ket noi" vi 1 connection multiplex nhieu
  phong (ADR-001) nen khong map 1-1 duoc - gioi han kien truc that, khong phai thieu sot.
  ScoreCalculator dung PlaceholderScoreCalculator tam vi cong thuc diem Product chua chot
  (§9.2 cau 1). T11 xong: GameDefinition + Step (co nextStepIds tao graph) + ScoringFormula
  (sealed interface dong, 7 toan tu, khong eval/script) + DefinitionLoader (tu choi luc nap:
  rong, tick_mode FIXED, maxTransitions<=0, startStepId/nextStepIds khong ton tai, chu trong
  DFS 3 mau) - 37/37 test o uni-engine, da prove-it bang cach vo hieu hoa rejectCycles.
  SPIKE Pekko scheduler DAT: 1000 actor, single-shot timer tu hen lai moi chu ky 200ms,
  -XX:ActiveProcessorCount=2, 2 lan chay doc lap deu p99 lech 36-37ms (<50ms) va CPU dinh
  5-6% (<30%) - quyet dinh GO, giu nguyen thiet ke ADR-4. Bao cao: spike-pekko-timer.md.
  T12 xong: EngineMetrics/GatewayMetrics dang ky eager (actor_processing_latency,
  actor_mailbox_depth, fanout_latency, handshake_rate, channel_not_writable_total) - VERIFY
  BANG APP CHAY THAT (mvn spring-boot:run + curl /actuator/prometheus tren ca 2 service, khong
  chi tin unit test), phat hien va sua 2 lo hong: (1) BackpressureHandler dang ky metric lazy
  moi khi co connection moi -> khong hien dien luc pod moi start; (2) EngineMetrics/
  GatewayMetrics chua he la Spring bean -> khong ai khoi tao trong app that. Them
  MetricsConfiguration (@Bean) ca 2 module. trace_id sinh tai TicketAuthHandler luc handshake,
  RoomRouteHandler dong dau vao InternalHeader cho MOI message forward - toi bien Gateway,
  chua toi Engine that (cho Task 13 noi FrameChannelClient.send). 88/88 test (46 gateway + 42
  engine), toan reactor xanh. Ke tiep: T1/T2/T4/T5/T8/T10/T11/T12 xong + SPIKE dat - task sach
  duy nhat con lai la Task 13 nhung no phu thuoc Sync checkpoint (can T3/T7/T9 dong han) -
  lua chon thuc te la quay lai chot G1a/G1c/G2a/G2b de mo khoa T3/T6.
  2026-09-06 (vong 2): Product/Business tra loi 3/5 cau o system-architecture.md §7.5 - cong
  thuc diem quiz (1/4 dap an, dung=100/sai=0, khong bonus toc do), gio van hanh (chay ca ngay,
  ca diem 18h50-21h30, ADR-002 giu nguyen khong dao nguoc), nguong L1 NAT IP (uoc luong 4000,
  chua phai so do that, cho PH-1). Them 1 quyet dinh moi ngoai 5 cau: tran HttpObjectAggregator
  Gateway 8KB->50KB (KHONG phai cau tra loi cho G2a - G2a van con treo). Da ghi vao
  system-architecture.md + tech-design.md.
  2026-09-06 (vong 3): dong not ScoreCalculator that. Xoa PlaceholderScoreCalculator. Vet thu
  BinaryChoiceScoreCalculator dung rieng ROI tu nhan ra trung lap voi ScoringFormula (T11) va
  mau thuan voi chinh cau da ghi o §2.5 ('bieu dien dung bang tap toan tu gioi han - khong can
  mo rong') - xoa, thay bang FormulaScoreCalculator boc ScoringFormula,
  FormulaScoreCalculator.binaryChoice() = Multiply(IsCorrect(), Constant(100)).
  ScoreCalculator.award() them tham so correctAnswerIds, noi qua RoomState.startQuestion (3
  tham so) va RoomActor.StartQuestion (3 field). Test moi: FormulaScoreCalculatorTest (3 case)
  + RoomActorTest.should_award0_when_answerDoesNotMatchTheCorrectChoice. Cung sua luon
  GatewayPipeline.MAX_HTTP_AGGREGATED_CONTENT_BYTES 8KB->50KB (khop quyet dinh cung ngay, tranh
  code lech tai lieu). mvn -pl :uni-engine test: 46/46 pass. mvn test toan reactor tu root:
  BUILD SUCCESS ca 4 module. Grep bat buoc sach: client_timestamp_ms chi o doc-comment/field
  telemetry, .retain() rong o uni-gateway/src/main. Khong con 'Ghi chu con treo' nao cho Task 2.
  Con treo thuc su: missed_step_policy mac dinh (Product), ngan sach ha tang (Business),
  G1a/G1c/G2a/G2b/G3 (ky thuat), nguong L1 IP 4000 moi o tai lieu chua co code.
  2026-09-07: T3 (tick coalescing, ADR-4) xong. Truoc khi code phai chot 2 quyet dinh ky thuat
  dang chan T3 o tech-design.md §9.1: G2b (chon D1-D4 - delta muc nguoi choi, khong dung .proto)
  va G2a (N=10 lan flush thi gui 1 full snapshot). RoomActor truoc Task 3 khong co roster/join
  gi ca (Task 2 note da ghi ro thuoc Task 3) - them RoomActor.JoinRoom + RoomState.players
  (Map<String,PlayerRecord>, index gan 1 lan luc join, khong doi lai). RoomState.isDirty()/
  flush()/buildDeltaSnapshot()/buildFullSnapshot() thuan du lieu (Pekko-free nhu thiet ke goc);
  dirty-scheduling that (flushScheduled, lastFlushAtMs, TimerScheduler, Behaviors.withTimers)
  nam o RoomActor dung theo pseudocode ADR-4 o tech-design v3.0 §6.2. RoomActor.create() them
  tham so TickMode (sai COALESCE -> IllegalArgumentException ngay luc goi, lop fail-fast thu hai
  sau DefinitionLoader cua T11) va ActorRef<GameMessage> broadcastTarget (dung khuon replyTo nhu
  SubmitAnswer/JoinRoom - RoomActor khong biet gi ve transport). Test moi TickCoalescingTest (5
  case) dung ActorTestKit + ManualTime THAT cua Pekko (khong phai BehaviorTestKit nhu
  RoomActorTest) vi co che can kiem la timer that su chay. Prove-it: tam doi buildDeltaSnapshot()
  sang duyet players.keySet() thay vi dirtyStudentIds - xac nhan dung 1/5 test Red truoc khi tra
  lai Green. mvn -pl :uni-engine test: 51/51 pass. mvn clean install toan reactor: BUILD SUCCESS.
  Grep bat buoc van sach (client_timestamp_ms, .retain(), % N|modulo). Chua lam (thuoc Task 13,
  khong phai thieu sot T3): noi broadcastTarget voi FrameChannelServer/kenh noi bo that; thuc su
  phat QUESTION_STARTED/GAME_OVER/TEACHER_COMMAND/CONNECTION_DEGRADED/StudentJoined ra ngoai
  (chua task nao lam viec nay); luong roi phong (connected=false) - Gateway chua co cach bao
  Engine.
  2026-09-07 (tiep): T7 dong han (tu 'mot phan' thanh xong). Diem con thieu duy nhat la L1 IP
  admission control (300->4000 handshake/phut theo quyet dinh 2026-09-06, system-architecture.md
  §5.6) - truoc day chua co diem gan trong repo, nay them IpAdmissionController (TokenBucket
  nhan theo IP qua ConcurrentHashMap chia se toan pod, giong khuon RoomRegistry) +
  IpAdmissionHandler (chay o channelActive, la handler DAU TIEN trong GatewayPipeline - truoc ca
  BackpressureHandler - de mot IP vuot nguong khong ton cycle CPU nao cho HTTP parsing/WS
  upgrade). GatewayPipeline.addTo/GatewayBootstrap them tham so IpAdmissionController. Test moi:
  IpAdmissionControllerTest (3 case, logic thuan) + IpAdmissionHandlerTest (3 case, EmbeddedChannel
  voi remoteAddress0() override de gia lap IP that vi EmbeddedChannel mac dinh khong tra ve
  InetSocketAddress). Prove-it: tam bo qua verdict cua controller trong handler - xac nhan dung
  1/3 test Red truoc khi tra lai Green. mvn -pl :uni-gateway test: 52/52 pass. mvn clean install
  toan reactor: BUILD SUCCESS (uni-engine 51/51 khong doi). Grep bat buoc van sach. KHONG lam L2
  (khoa theo student_id, 10 handshake/phut) hay L3 (admission control toan pod, §6.5) - ca hai
  co trong system-architecture.md §5.6 nhung khong nam trong AC goc cua plan.md Task 7, mo rong
  se la tu them pham vi. Khong them metric Prometheus rieng cho luot tu choi L1 (chi log.warn,
  dung khuon TicketAuthHandler xu ly ticket bi tu choi). Task sach con lai: Task 13 (cho Sync
  checkpoint - chi con T9 dong han, T3/T7/T11 da xong). Task 6 van cho G1a/G1c tu doi dich vu nen
  tang - khong tu quyet duoc trong noi bo.
  2026-09-07 (tiep 2): T13 (walking skeleton) MOT PHAN xong. Xay moi RoomSupervisor (engine) -
  spawn RoomActor luoi theo room_id luc JOIN_ROOM dau tien, dich GameMessage thanh
  RoomActor.Command, hoc subscriber-set moi phong tu JOIN_ROOM de fan-out dung tap connection
  (thay vi 1 target co dinh nhu T3 gia dinh tam). ChannelReplyActor (engine, moi) - diem DUY
  NHAT dong dau InternalHeader{owner_pod_id, delivery_class} truoc khi ghi ra Channel -
  RoomActor/RoomState van khong biet gi ve pod id/delivery class, dung thiet ke
  transport-agnostic cua T3. EngineResponseRouter (gateway, moi) - ANSWER_ACK di thang 1 hoc
  sinh (tra RoomRegistry theo student_id), con lai qua Broadcaster co san, NOT_OWNER bi bo qua
  (khong payload), luon clearInternal() truoc khi toi client. RoomRouteHandler sua - goi
  EngineSender.send() that thay vi fireChannelRead roi khong ai doc (dung nhu T12 da du doan
  truoc); tien the ap them ranh gioi tin cay cho student_id giong room_id (T6 §10.6) - lo hong
  nho truoc day (envelope student_id khong bi ep ve gia tri da xac thuc) chua ai phat hien vi
  chua co gi tieu thu message do. RouteCache.evictPod doi void -> Set<String> (room bi anh
  huong); FrameChannelClient them onPodDisconnected - nen tang cho §9.7 CONNECTION_DEGRADED (co
  che co roi, CHUA test thanh 1 chuoi hoan chinh). EngineNetworkLifecycle/
  GatewayNetworkLifecycle (.../boot/, moi) - lan dau Spring Boot that su khoi dong Netty/Pekko -
  DA CHAY THAT (spring-boot:run + curl/netstat): Engine bind that 9100+8090; Gateway chi bind
  8080 - cong WS 9000 CO CHU Y khong mo vi GatewayNetworkLifecycle co
  @ConditionalOnBean(TicketVerifier.class) va chua co bean that (G1a/G1c chua chot) - dung hanh
  vi mong muon, dung theo dung cam cua TicketAuthHandler ve verifier tam. Test moi
  WalkingSkeletonTest (modules/uni-e2e, module MOI) dung socket that hoan toan (WS client that
  qua Netty WebSocketClientHandshaker, khong EmbeddedChannel nao) - join+full snapshot, submit+
  AnswerAck+delta ca 2 client, im lang -> 0 goi, replay cung sequence -> khong doi. Phat hien va
  sua giua chung: spring-boot-maven-plugin repackage (khong classifier) thay artifact chinh cua
  uni-gateway/uni-engine bang jar thuc thi (class duoi BOOT-INF/classes), lam mvn clean install
  TU ROOT fail voi 'package khong ton tai' cho MOI class (dung -pl :uni-e2e -am test-compile thi
  khong sao vi dung truoc phase package) - sua bang them <classifier>exec</classifier> vao ca
  hai pom.xml. mvn clean install toan reactor tu root: BUILD SUCCESS, 119 test (4 protocol + 59
  gateway + 55 engine + 1 e2e), khong leak. Prove-it: RoomSupervisorTest (bo qua subscriber set)
  + EngineResponseRouterTest (luon broadcast thay vi gui rieng ACK) - ca hai xac nhan dung 1
  test Red truoc khi Green. CHUA lam (co chu y, khong phai quen): Docker Compose that (2 GW + 2
  Engine) - Docker daemon KHONG chay trong moi truong nay (docker info loi ket noi
  dockerDesktopLinuxEngine), chua co Dockerfile nao - khong viet mu thu khong verify duoc; kich
  ban da-pod/giet-pod ghep thanh 1 test uni-e2e (co che co san, chua ghep); TeacherCommand.
  NEXT_STEP/noi dung cau hoi qua day (khong co dinh dang nao duoc chot - T11 note) - test dung
  RoomSupervisor.GetRoomActor (hook test/ops-only) de bat dau cau hoi; PAUSE/KICK_STUDENT/luong
  roi phong - log canh bao, khong wire. T9: xac nhan lai gioi han kien truc van dung sau khi
  noi day that (1 connection dung chung MOI phong giua 1 cap pod, khong doi tu ADR-001) - AC dau
  tien cua T9 van [ ] co chu y."
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-07"
```

**Ship-ready khi:** `dev_selftest: pass` **và** `qc_status ∈ {pass, na}` **và** `trace: pass`.
