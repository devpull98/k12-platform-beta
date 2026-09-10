# Technical Design Specification: In-class Group & Cooperative Games (Phase 2)

> **Artifact ID:** `INCLASS-GAME-001`  
> **Target System:** `uni-realtime` (WebSocket Gateway + Pekko Game Engine)  
> **Product Brief (bản mới nhất):** [`docs/specs/modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games-v2.1.md`](../modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games-v2.1.md)  
> **Product Brief (V1.0, tiền nhiệm):** [`docs/specs/modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games.md`](../modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games.md)  
> **Business Spec (nguồn gốc):** [`PO_Require_Game+nhóm_+tập+thể+Inclass.doc`](../modules/engine/PO_Require_Game+nhóm_+tập+thể+Inclass.doc) (V1.0), [`...V2.1.doc`](../modules/engine/PO_Require_Game+nhóm_+tập+thể+Inclass%20V2.1.doc)  
> **System Architecture Reference:** [`docs/architecture/system-architecture.md §10`](../../architecture/system-architecture.md#10-ví-dụ-thực-chiến-chuyển-đổi-yêu-cầu-po-sang-đặc-tả-kỹ-thuật-dev-specs--kịch-bản-bdd)

> [!IMPORTANT]
> **Cập nhật 2026-09-10:** tài liệu này được viết trước khi Task 20-27 code xong — vài chỗ mô tả
> "dự kiến" ở dưới không khớp với implementation thật đã chốt sau đó. Đã sửa lại để khớp code +
> khớp phát hiện mới nhất từ việc diff trực tiếp 2 file `.doc` gốc (xem
> `docs/work/NOJIRA-uni-p2-inclass-game/plan.md` Task 27). Đây vẫn là tài liệu tech-design gốc
> (giữ giá trị lịch sử), không phải tài liệu sinh tự động từ code — `plan.md` mới là nơi có chi
> tiết đầy đủ nhất, cập nhật liên tục theo từng task.

---

## 1. Overview & Context

Hệ thống `uni-realtime` Phase 1 đã hoàn thiện hạ tầng Core (WebSocket Gateway + Pekko RoomActor cho Solo Quiz). Phase 2 bổ sung các chế độ chơi tương tác nhóm và tập thể phục vụ giờ học Inclass:
- **`cooperative` (Đánh Boss Tập thể):** Cả lớp 12 học sinh đóng góp câu trả lời đúng để hoàn thành thanh tiến trình `progress_meter` (ví dụ: Boss Rồng Số Học).
- **`team` (Chia nhóm thi đấu):** Chia phòng thành 2–4 nhóm thi đấu tốc độ (`first_to_finish`) hoặc tổng điểm (`sum_all`), có tính năng đồng bộ bản nháp gõ chung (`UPDATE_DRAFT`).
- **`individual` (Cá nhân mở rộng):** Thi đấu cá nhân kèm mốc tiến trình và phạt tài nguyên chung (`shared_resource`).

> Mục "Tích hợp `lms-worker`" từng ở đây đã bị PO chốt hủy khỏi phạm vi (2026-09-09) — xem
> `docs/work/NOJIRA-uni-p2-inclass-game/plan.md` Task 24.

---

## 2. PO Specs $\rightarrow$ Dev Technical Specs Mapping

| Yêu cầu PO (`PO_Require_Game...doc`) | Đặc tả Kỹ thuật Developer (Dev Specs) — **đã khớp code thật** | Thành phần Codebase |
|---|---|---|
| **Chế độ chơi (Game Modes):**<br>- `cooperative`<br>- `team`<br>- `individual` | Enum `GameMode` (`SOLO`, `COOPERATIVE`, `TEAM`, `INDIVIDUAL`). **Roster đội là TĨNH** — `GameDefinition.teamRosters` (`List<TeamAssignment>`), do CMS/upstream quyết định trước khi nạp vào Engine, **KHÔNG** có field `team_count`/`team_assignment` (random/manual) nào trong code — đây là gap schema đã ghi ở `plan.md` Task 27, chưa phải tính năng thật. | `modules/uni-protocol/.../uni/realtime/v1/common.proto`<br>`modules/uni-game-engine/.../definition/GameDefinition.java`<br>`modules/uni-game-engine/.../room/RoomState.java` |
| **Mechanic `progress_meter`:**<br>- `simple_bar`<br>- `staged_visual` | `progressTarget` (số câu đúng cần thiết) + `progressStages` (mốc visual %) trong `GameDefinition`. Broadcast `progress_percentage`/`stage_index` qua `ProgressMeterSnapshot` trong `RoomStateSnapshot`. **`progress_display_mode` (`simple_bar`/`staged_visual`) không có field riêng** — engine luôn gửi `stage_index`, không có cờ chọn kiểu hiển thị (gap schema, Task 27). | `modules/uni-protocol/.../uni/realtime/v1/{common,server_events}.proto`<br>`modules/uni-game-engine/.../room/RoomState.java` |
| **Điều kiện Thắng (`win_condition`):**<br>- `progress_completed`<br>- `first_to_finish`<br>- `most_points_when_time_up` | `WinConditionEvaluator` (pure function). Khi thoả mãn, `RoomActor`/`RoomState.endGame()` đổi FSM sang `FINISHED`, broadcast `GameOver`. **Luật "Hoà"** (2 phe chạm đích cùng lúc, hoặc bằng điểm khi hết giờ) — PO **tự flag chưa chốt** (V2.1 §5.3) — `singleHighestScorer()` trả rỗng khi hoà, đúng tinh thần "không tự bịa", không phải thiếu sót. `most_points_when_time_up` chưa có **trigger tự động** ("hết giờ") — hiện chỉ `TeacherCommand.END_GAME` (thủ công) kích hoạt được, xem `plan.md` Task 23/27. | `modules/uni-game-engine/.../scoring/WinConditionEvaluator.java`<br>`modules/uni-game-engine/.../room/RoomState.java` |
| **Phạt tài nguyên chung (`shared_resource`):**<br>- `time`<br>- `lives` | `GameDefinition.sharedResourceType` (`SharedResourceType.TIME`/`LIVES`/`NONE`) + `sharedResourcePenalty`. Trả lời sai trừ `deadlineMs` khi `type=TIME` — **đã xong**. `type=LIVES` **bị `DefinitionLoader` từ chối tại load-time** — PO không đặc tả số "lives ban đầu", chưa implement có chủ đích (không tự bịa số, xem `plan.md` Task 21/23). | `modules/uni-game-engine/.../definition/{GameDefinition,DefinitionLoader}.java`<br>`modules/uni-game-engine/.../room/RoomState.java` |
| **Cách tính điểm (`scoring_rule`):**<br>- `fixed`<br>- `speed_based`<br>- `streak_bonus` (chỉ có ở PO V2.1 §5.4) | Chỉ có `fixed` (100đ, `FormulaScoreCalculator.binaryChoice()`) **đã implement**. `speed_based` và `streak_bonus` **chưa có field/logic nào** — `streak_bonus` còn là 1 mâu thuẫn ngay trong tài liệu PO (mô tả ở §5.4 nhưng không có trong bảng schema chính thức §7 của V2.1) — xem `plan.md` Task 27. | `modules/uni-game-engine/.../scoring/FormulaScoreCalculator.java` |
| **Gõ nháp chung nhóm (`UPDATE_DRAFT`):** | Event `UPDATE_DRAFT` (`team_id`, `student_id`, `draft_content`, `client_timestamp_ms` — trường cuối chỉ mang tính telemetry, **không** dùng cho tính điểm/logic nào, đúng quy tắc server-authoritative). `RoomState.updateDraft()` tra `team_id` của người gửi từ **roster phía server** (không tin `team_id` client gửi lên), broadcast tới **toàn bộ thành viên còn lại đã join trong cùng đội** (tổng quát theo cỡ đội thật, không cứng số 3 như bản nháp kỹ thuật ban đầu). | `modules/uni-protocol/.../uni/realtime/v1/client_events.proto`<br>`modules/uni-game-engine/.../room/{RoomState,RoomActor}.java` |

---

## 3. Protocol Schema Changes

> **Cập nhật 2026-09-09 (Task 20, ngoài phạm vi Phase 2 riêng — đổi chung với Phase 1's `room_id % N`
> removal work):** schema không còn nằm trong 1 file `game_message.proto` duy nhất với package
> `com.uni.realtime.protocol` — đó là tên **Java package** (`option java_package`), không phải
> proto package. Proto package thật là `uni.realtime.v1`, tách thành 5 file dưới
> `modules/uni-protocol/src/main/proto/uni/realtime/v1/`: `common.proto` (enum dùng chung +
> `TeamAssignment`/`ProgressMeterSnapshot`/`SharedResourceState`), `internal.proto`,
> `client_events.proto` (`DraftUpdate` ở đây), `server_events.proto` (`RoomStateSnapshot`/
> `GameOver` ở đây), `game_message.proto` (chỉ còn `GameMessage`/`Resync`).

```protobuf
// modules/uni-protocol/.../uni/realtime/v1/common.proto
syntax = "proto3";
package uni.realtime.v1;
option java_package = "com.uni.realtime.protocol";

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
  int32 team_score = 4;      // tính động mỗi flush (Task 22), không phải input tĩnh
}

message ProgressMeterSnapshot {
  int32 current_progress = 1;
  int32 target_progress = 2;
  int32 progress_percentage = 3;
  int32 stage_index = 4;
}

message SharedResourceState {
  string resource_type = 1;
  int32 remaining_lives = 2;  // luôn 0 hiện tại — type=LIVES chưa implement, xem §2
}
```

```protobuf
// modules/uni-protocol/.../uni/realtime/v1/client_events.proto
message DraftUpdate {
  string team_id = 1;
  string student_id = 2;
  string draft_content = 3;
  int64 client_timestamp_ms = 4;  // telemetry only -- KHÔNG dùng cho tính điểm/logic nào
}
```

`RoomStateSnapshot` (`server_events.proto`) là nơi 3 message trên thực sự được nhúng vào — không
phải oneof payload riêng, đi theo đúng cơ chế tick-coalescing (ADR-4) đã có từ Phase 1:

```protobuf
message RoomStateSnapshot {
  bool full = 1;
  GamePhase phase = 2;
  repeated PlayerState players = 3;
  string current_question_id = 4;
  int64 server_question_started_at_ms = 5;
  int64 deadline_ms = 6;
  uint64 broadcast_seq = 7;
  GameMode game_mode = 8;
  repeated TeamAssignment teams = 9;
  ProgressMeterSnapshot progress = 10;
  SharedResourceState shared_resource = 11;
}
```

---

## 4. Architecture & Hot Path Guarantees

1. **Non-blocking Hot Path:** Mọi logic tính điểm, kiểm tra win condition và broadcast nháp đều diễn ra đơn luồng trong `RoomActor` RAM state.
2. **Async Kafka Event Publishing:** Lời gọi Kafka Producer thiết lập `max.block.ms = 0` đẩy event sang `game.events.v1` ở thread riêng, bảo đảm p99 < 100ms cho WebSocket client.
3. **Scoped Fan-out:** `DraftUpdate` được định tuyến scoped theo `team_id` trên Gateway / Engine, không broadcast toàn phòng.
