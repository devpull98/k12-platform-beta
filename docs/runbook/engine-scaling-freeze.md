# Runbook vận hành: Cấm Auto-scaling Engine trong ca thi đấu

**Trạng thái tại thời điểm viết (2026-09-07):** `LeaseBasedRoomOwnership` (plan.md Task 14) đã có
code, **chưa verify với Valkey thật**, và **chưa nối vào production mặc định**
(`uni.engine.room-store.enabled=false`). Cho tới khi task đó được bật và kiểm chứng bằng chaos test
thật, runbook này vẫn có hiệu lực **đầy đủ, không có ngoại lệ**.

## Vì sao runbook này tồn tại

GĐ1 mặc định dùng `ModuloRoomOwnership` (`room_id % N`, xem
[ADR-007](../architecture/system-architecture.md#adr-007)) — `pod-count`/`pod-id` là **config
tĩnh** đọc lúc JVM khởi động (`application.yml`), không phải cluster membership tự phát hiện.
Đổi `pod-count` (thêm/bớt Engine pod) đồng nghĩa đổi `N` trong phép chia dư đó.

**Hệ quả nếu đổi N khi hệ thống đang có phòng sống:** hầu hết mọi `room_id % N` đổi kết quả cùng
lúc → Gateway gửi gói tin sang nhầm pod cho **toàn bộ phòng trên mọi pod**, không chỉ phòng của
pod bị thêm/bớt. Actor mới khởi tạo ở pod nhận nhầm không có state phòng cũ (vì
`DistributedRoomSnapshotStore` — phần phục hồi state của Task 14 — cũng đang tắt cùng cờ Valkey). Vỡ trận
hàng loạt, khác hẳn quy mô của một pod crash đơn lẻ (chỉ ảnh hưởng ~300-375 phòng của đúng pod
đó — xem system-architecture.md §6.1, "Đánh đổi chiến lược: 12-16 Pod nhỏ").

Chi tiết kỹ thuật đầy đủ: [system-architecture.md §9.2 Rủi ro 4](../architecture/system-architecture.md#9.2-rủi-ro-tầng-runtime--chấm-điểm-jvm--actor).

## Khung giờ cấm tuyệt đối

**18h50 – 21h30** (ca điểm/thi đấu, quyết định Business 2026-09-06 —
[`_context.md`](../work/NOJIRA-uni-p1-realtime-core/_context.md)).

Trong khung giờ đó, **KHÔNG được**:

1. Có bất kỳ `HorizontalPodAutoscaler` nào target Deployment `uni-game-engine`.
2. Chạy `kubectl scale deployment uni-game-engine --replicas=<N khác>` thủ công.
3. Rolling update Engine pod theo cách đổi `ENGINE_POD_COUNT` hoặc thay đổi tập giá trị
   `ENGINE_POD_ID` đang chạy (một rolling update giữ nguyên cả hai giá trị này — ví dụ chỉ đổi
   image tag — KHÔNG nằm trong lệnh cấm này, vì `pod-count`/`pod-id` không đổi).
4. Lên lịch bất kỳ pipeline auto-deploy/CI-CD nào có khả năng chạm tới Deployment `uni-game-engine`
   trong khung giờ trên.

`uni-websocket-gateway` **không** nằm trong lệnh cấm này — Gateway là stateless (§2.3), scale tự do không
ảnh hưởng tới `RoomOwnership`.

## Checklist DevOps — ký xác nhận trước khi mở ca thi đấu

- [ ] Xác nhận **không có `HorizontalPodAutoscaler`** nào tồn tại cho Deployment `uni-game-engine`
      (`kubectl get hpa -n <namespace>` — không thấy dòng nào target `uni-game-engine`).
- [ ] Xác nhận `ENGINE_POD_COUNT` và danh sách `ENGINE_PODS` (phía Gateway) đã **fix cứng**,
      khớp đúng số Engine pod thực tế đang chạy.
- [ ] Xác nhận **không có pipeline auto-deploy/rolling-update nào** được lên lịch chạy trong
      khung giờ 18h50–21h30 cho Deployment `uni-game-engine`.
- [ ] Nếu cần tăng công suất trước ca thi (ví dụ 12 → 14 pod theo kịch bản
      `system-architecture.md` §6.1.4): thực hiện **trước** 18h50 ít nhất 20 phút, xác nhận đủ
      pod healthy, rồi mới khoá chức năng auto-scale — không scale giữa chừng ca thi.

## Điều kiện để nới lỏng runbook này

Runbook này **không tự động hết hiệu lực** khi Task 14 có code — chỉ nới lỏng khi **cả hai** điều
kiện sau đều đạt:

1. `uni.engine.room-store.enabled=true` đã chạy ổn định ở staging với Valkey Cluster thật (không phải
   môi trường dev không có Valkey).
2. Chaos test thật đã xác nhận: kill 1 Engine pod giữa trận **và** scale thêm/bớt pod giữa trận
   đều không làm mất phòng đang chạy — đo được thời gian phục hồi thực tế (khuyến nghị: vài giây,
   theo TTL đã cấu hình ở `uni.engine.room-store.lease-ttl-seconds`), không phải suy luận từ code.

Sau khi đạt cả hai, runbook có thể đổi từ "cấm tuyệt đối" thành hướng dẫn vận hành lease (theo
dõi `zombie_actor_stopped_total`, giám sát TTL/renewal) — cập nhật lại file này khi đó, không xoá.

## Tài liệu liên quan

- [`plan.md`](../work/NOJIRA-uni-p1-realtime-core/plan.md) Task 14 (fix thật) và Task 19 (runbook này).
- [`_context.md`](../work/NOJIRA-uni-p1-realtime-core/_context.md) — cảnh báo rủi ro vận hành GĐ1.
- [`system-architecture.md`](../architecture/system-architecture.md) ADR-002, ADR-007, §9.2 Rủi ro 4, §9.5 (ma trận rủi ro, dòng 6).
