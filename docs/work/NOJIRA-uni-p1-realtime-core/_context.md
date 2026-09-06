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
  IP admission control (300 handshake/phut) CHUA lam vi chua co diem gan trong repo. Toan
  reactor xanh. ScoreCalculator dung PlaceholderScoreCalculator tam vi cong thuc diem Product
  chua chot (§9.2 cau 1). Ke tiep: SPIKE Pekko timer (truoc T3), T8 (RoomRegistry + fan-out,
  phu thuoc T6), hoac T11 (Game Definition toi gian, phu thuoc T2)"
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-06"
```

**Ship-ready khi:** `dev_selftest: pass` **và** `qc_status ∈ {pass, na}` **và** `trace: pass`.
