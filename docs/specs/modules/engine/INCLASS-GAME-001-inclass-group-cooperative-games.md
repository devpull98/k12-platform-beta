# Product Brief: In-class Group & Cooperative Games (Phase 2)

> **Artifact ID:** `INCLASS-GAME-001`
> **Layer:** Product Brief (canonical-flow step 3 — trước BDD, trước Tech Design)
> **Nguồn:** [`PO_Require_Game+nhóm_+tập+thể+Inclass.doc`](./PO_Require_Game+nhóm_+tập+thể+Inclass.doc) (yêu cầu gốc từ Product Owner)
> **Domain:** `engine` (chính) + `protocol` (mở rộng schema dùng chung)
> **Downstream:**
> - BDD: [`docs/specs/bdd/INCLASS-GAME-001-cooperative-boss.feature`](../../bdd/INCLASS-GAME-001-cooperative-boss.feature), [`-002-team-speed-race.feature`](../../bdd/INCLASS-GAME-002-team-speed-race.feature)
> - Tech Design: [`docs/specs/tech-design/INCLASS-GAME-001-inclass-group-game-tech-design.md`](../../tech-design/INCLASS-GAME-001-inclass-group-game-tech-design.md)
> - Work package: [`docs/work/NOJIRA-uni-p2-inclass-game/`](../../../work/NOJIRA-uni-p2-inclass-game/)

---

## 1. Bối cảnh & Vấn đề

`uni-realtime` Giai đoạn 1 chỉ phục vụ một chế độ chơi duy nhất: mỗi học sinh trả lời câu hỏi
độc lập (Solo Quiz), điểm số tính riêng từng người. Giáo viên dạy Inclass (trong giờ học, không
phải thi đấu) cần thêm các hình thức chơi **mang tính tương tác nhóm/tập thể** để tăng gắn kết
lớp học — đây là yêu cầu PO gốc trong `PO_Require_Game+nhóm_+tập+thể+Inclass.doc`, chưa có gì
trong hệ thống hiện tại đáp ứng được.

## 2. Mục tiêu (Goals)

Bổ sung 3 chế độ chơi mới lên trên hạ tầng RoomActor/Gateway đã có của Giai đoạn 1:

1. **`cooperative` (Đánh Boss Tập thể):** Cả phòng (12 học sinh) cùng đóng góp câu trả lời đúng
   vào một thanh tiến trình chung (`progress_meter`) để "hạ gục Boss" — ví dụ Boss Rồng Số Học.
2. **`team` (Chia nhóm thi đấu):** Chia phòng thành 2–4 nhóm, thi tốc độ (`first_to_finish`) hoặc
   tổng điểm (`sum_all`), có tính năng gõ nháp chung trong nhóm (`UPDATE_DRAFT`, debounce phía
   client 150ms, broadcast scoped chỉ trong `team_id`).
3. **`individual` (Cá nhân mở rộng):** Vẫn thi cá nhân nhưng thêm mốc tiến trình và tài nguyên
   dùng chung của cả phòng (`shared_resource`: thời gian hoặc mạng sống), trả lời sai bị trừ vào
   tài nguyên chung thay vì chỉ trừ điểm cá nhân.

## 3. Ngoài phạm vi (Non-goals)

- **Tích hợp `lms-worker`** (đẩy sự kiện kết quả/thảo luận nhóm/bầu tên nhóm qua Kafka để hệ
  thống LMS cũ ghi nhận cúp/thành tích) — từng nằm trong mục tiêu bản đầu, nhưng PO đã chốt **hủy
  hẳn** khỏi phạm vi (2026-09-09). Xem `docs/work/NOJIRA-uni-p2-inclass-game/plan.md` Task 24.

- **Không** đổi hành vi chế độ Solo hiện có của Giai đoạn 1 — 3 chế độ mới là bổ sung, không thay thế.
- **Không** làm load-test/xác định capacity cho các chế độ mới (PH-1 vẫn ngoài phạm vi, như Giai đoạn 1).
- **Không** xây UI/frontend — Product Brief này chỉ mô tả hợp đồng server-side (protocol + RoomActor).
- **Không** đổi cơ chế cách ly Kafka (`max.block.ms=0`, worker thread riêng) đã chốt ở Giai đoạn 1 —
  chỉ tái sử dụng, không thiết kế lại.

## 4. Ràng buộc kỹ thuật kế thừa từ Giai đoạn 1 (không được vi phạm)

- Hot path vẫn đơn luồng trong `RoomActor`, không I/O chặn (Valkey/Kafka) trên đường tính điểm.
- `DraftUpdate` phải scoped theo `team_id`, tuyệt đối không broadcast toàn phòng (rủi ro traffic
  storm đã ghi nhận ở `plan.md` Task 20's Risk Assessment).
- `client_timestamp_ms` (nếu `DraftUpdate` mang theo) không được dùng cho bất kỳ tính điểm nào —
  đúng quy tắc server-authoritative timestamp đã áp dụng cho `SubmitAnswer`.

## 5. Ánh xạ Yêu cầu PO → Phạm vi kỹ thuật (tóm tắt)

| Yêu cầu PO | Khái niệm kỹ thuật | Chi tiết |
|---|---|---|
| Chế độ chơi | `GameMode` (`SOLO`/`COOPERATIVE`/`TEAM`/`INDIVIDUAL`) | Xem Tech Design §2 |
| Thanh tiến trình | `ProgressMeterSnapshot` (`progress_target`, `stage_index`) | Xem Tech Design §2-3 |
| Điều kiện thắng | `WinConditionEvaluator` (`progress_completed`/`first_to_finish`/`most_points_when_time_up`) | Xem Tech Design §2 |
| Tài nguyên dùng chung | `shared_resource` (`time`/`lives`), phạt khi trả lời sai | Xem Tech Design §2 |
| Gõ nháp chung nhóm | `DraftUpdate` (`team_id`, debounce 150ms, scoped fan-out) | Xem Tech Design §2-4 |

Chi tiết đầy đủ (schema `.proto`, file dự kiến sửa, acceptance criteria từng task) nằm ở Tech
Design và `plan.md` — Product Brief này chỉ giữ vai trò "vì sao làm", không lặp lại "làm thế nào".

## 6. Trạng thái

Product Brief này được viết **sau** khi BDD/Tech Design/Plan đã tồn tại (khắc phục việc tầng
Product Brief bị bỏ qua khi feature này được khởi tạo) — nội dung tổng hợp lại từ Tech Design đã
có, không phải bước đầu tiên theo đúng thứ tự canonical flow. Chưa có code nào được viết cho
Giai đoạn 2 tại thời điểm này — xem `docs/work/NOJIRA-uni-p2-inclass-game/_context.md` để biết
trạng thái thực thi mới nhất.
