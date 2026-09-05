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
3. Các mục v3.0 liệt kê ở trên — **không đọc toàn bộ 1528 dòng**

## Dependencies
- **Code:** chưa có — đây là greenfield. Xem "Việc còn mở" bên dưới về vị trí project.
- **Cross-module:** không
- **Rules:** `rules/{stack}/` **chưa tồn tại trong repo này** — xem cảnh báo governance bên dưới

## Impact radius
- **Stores:** không (Giai đoạn 1 chưa có Redis snapshot, chưa có PostgreSQL trên hot path)
- **Messaging:** không (Kafka bị cắt khỏi Giai đoạn 1)
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

**Ngoài Giai đoạn 1:** snapshot Redis (§9.6) · Cluster Sharding + SBR + fencing (§9.1) ·
trạng thái `RESYNCING` · Kafka · dashboard fan-in (§11) · nén LZ4.

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

> [!CAUTION]
> **Framework kit chưa được cài vào repo này.** Không có `project-context.yaml` ở root,
> không có `docs/principles.md`, không có `rules/{stack}/`, và `scripts/` chỉ chứa
> `daily-report.mjs` + `kafka-setup.mjs` — **không có `governance-check.sh`**.
>
> Vì vậy các gate `validate-sdd-gate` / `validate-trace` / `validate-context-state`
> **không chạy được**. Checklist trong `plan.md` dùng lệnh build/test thật thay thế.
> Muốn có gate thật thì chạy skill `onboarding` trước.

## State (machine-readable)
```yaml
phase: dev
track: standard
last_skill: tdd
next_skill: tdd
progress: "T1 xong (protocol + round-trip test). Ke tiep: T2 RoomActor, T4 frame codec, T6 GW pipeline - 3 nhanh song song"
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-05"
```

**Ship-ready khi:** `dev_selftest: pass` **và** `qc_status ∈ {pass, na}` **và** `trace: pass`.
