# Technical Design Specification: In-class Group & Cooperative Games (Phase 2)

> **Artifact ID:** `INCLASS-GAME-001`  
> **Target System:** `uni-realtime` (WebSocket Gateway + Pekko Game Engine)  
> **Product Brief:** [`docs/specs/modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games.md`](../modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games.md)  
> **Business Spec (nguồn gốc):** [`PO_Require_Game+nhóm_+tập+thể+Inclass.doc`](../modules/engine/PO_Require_Game+nhóm_+tập+thể+Inclass.doc)  
> **System Architecture Reference:** [`docs/architecture/system-architecture.md §10`](../../architecture/system-architecture.md#10-ví-dụ-thực-chiến-chuyển-đổi-yêu-cầu-po-sang-đặc-tả-kỹ-thuật-dev-specs--kịch-bản-bdd)

---

## 1. Overview & Context

Hệ thống `uni-realtime` Phase 1 đã hoàn thiện hạ tầng Core (WebSocket Gateway + Pekko RoomActor cho Solo Quiz). Phase 2 bổ sung các chế độ chơi tương tác nhóm và tập thể phục vụ giờ học Inclass:
- **`cooperative` (Đánh Boss Tập thể):** Cả lớp 12 học sinh đóng góp câu trả lời đúng để hoàn thành thanh tiến trình `progress_meter` (ví dụ: Boss Rồng Số Học).
- **`team` (Chia nhóm thi đấu):** Chia phòng thành 2–4 nhóm thi đấu tốc độ (`first_to_finish`) hoặc tổng điểm (`sum_all`), có tính năng đồng bộ bản nháp gõ chung (`UPDATE_DRAFT`).
- **`individual` (Cá nhân mở rộng):** Thi đấu cá nhân kèm mốc tiến trình và phạt tài nguyên chung (`shared_resource`).
- **Tích hợp `lms-worker`:** Đẩy sự kiện qua Kafka `game.events.v1` để dịch vụ cũ ghi nhận cúp và lưu lịch sử bài tập.

---

## 2. PO Specs $\rightarrow$ Dev Technical Specs Mapping

| Yêu cầu PO (`PO_Require_Game...doc`) | Đặc tả Kỹ thuật Developer (Dev Specs) | Thành phần Codebase |
|---|---|---|
| **Chế độ chơi (Game Modes):**<br>- `cooperative`<br>- `team`<br>- `individual` | Enum `GameMode` (`SOLO`, `COOPERATIVE`, `TEAM`, `INDIVIDUAL`). Cấu hình `team_count` và `team_assignment` trong `RoomState`. | `modules/uni-protocol/.../game_message.proto`<br>`modules/uni-game-engine/.../definition/GameDefinition.java`<br>`modules/uni-game-engine/.../room/RoomState.java` |
| **Mechanic `progress_meter`:**<br>- `simple_bar`<br>- `staged_visual` | Thêm `progress_target` (số câu đúng cần thiết) và `progress_stages` (mốc visual %). Broadcast delta `progress_percentage` và `stage_index` trong `RoomStateSnapshot`. | `modules/uni-protocol/.../game_message.proto`<br>`modules/uni-game-engine/.../room/RoomActor.java` |
| **Điều kiện Thắng (`win_condition`):**<br>- `progress_completed`<br>- `first_to_finish`<br>- `most_points_when_time_up` | Modifiers trong `WinConditionEvaluator`. Khi điều kiện thỏa mãn, `RoomActor` đổi FSM sang `FINISHED` và dừng ván game. | `modules/uni-game-engine/.../scoring/WinConditionEvaluator.java` |
| **Phạt tài nguyên chung (`shared_resource`):**<br>- `time`<br>- `lives` | Cấu hình `shared_resource_type` và `penalty_value`. Trả lời sai trừ trực tiếp `step_deadline_at` hoặc `remaining_lives`. | `modules/uni-game-engine/.../room/RoomState.java` |
| **Gõ nháp chung nhóm (`UPDATE_DRAFT`):** | Event `UPDATE_DRAFT` với `team_id`. Client debounce 150ms. Engine chỉ broadcast cho 3 thành viên cùng nhóm. | `modules/uni-game-engine/.../room/RoomActor.java` |
| **Tương thích `lms-worker`:** | Đẩy `TeamSubmitExerciseEvent`, `GroupDiscussionEvent`, `VoteGroupNameEvent` sang Kafka topic `game.events.v1`. | `modules/uni-game-engine/.../events/GameEventPublisher.java` |

---

## 3. Protocol Schema Changes (`game_message.proto`)

```protobuf
syntax = "proto3";
package com.uni.realtime.protocol;

enum GameMode {
  GAME_MODE_UNSPECIFIED = 0;
  GAME_MODE_SOLO = 1;
  GAME_MODE_COOPERATIVE = 2;
  GAME_MODE_TEAM = 3;
  GAME_MODE_INDIVIDUAL = 4;
}

message TeamAssignment {
  string team_id = 1;
  string team_name = 2;
  repeated string student_ids = 3;
}

message DraftUpdate {
  string team_id = 1;
  string student_id = 2;
  string draft_content = 3;
  int64 client_timestamp_ms = 4;
}

message ProgressMeterSnapshot {
  int32 current_progress = 1;
  int32 target_progress = 2;
  int32 progress_percentage = 3;
  int32 stage_index = 4;
}
```

---

## 4. Architecture & Hot Path Guarantees

1. **Non-blocking Hot Path:** Mọi logic tính điểm, kiểm tra win condition và broadcast nháp đều diễn ra đơn luồng trong `RoomActor` RAM state.
2. **Async Kafka Event Publishing:** Lời gọi Kafka Producer thiết lập `max.block.ms = 0` đẩy event sang `game.events.v1` ở thread riêng, bảo đảm p99 < 100ms cho WebSocket client.
3. **Scoped Fan-out:** `DraftUpdate` được định tuyến scoped theo `team_id` trên Gateway / Engine, không broadcast toàn phòng.
