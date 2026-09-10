# NOJIRA — Uni Realtime Giai đoạn 1: WebSocket Gateway + Game Engine

## 🎯 1. Tổng Quan & Phạm Vi (Scope & Specs Anchor)
- **Module:** `uni-realtime` (`uni-protocol`, `uni-websocket-gateway`, `uni-game-engine`, `uni-observability`)
- **Tài liệu chuẩn:** `docs/specs/tech-design/EdTech_Game_Realtime_Architecture_v3.0.md` & `docs/architecture/system-architecture.md`
- **Hạ tầng phụ thuộc:**
  - **Valkey Cluster (Redis):** Dùng cho Room Ownership Dynamic Lease (`room:lease:{id}`) & Hot Snapshot (<5KB).
  - **Kafka Cluster:** Push async events (`game.events.v1`) sau mỗi lượt trả lời/trận đấu.
  - **Gateway WSS (`/ws`):** Biên giao tiếp WebSocket Realtime mã hóa Protobuf v1.

---

## 🛠️ 2. Các Quyết Định Kiến Trúc Đã Chốt (Key Architectural ADRs)
1. **TLS Termination tại Ingress/LB:** Gateway Pod nhận WS plaintext. Ingress bắt buộc bật `Connection: Upgrade` và Idle Timeout > 30s (khuyến nghị 3600s).
2. **Tick Coalescing (200ms):** Gom các thay đổi điểm/tiến độ và broadcast chu kỳ 200ms (ADR-004).
3. **Lazy-Learned Routing (Gateway):** Gateway tự học vị trí Engine Pod sở hữu phòng sau câu trả lời đầu tiên (`RoutingStatus.NOT_OWNER`), tránh lookup trung gian.
4. **Protocol Split (v1 Protobuf):** Tách `game_message.proto` thành 5 domain schemas (`common`, `internal`, `client_events`, `server_events`, `game_message`).
5. **LZ4 Compression:** Nén nhip frame > 150 bytes bằng WireCompression flag `0x01` + LZ4 block.

---

## 🚨 3. Cảnh Báo Vận Hành & Rủi Ro Đã Biết (Known Risks & Operational Rules)
- **`room_id % N` (Modulo Ownership) đã bị xoá hẳn khỏi code (Task 23, 2026-09-09)** — không còn cờ
  `ENGINE_ROOM_STORE_ENABLED` (đã gỡ khỏi `application.yml`/`docker-compose.dev.yml`), không còn
  fallback modulo. `LeaseBasedRoomOwnership` (Valkey lease) giờ là **cơ chế duy nhất, luôn chạy** —
  Valkey trở thành hard dependency lúc Engine khởi động.
- **Cấm Auto-scaling Engine trong ca thi (18h50 - 21h30) vẫn còn hiệu lực** (`docs/runbook/engine-scaling-freeze.md`)
  — không phải vì rủi ro "vỡ hash modulo" nữa (đã hết), mà vì `LeaseBasedRoomOwnership`
  + `EnginePodDiscovery` mới chỉ verify qua Docker 1 máy, **chưa verify trên staging Valkey Cluster
  thật** (xem `docs/runbook/staging-valkey-cluster-verification.md`). Gỡ runbook này khi cả điều
  kiện staging đó đạt.
- **`GATEWAY_ENGINE_POD_DISCOVERY_ENABLED=true`** (env, mặc định `false`) bật cơ chế Gateway tự dò
  Engine pod mới qua Valkey registry (Task 21) — cờ này còn tồn tại thật, khác `ENGINE_ROOM_STORE_ENABLED` ở trên.

---

### 📊 4. Machine-Readable State
```yaml
phase: dev
track: standard
progress: "Code Giai doan 1 (T1-T23) da xong, mvn clean install toan reactor xanh khong leak
  (so test chinh xac chua re-verify trong phien nay - dung so o lan chay gan nhat, khong tu bia).
  Con GAP CHUA dong truoc khi ship that (khong phai '100% hoan thanh'): staging Valkey Cluster
  chua verify (moi Docker 1 may); PH-3 client contract (ring buffer/RESYNC) chua co doi nhan;
  JoinTokenVerifier that con chan boi G1a/G1c; nguong L1 IP 4000/phut chua do tai that (PH-1);
  missed_step_policy khac ZERO + ngan sach ha tang van cho Product/Business tra loi. Chi tiet day
  du (B1-B5, lich su tung task) xem git history cua file nay truoc ban rut gon 2026-09-10."
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-10"
```

**Ship-ready khi:** `dev_selftest: pass` **và** `qc_status ∈ {pass, na}` **và** `trace: pass`.
