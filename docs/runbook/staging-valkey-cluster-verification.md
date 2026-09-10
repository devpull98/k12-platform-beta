# Runbook: Verify `LeaseBasedRoomOwnership` trên Valkey Cluster staging thật

**Mục đích:** đóng điều kiện cuối cùng còn treo để gỡ
[`engine-scaling-freeze.md`](engine-scaling-freeze.md) và để công bố `LeaseBasedRoomOwnership`
sẵn sàng production — hiện mới chỉ verify qua Docker Compose 1 máy
(`docs/work/NOJIRA-uni-p1-realtime-core/plan.md` Task 14/23), chưa qua Valkey **Cluster** thật
nhiều node, chưa qua topology nhiều pod thật (K8s hay tương đương).

**Không phải việc của tài liệu này:** dựng hạ tầng staging (xem
[`production-deployment-and-configuration.md`](production-deployment-and-configuration.md) cho
cấu hình triển khai chung). Tài liệu này giả định staging đã tồn tại và mô tả CHÍNH XÁC cần đo gì,
đo bằng cách nào, và tiêu chí pass/fail — để không lặp lại lỗi "tin vào code mà không đo" đã xảy
ra nhiều lần trong repo này (xem `_context.md` B1/B2/B3 và các "bug thật phát hiện qua Docker
thật" ở Task 14/20/21).

> [!WARNING]
> **Cảnh báo staleness (2026-09-10):** `production-deployment-and-configuration.md` §3 hiện vẫn
> nhắc `ENGINE_POD_COUNT`, `ENGINE_ROOM_STORE_ENABLED`, và cụm "fallback modulo" như thể còn tồn
> tại — các config/khái niệm này đã bị **xoá khỏi code** ở Task 23 (`room_id % N` removal,
> 2026-09-09, commit `33faa97`). `LeaseBasedRoomOwnership` giờ LUÔN chạy, Valkey là dependency bắt
> buộc, không còn "bật/tắt". Tài liệu đó là do người dùng tự viết — chưa tự sửa lại, chỉ ghi chú ở
> đây để người đọc runbook này không bị lẫn giữa 2 tài liệu.

---

## 1. Điều kiện tiên quyết hạ tầng

| Thành phần | Yêu cầu tối thiểu | Vì sao |
|---|---|---|
| Valkey **Cluster** | ≥ 3 node thật (không phải 1 instance như `docker-compose.dev.yml`'s `room-store`) | Docker chưa từng test hành vi cluster (resharding, node failover) — chỉ test 1 instance |
| Engine pod | ≥ 3 pod thật, triển khai qua orchestrator thật (K8s hoặc tương đương) | `DockerComposeChaosIT` chỉ có 2-3 container trên 1 máy — chưa test qua network thật, chưa test cross-node latency |
| Gateway pod | ≥ 2 pod, `uni.gateway.engine.pod-discovery.enabled=true` | Để test cả B5 (thêm Engine pod mới) lẫn B1 (mất Engine pod) cùng lúc |
| Tải đại diện | Có khả năng mô phỏng ≥ 500-1000 kết nối/phòng đồng thời | PH-1 (load-test harness) vẫn chưa dựng — đây là lần ĐẦU TIÊN đo dưới tải thật, không phải 1-2 phòng test như Docker |
| Quyền hạ tầng | Kill được 1 pod Engine, kill được 1 node Valkey, scale được Engine pod, xem log/metrics của cả 2 phía | Không verify được nếu chỉ có quyền đọc |

Nếu bất kỳ điều kiện nào ở trên chưa đạt (đặc biệt "Valkey Cluster ≥ 3 node" và "tải đại diện"),
**đừng công bố "đã verify staging"** — verify một phần vẫn hữu ích nhưng phải ghi rõ phạm vi thật
đã đo, đúng tinh thần các mục "Chưa làm/còn treo" đã có trong `plan.md`.

## 2. Kịch bản cần đo (thứ tự khuyến nghị)

### 2.1. Baseline — lease acquisition đúng, không còn dấu vết modulo
- Tạo N phòng mới liên tiếp qua đường join thật (không dùng `RoomSupervisor.GetRoomActor`/
  `SpawnConfiguredRoom` test-hook).
- Xác nhận qua `redis-cli`/`valkey-cli CLUSTER` trên staging: key `room:owner:{room_id}` phân bổ
  theo thứ tự pod nào request TRƯỚC giành được (first-acquire-wins), KHÔNG theo bất kỳ công thức
  `room_id % N` nào — đối chiếu với `LeaseBasedRoomOwnership` javadoc.
- **Pass:** không có phòng nào bị NOT_OWNER liên tục (loop route sai); mỗi phòng ổn định về đúng
  1 pod sau lần route-học đầu tiên (§8.2 lazy-learned routing).

### 2.2. Engine pod crash — đo phân phối, không chỉ 1 mẫu
- Lặp lại **≥ 5 lần độc lập** (khác hẳn Docker chỉ chạy 1-6 lần cho toàn bộ lịch sử Task 14): với
  phòng đang có học sinh active, `kill -9`/xoá pod Engine đang giữ phòng đó.
- Đo: thời gian từ lúc kill tới lúc `redis-cli GET room:owner:{room_id}` đổi sang pod khác VÀ Hot
  Snapshot phục hồi đúng roster (dùng cách đo giống `DockerComposeChaosIT.recoveryMs`, có thể tái
  dùng code đo đó làm tham khảo, không nhất thiết chạy y hệt class JUnit đó trên staging).
- Xác nhận song song: client KHÔNG bị đóng socket (`CONNECTION_DEGRADED`, giữ mở, đúng §9.7) —
  không chỉ đo thời gian phục hồi phòng mà còn phải xác nhận trải nghiệm client thật.
- **Pass:** p50/p90/p99 thời gian phục hồi nằm trong khoảng dự kiến theo `lease-ttl-seconds` cấu
  hình thật của staging (Docker đo 21.8s-27.9s với TTL=20s — số thật ở staging có thể khác nếu TTL
  khác, hoặc nếu network latency tới Valkey Cluster cao hơn localhost). Không có phòng nào MẤT
  vĩnh viễn (không phục hồi sau khi TTL hết hạn nhiều lần TTL).

### 2.3. Thêm Engine pod mới giữa phiên (B5, Task 21)
- Trong lúc có tải, thêm 1 Engine pod hoàn toàn mới (chưa từng có trong danh sách Gateway biết).
- **Pass:** Gateway tự dial được pod mới trong vòng ≤ 1 chu kỳ poll
  (`GATEWAY_ENGINE_POD_DISCOVERY_POLL_INTERVAL_SECONDS`) mà KHÔNG restart Gateway; phòng mới tạo
  sau đó có thể rơi vào pod mới.

### 2.4. Scale DOWN có chủ đích (kịch bản CHƯA từng test ở Docker)
- Rút graceful 1 Engine pod đang giữ phòng active (khác "crash" ở 2.2 — đây là SIGTERM/drain có
  chủ đích, mô phỏng rolling update hoặc giảm tải kế hoạch).
- **Pass:** hành vi phải giống hệt 2.2 (không có cơ chế "bàn giao êm" nào khác trong code hiện
  tại — nếu code coi graceful shutdown khác crash, đó là phát hiện MỚI cần ghi lại, không phải giả
  định trước).

### 2.5. Valkey Cluster node failure (chưa từng test — Docker chỉ có 1 instance)
- Kill **1 node** trong Valkey Cluster (không phải toàn bộ cluster) trong lúc Engine đang có phòng
  active.
- **Pass:** Lettuce cluster client failover sang node còn lại mà không cần restart Engine pod;
  không có khoảng thời gian nào TOÀN BỘ phòng trên MỌI pod cùng "unresolved" (chỉ những phòng có
  slot dữ liệu rơi đúng vào node chết mới bị ảnh hưởng tạm thời, đúng tính chất Cluster). Nếu quan
  sát thấy ảnh hưởng lan ra toàn bộ phòng, đây là phát hiện NGHIÊM TRỌNG cần dừng lại, không phải
  lỗi nhỏ.

### 2.6. Valkey Cluster mất kết nối hoàn toàn (đúng hợp đồng "unresolved", Task 23)
- Chặn network Engine ↔ toàn bộ Valkey Cluster (không kill riêng lẻ, mô phỏng outage toàn cụm).
- **Pass:** đúng hành vi đã code ở Task 23 — phòng MỚI không tạo được (join bị drop, không crash
  actor nào), phòng ĐANG chạy trước đó vẫn giữ được lease trong cache tới khi TTL hết hạn thật (vì
  `renewAll()` sẽ fail liên tục nhưng actor vẫn sống bình thường tới khi lease hết hạn). Khi mạng
  phục hồi, phòng mới tạo lại được bình thường, không cần restart Engine.
- **Đây là rủi ro MỚI so với thời modulo:** trước đây mất Valkey chỉ mất phần Hot Snapshot (rơi về
  modulo); giờ mất Valkey nghĩa là KHÔNG PHÒNG MỚI NÀO tạo được tới khi phục hồi. Runbook DevOps
  (`production-deployment-and-configuration.md` §6) cần alert riêng cho "room-store/lease failure"
  — xác nhận alert đó thật sự bắn khi test kịch bản này.

### 2.7. Dưới tải đại diện (PH-1 vẫn treo — đây là lần đo đầu tiên có ý nghĩa)
- Lặp lại 2.2 dưới tải ≥ 500-1000 kết nối/phòng đồng thời thay vì 1-2 phòng test.
- **Pass:** không có suy giảm đáng kể so với 2.2 (baseline không tải) — nếu CÓ suy giảm, đây là dữ
  liệu thật đầu tiên cho câu hỏi "CPU actor cạnh tranh với business logic thật" mà spike Pekko
  (Task 3) đã cảnh báo trước là "giả định chưa kiểm chứng".

## 3. Sau khi verify — cập nhật tài liệu (không tự động, phải làm tay)

Khi (và chỉ khi) tất cả kịch bản ở mục 2 đã chạy thật và có số đo thật, cập nhật theo đúng thứ tự:

1. `docs/work/NOJIRA-uni-p1-realtime-core/_context.md` — ghi số đo thật vào state block (theo
   đúng phong cách các entry trước, không sửa lại phần "giá trị lịch sử" đã có).
2. `docs/work/NOJIRA-uni-p1-realtime-core/plan.md` Task 14 — cập nhật AC cuối cùng, đổi trạng thái
   "chưa verify staging" thành có ngày/số đo cụ thể.
3. `docs/runbook/engine-scaling-freeze.md` — mục "Điều kiện để nới lỏng": tick đủ điều kiện, đổi
   nội dung runbook từ "cấm tuyệt đối" sang hướng dẫn vận hành lease theo đúng mô tả đã ghi sẵn ở
   cuối file đó — **không xoá file**, chỉ đổi nội dung.
4. `CLAUDE.md` mục "Known Phase 1 trade-offs" — cập nhật câu "NOT verified against a real staging
   Valkey Cluster".
5. Nếu 2.5/2.6 (Valkey Cluster node failure/outage) phát hiện hành vi khác mô tả ở mục 2 — đó là
   phát hiện MỚI, cần thêm dòng riêng vào `system-architecture.md` §9.2 (theo đúng format "Rủi ro
   N" đã có), không chỉ sửa âm thầm.

## 4. Việc KHÔNG nằm trong phạm vi runbook này

- Dựng Valkey Cluster/K8s topology thật — đó là việc DevOps, tài liệu này không tự suy đoán
  manifest/Helm chart cụ thể.
- Quyết định ngưỡng `lease-ttl-seconds`/`poll-interval` mới cho production — chỉ đo số thật, không
  tự đổi giá trị mặc định trong `application.yml` mà không có quyết định riêng.
- Load-test harness đầy đủ (PH-1) — mục 2.7 chỉ cần MỘT lần đo có tải để có dữ liệu, không phải
  dựng hẳn hệ thống load-test lâu dài.
