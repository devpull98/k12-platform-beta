# Runbook vận hành: Cấm Auto-scaling Engine trong ca thi đấu

**Trạng thái tại thời điểm viết (2026-09-07):** `LeaseBasedRoomOwnership` (plan.md Task 14) đã có
code, **chưa verify với Valkey thật**, và **chưa nối vào production mặc định**
(`uni.engine.room-store.enabled=false`). Cho tới khi task đó được bật và kiểm chứng bằng chaos test
thật, runbook này vẫn có hiệu lực **đầy đủ, không có ngoại lệ**.

> [!IMPORTANT]
> **Cập nhật 2026-09-09 — `room_id % N` đã bị XOÁ HẲN khỏi code, theo yêu cầu người dùng.**
> `ModuloRoomOwnership` không còn tồn tại; `LeaseBasedRoomOwnership` giờ là cơ chế ownership DUY
> NHẤT, luôn bật (không còn cờ `uni.engine.room-store.enabled` — Valkey/room-store giờ là
> dependency bắt buộc để Engine khởi động). **Rủi ro cụ thể mà runbook này viết ra để chặn
> (đổi `N` làm vỡ hash toàn bộ phòng cùng lúc) không còn tồn tại trong code nữa** — không có `N`
> nào để đổi. Nhưng runbook **CHƯA được gỡ bỏ**: xem mục "Vì sao vẫn còn hiệu lực" ngay dưới đây
> và mục "Điều kiện để nới lỏng" — điều kiện đó (verify LeaseBasedRoomOwnership + Valkey Cluster
> thật ở staging) **vẫn CHƯA đạt**, mới chỉ Docker 1 máy. Xem `docs/work/NOJIRA-uni-p1-realtime-core/_context.md`
> để biết đầy đủ quyết định này.

## Vì sao runbook này tồn tại (lịch sử — giữ nguyên, xem cảnh báo ở trên cho hiện trạng thật)

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

## Vì sao vẫn còn hiệu lực dù rủi ro gốc đã hết (2026-09-09)

Rủi ro CỤ THỂ ("đổi N làm vỡ hash hàng loạt") đã hết vì không còn `N`/modulo trong code —
`LeaseBasedRoomOwnership` không quan tâm tổng số pod, mỗi phòng tự giành lease độc lập, thêm/bớt
pod không rehash gì cả về mặt thuật toán. Nhưng runbook này KHÔNG tự động hết hiệu lực vì:

1. **`LeaseBasedRoomOwnership` + Valkey chưa từng verify ở staging thật** — mọi bằng chứng hiện có
   (`DockerComposeChaosIT`) chỉ chạy qua Docker 1 máy, không phải Valkey Cluster + nhiều node thật.
2. **Rủi ro vận hành khác vẫn còn** (không liên quan gì tới modulo): room-store giờ là dependency
   BẮT BUỘC — nếu Valkey Cluster thật gặp sự cố trong ca thi đấu, MỌI phòng MỚI (chưa giành được
   lease) sẽ không khởi động được cho tới khi Valkey phục hồi (§ "Callers phải coi owner rỗng là
   drop frame" trong `RoomOwnership` javadoc) — đây là đánh đổi có chủ đích (xem quyết định người
   dùng 2026-09-09), nhưng vẫn là 1 rủi ro vận hành thật cần DevOps biết trước khi mở ca thi đấu.

Tóm lại: lý do CŨ (modulo rehash) đã đóng, nhưng điều kiện "nới lỏng" ở dưới vẫn đòi hỏi verify
Valkey Cluster thật ở staging — chưa đạt thì vẫn thận trọng với việc scale Engine giữa ca thi.

## Khung giờ cấm tuyệt đối

**18h50 – 21h30** (ca điểm/thi đấu, quyết định Business 2026-09-06 —
[`_context.md`](../work/NOJIRA-uni-p1-realtime-core/_context.md)).

Trong khung giờ đó, **KHÔNG được**:

1. Có bất kỳ `HorizontalPodAutoscaler` nào target Deployment `uni-game-engine`.
2. Chạy `kubectl scale deployment uni-game-engine --replicas=<N khác>` thủ công.
3. Rolling update Engine pod theo cách thay đổi tập giá trị `ENGINE_POD_ID` đang chạy (một rolling
   update giữ nguyên tập `ENGINE_POD_ID` — ví dụ chỉ đổi image tag — KHÔNG nằm trong lệnh cấm này).
   (2026-09-09: `ENGINE_POD_COUNT` không còn tồn tại kể từ khi `room_id % N` bị xoá — số lượng
   Engine pod tự do thay đổi về mặt thuật toán ownership; lệnh cấm này vẫn giữ nguyên chỉ vì
   `LeaseBasedRoomOwnership` + Valkey Cluster thật chưa verify ở staging, xem mục phía trên.)
4. Lên lịch bất kỳ pipeline auto-deploy/CI-CD nào có khả năng chạm tới Deployment `uni-game-engine`
   trong khung giờ trên.

`uni-websocket-gateway` **không** nằm trong lệnh cấm này — Gateway là stateless (§2.3), scale tự do không
ảnh hưởng tới `RoomOwnership`.

## Checklist DevOps — ký xác nhận trước khi mở ca thi đấu

- [ ] Xác nhận **không có `HorizontalPodAutoscaler`** nào tồn tại cho Deployment `uni-game-engine`
      (`kubectl get hpa -n <namespace>` — không thấy dòng nào target `uni-game-engine`).
- [ ] (2026-09-09, `ENGINE_POD_COUNT` không còn tồn tại — mục này giờ chỉ còn phía Gateway) Xác
      nhận danh sách `ENGINE_PODS` (phía Gateway, hoặc registry Valkey nếu
      `uni.gateway.engine.pod-discovery.enabled=true`, Task 21) khớp đúng số Engine pod thực tế
      đang chạy.
- [ ] Xác nhận room-store (Valkey) đang khoẻ (`valkey-cli ping`) và có giám sát cảnh báo riêng —
      Valkey giờ là dependency bắt buộc để Engine khởi động/giữ phòng, không còn đường lui.
- [ ] Xác nhận **không có pipeline auto-deploy/rolling-update nào** được lên lịch chạy trong
      khung giờ 18h50–21h30 cho Deployment `uni-game-engine`.
- [ ] Nếu cần tăng công suất trước ca thi (ví dụ 12 → 14 pod theo kịch bản
      `system-architecture.md` §6.1.4): thực hiện **trước** 18h50 ít nhất 20 phút, xác nhận đủ
      pod healthy, rồi mới khoá chức năng auto-scale — không scale giữa chừng ca thi.

## Điều kiện để nới lỏng runbook này

Runbook này **không tự động hết hiệu lực** khi Task 14 có code — chỉ nới lỏng khi **cả hai** điều
kiện sau đều đạt:

1. `LeaseBasedRoomOwnership` (giờ luôn bật, không còn cờ `uni.engine.room-store.enabled`) đã chạy
   ổn định ở staging với Valkey Cluster thật (không phải môi trường dev không có Valkey).
2. Chaos test thật đã xác nhận: kill 1 Engine pod giữa trận **và** scale thêm/bớt pod giữa trận
   đều không làm mất phòng đang chạy — đo được thời gian phục hồi thực tế (khuyến nghị: vài giây,
   theo TTL đã cấu hình ở `uni.engine.room-store.lease-ttl-seconds`), không phải suy luận từ code.

Sau khi đạt cả hai, runbook có thể đổi từ "cấm tuyệt đối" thành hướng dẫn vận hành lease (theo
dõi `zombie_actor_stopped_total`, giám sát TTL/renewal) — cập nhật lại file này khi đó, không xoá.

## Tài liệu liên quan

- [`plan.md`](../work/NOJIRA-uni-p1-realtime-core/plan.md) Task 14 (fix thật) và Task 19 (runbook này).
- [`_context.md`](../work/NOJIRA-uni-p1-realtime-core/_context.md) — cảnh báo rủi ro vận hành GĐ1.
- [`system-architecture.md`](../architecture/system-architecture.md) ADR-002, ADR-007, §9.2 Rủi ro 4, §9.5 (ma trận rủi ro, dòng 6).
