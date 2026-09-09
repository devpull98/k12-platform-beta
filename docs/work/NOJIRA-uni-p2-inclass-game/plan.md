# Plan: Uni Realtime Giai đoạn 2 — Game Nhóm & Tập Thể Inclass (In-class Group & Cooperative Games)

<!--
Lưu tại:      docs/work/NOJIRA-uni-p2-inclass-game/plan.md
Product Brief: docs/specs/modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games.md
Yêu cầu PO:   docs/specs/modules/engine/PO_Require_Game+nhóm_+tập+thể+Inclass.doc
Kiến trúc:   docs/architecture/system-architecture.md (§10)
-->

---
uc_id: NOJIRA-uni-p2
track: feature
size: L
parallel_safe: true
---

## 1. Overview & Risk Assessment

Giai đoạn 2 bổ sung các chế độ chơi tương tác nhóm và tập thể trong lớp học (`PO_Require_Game+nhóm_+tập+thể+Inclass.doc`):
1. **Chế độ Tập thể (`cooperative`):** 12 học sinh cùng nhau đóng góp đáp án đúng để hoàn thành thanh tiến trình chung (`progress_meter`), hạ gục Boss (ví dụ: Boss Rồng Số Học).
2. **Chế độ Chia nhóm (`team`):** Chia phòng 12 học sinh thành 2–4 nhóm thi đấu tốc độ (`first_to_finish`) hoặc tổng điểm (`sum_all`), có hỗ trợ đồng bộ bản nháp gõ chung (`UPDATE_DRAFT`).
3. **Chế độ Cá nhân Mở rộng (`individual`):** Mở rộng tính năng cá nhân kèm theo các mốc tiến trình và tài nguyên dùng chung (`shared_resource`: thời gian/mạng).
4. **Tích hợp `lms-worker`:** Đẩy sự kiện kết quả thi đấu, thảo luận nhóm, bầu chọn tên nhóm qua Kafka `game.events.v1` để hệ thống cũ ghi nhận cúp và thành tích.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **High Traffic Draft Sync (`UPDATE_DRAFT`) gây sập socket** | Med | **High** | Ép client debounce 150ms. Engine chỉ broadcast draft cho các thành viên trong *cùng nhóm*, không broadcast toàn phòng |
| **Race Condition khi 2 HS cùng Submit làm vượt mốc 100% Progress** | Med | Med | `RoomActor` xử lý đơn luồng sequential. Dùng `AtomicInteger` / State check để đảm bảo chỉ trigger `GameOver` đúng 1 lần |
| **Kafka lag làm block Engine thread ở chế độ Team** | Low | **High** | Đã xử lý ở Phase 1: `max.block.ms=0`, đẩy event qua worker thread riêng biệt |
| **Lệch điểm giữa `lms-worker` và Game Engine** | Low | Med | Game Engine là **Authoritative Source**. Event gửi sang Kafka chứa kết quả cuối cùng đã verified bởi Server |

---

## 2. Task List

### Task 20: Mở rộng Schema Protobuf (`game_message.proto`) — ✅ XONG (2026-09-09)
- **Mô tả:** Thêm enum `GameMode` (`SOLO`, `COOPERATIVE`, `TEAM`, `INDIVIDUAL`), các message payload cho `TeamAssignment`, `DraftUpdate`, `ProgressMeterSnapshot`, `SharedResourceState`.
- **File:** `modules/uni-protocol/src/main/proto/game_message.proto`
- **Acceptance Criteria:**
  - [x] Support payload `TeamAssignment` chứa `team_id`, `student_ids` (field đổi tên từ `member_student_ids` theo đúng schema block trong tech-design.md §3 — đây là tài liệu chính xác hơn, `plan.md` gốc chỉ mô tả sơ)
  - [x] Support `DraftUpdate` mang `draft_content` và `team_id` — wired vào `GameMessage.oneof payload` (field 24), tái dùng `MessageType.UPDATE_DRAFT` đã có sẵn từ Task 7 (P1)
  - [x] Support `ProgressMeterSnapshot` mang `current_progress`, `target_progress`, `stage_index` (+ `progress_percentage`)
- **Quyết định thiết kế:** `TeamAssignment`/`ProgressMeterSnapshot`/`SharedResourceState` là state FRAGMENT nhúng vào `RoomStateSnapshot` (field 8-11: `game_mode`, `teams`, `progress`, `shared_resource`), không phải oneof payload riêng — đi theo đúng cơ chế coalesce (ADR-4) thay vì bypass như `AnswerAck`/`GameOver`. Chỉ `DraftUpdate` là oneof payload thật vì nó là event 2 chiều riêng biệt.
- **Verification:** `GameMessageRoundTripTest` (7/7, thêm 2 test: `draftUpdateRoundTrips`, `roomStateSnapshotCarriesPhase2Fields`).

### Task 21: Nâng cấp `GameDefinition` & `RoomState` cho Cooperative Mode — ✅ XONG (2026-09-09)
- **Mô tả:** Cấu hình `progress_target`, `progress_stages` trong `GameDefinition`. `RoomState` theo dõi tổng điểm/câu đúng cả phòng.
- **File:** `modules/uni-game-engine/.../definition/GameDefinition.java`, `modules/uni-game-engine/.../definition/DefinitionLoader.java`, `modules/uni-game-engine/.../room/RoomState.java`
- **Acceptance Criteria:**
  - [x] Tính toán `% = (tổng_câu_đúng / progress_target) * 100` khi có `SubmitAnswer` hợp lệ — floor, không round (§5.6 luật biên 6, có test riêng `should_floorThePercentage_ratherThanRound`)
  - [x] Tự động chuyển `stage_index` visual khi đạt mốc % tương ứng
- **Thêm ngoài 2 AC gốc (cần thiết để BDD cooperative-boss.feature pass hết):**
  - `applyCooperativeOutcome`: FSM tự chuyển `FINISHED` khi progress chạm `progress_target` (lát cắt hẹp của Task 23 — chỉ `progress_completed`, KHÔNG phải `first_to_finish`/`most_points_when_time_up` vì cần state theo nhóm chưa tồn tại tới Task 22).
  - `shared_resource type=TIME`: trừ `penalty_seconds` vào `deadline_ms` khi trả lời sai. `type=LIVES` bị `DefinitionLoader` từ chối tại load-time (chưa implement, không có số "lives ban đầu" nào được đặc tả trong PO V2.1 — theo đúng tinh thần fail-fast của `missed_step_policy SKIP/ALLOW_LATE`).
  - `DefinitionLoader` từ chối `game_mode TEAM`/`INDIVIDUAL` tại load-time (chưa có `RoomState` implementation — Task 22).
- **Verification:** `RoomStateCooperativeModeTest` (7/7 case mới) + `DefinitionLoaderTest` (17/17, +7 case) + `RoomStateSnapshotTest` (6/6, không regress). Prove-it: tạm vô hiệu `roomProgress++`, xác nhận đúng 4/7 test Red trước khi trả lại Green. `mvn clean install` toàn reactor: BUILD SUCCESS, 5 protocol + 86 gateway + 134 engine (120 cũ + 14 mới) + 2 e2e.

### Task 22: Triển khai Chế độ Chia Nhóm (`Team` Mode) & Scoped Draft Sync — ✅ XONG (2026-09-09)
- **Mô tả:** Quản lý danh sách đội nhóm trong `RoomState`. Xử lý sự kiện `UPDATE_DRAFT` và broadcast scoped trong nhóm.
- **File:** `modules/uni-game-engine/.../room/RoomActor.java`, `modules/uni-game-engine/.../room/RoomSupervisor.java`, `modules/uni-game-engine/.../room/RoomState.java`, `modules/uni-game-engine/.../definition/GameDefinition.java`, `modules/uni-game-engine/.../definition/DefinitionLoader.java`
- **Acceptance Criteria:**
  - [x] Gửi `UPDATE_DRAFT` chỉ tới các thành viên còn lại trong cùng `team_id` (không cứng "3" — tổng quát cho mọi cỡ đội, chỉ tới người ĐÃ join)
  - [x] Tính điểm dồn nhóm (`sum_all`) hoặc điểm trung bình nhóm (`average`) chuẩn xác — floor cho average, cùng quy tắc làm tròn với progress %
- **Không có `TeamState.java` riêng** (khác gợi ý file gốc): team roster là 1 field tĩnh trong `RoomState` (`List<TeamAssignment> teamRosters`, đọc từ `GameDefinition`, không mutate) — không cần class riêng vì không có state runtime nào khác ngoài "ai thuộc đội nào" (đã có sẵn trong roster) + "điểm đội" (tính động từ `totalScoreByStudent` đã có).
- **Quyết định thiết kế quan trọng — roster TĨNH, không phải round-robin tự động:** `GameDefinition.teamRosters` là `List<TeamAssignment>` (tái dùng thẳng type protobuf Task 20, không tạo type domain trùng lặp — giống cách `GamePhase`/`GameMode` đã được dùng thẳng trong domain code) quyết định TỪ TRƯỚC bởi CMS/giáo viên (`team_assignment: manual`), KHÔNG do Engine tự tính (giống nguyên tắc "Engine không tự bịa gameplay data" mà `steps`/`scoringFormula` đã theo). Cân nhắc round-robin theo thứ tự join trước, nhưng để khớp đúng BDD ("Team A = HS1-3" theo NHÓM 3 người liên tiếp, không xen kẽ) cần biết trước sức chứa/team-size mà không field nào trong schema hiện tại cung cấp — roster tĩnh vừa khớp BDD chính xác, vừa không cần đoán thêm field mới (`max_players`), vừa đúng tinh thần "team_assignment=random" thật ra là việc CMS/upstream làm trước khi đưa vào Engine (§5.6 luật biên 7: "LLM chỉ sinh cấu hình tĩnh trước đó").
- **Bảo mật:** `updateDraft()` LUÔN tra `team_id` của người gửi từ roster phía server, KHÔNG BAO GIỜ tin `team_id` client gửi lên để định tuyến — cùng nguyên tắc "không tin định danh do client khẳng định" mà `room_id` đã áp dụng (§10.6).
- **Zero thay đổi Gateway:** tận dụng đúng cơ chế "student_id khác rỗng → gửi riêng 1 người" mà `EngineResponseRouter` đã có sẵn từ Task 13 — Engine chỉ cần sinh N bản GameMessage (mỗi bản gắn đúng 1 student_id người nhận), Gateway tự route đúng, không cần route "theo nhóm" kiểu mới nào.
- **Proto (Task 20 mở rộng thêm, additive):** `PlayerState.team_id` (field 7), `TeamAssignment.team_score` (field 4, tính động mỗi flush — roster field 1-3 tĩnh).
- **Verification:** `RoomStateTeamModeTest` (8/8 case mới) + `DefinitionLoaderTest` (23/23, +6 case: roster hợp lệ/rỗng/2 đội trở lên/tối đa 4/trùng student_id/progress_target dương). Prove-it: tạm bỏ điều kiện loại sender khỏi danh sách nhận draft, xác nhận đúng 2/8 test Red trước khi trả lại Green. Wire thật `RoomActor.Command.UpdateDraft` + `RoomSupervisor`'s `DRAFT_UPDATE` dispatch case (không chỉ dừng ở `RoomState` đơn lẻ như Task 21) — nhưng `RoomSupervisor.spawnRoom()` vẫn CHƯA có nguồn `GameDefinition` thật để spawn phòng ở chế độ TEAM (gap đã biết từ Task 11: "chưa có định dạng game-definition-authoring nào được chốt" — giống hệt tình trạng cooperative mode ở Task 21, không phải thiếu sót riêng của Task 22). `mvn clean install` toàn reactor: BUILD SUCCESS, 5 protocol + 86 gateway + 148 engine (134 cũ + 14 mới) + 2 e2e, không regress.

### Task 23: Triển khai FSM Win Condition Evaluator & Shared Resource Penalty — ✅ PHẦN LỚN XONG (2026-09-09)
- **Mô tả:** Đánh giá điều kiện thắng (`progress_completed`, `first_to_finish`, `most_points_when_time_up`) và xử lý phạt tài nguyên chung (trừ `time` hoặc `lives`).
- **File:** `modules/uni-game-engine/.../scoring/WinConditionEvaluator.java` (mới) — **không tạo** `PenaltyCalculator.java` riêng, xem lý do bên dưới.
- **Acceptance Criteria:**
  - [x] Khi thỏa mãn `win_condition`, FSM đổi trạng thái `FINISHED` lập tức — cho `progress_completed` (Task 21, gia cố lại qua `WinConditionEvaluator`) và `first_to_finish` (mới). `most_points_when_time_up` chỉ đánh giá được KHI game đã kết thúc (xem "Chưa làm" bên dưới) — WHO thắng đã đúng, WHEN game tự kết thúc theo hết giờ thì chưa.
  - [x] Trả lời sai bị trừ đúng số thời gian cấu hình trong `shared_resource` (type=TIME, đã xong từ Task 21). `type=LIVES` vẫn bị `DefinitionLoader` từ chối — xem "Chưa làm".
- **`WinConditionEvaluator`** (`scoring/`, pure function, không đụng `RoomState`/actor): `progressCompleted()`, `firstToFinish()`, `highestScorers()`/`singleHighestScorer()` (cho `most_points_when_time_up`, trả rỗng khi hoà thay vì chọn bừa 1 người thắng).
- **Không tạo `PenaltyCalculator.java` riêng:** chỉ có ĐÚNG 1 luật phạt đã implement (TIME, 1 dòng `deadlineMs -= penalty*1000`, đã có từ Task 21) — tách thành class riêng lúc này là abstraction chưa có lý do tồn tại (chỉ có 1 implementation). Sẽ tách khi LIVES có logic thật (cần trả lời được "số lives ban đầu là bao nhiêu" — vẫn treo, xem dưới).
- **`GameOver` giờ mới thực sự được gửi** (phát hiện phụ khi làm task này): trước Task 23, `GameOver` **chưa từng có nơi nào gửi** trong toàn bộ codebase (kể cả Phase 1 SOLO) — tồn tại trong schema từ Task 1 nhưng không actor nào build/broadcast nó. Không phải lỗi riêng của Task 21/22, mà là 1 gap Phase 1 cũ. Sửa luôn: `RoomActor.onEndGame` VÀ nhánh tự-kết-thúc mới trong `onSubmitAnswer` (khi FSM tự chuyển FINISHED qua win condition) đều broadcast `GameOver` qua `broadcastTarget` (CRITICAL, giống `STUDENT_KICKED`) rồi dừng actor — trước đây chỉ `EndGame` dừng actor, nhánh tự-kết-thúc để actor sống tiếp (rò rỉ, giống đúng bug "RoomSupervisor không dọn phòng" đã sửa ở review pass P1).
- **Proto thêm (additive):** `GameOver.winner_id` (field 3) — team_id/student_id thắng cuộc, rỗng khi không có 1 người thắng cụ thể (`cooperative`, hoà `most_points_when_time_up`, `SOLO`).
- **`RoomState.endGame()`** không ghi đè `gameOverReason`/`winnerId` nếu game đã FINISHED từ trước qua win condition (chỉ tính điểm `most_points_when_time_up` khi game CHƯA kết thúc theo cách khác) — có test riêng + prove-it xác nhận.
- **Chưa làm (có chủ ý, ghi rõ trong `WinCondition.MOST_POINTS_WHEN_TIME_UP`'s javadoc):**
  - **Trigger tự động cho `most_points_when_time_up`** ("hết giờ thì tự kết thúc") — không có cơ chế timer/deadline nào tự động chuyển FSM ở bất kỳ đâu trong codebase này (giống hệt gap `TeacherCommand.NEXT_STEP` đã ghi từ Task 11: "chưa có định dạng nào được chốt"). Logic ĐÁNH GIÁ ai thắng khi game kết thúc đã đúng (`WinConditionEvaluator.singleHighestScorer` + `RoomState.endGame()`); chỉ thiếu cái TRIGGER tự động — hôm nay chỉ `TeacherCommand.END_GAME` (thủ công) mới kích hoạt được nhánh này.
  - **`shared_resource=LIVES`** vẫn bị `DefinitionLoader` từ chối — PO V2.1 không đặc tả số "lives ban đầu", đoán một con số là bịa quyết định nghiệp vụ (đúng tinh thần G1a/G1c).
- **Verification:** `WinConditionEvaluatorTest` (9/9 case mới, pure logic) + `RoomStateWinConditionTest` (7/7 case mới, cả 3 win condition + không ghi đè reason) + `RoomActorTest` (10/10, +1 case: `GameOver` broadcast thật qua `EndGame`) + `DefinitionLoaderTest` (26/26, +3 case win_condition). Prove-it: tạm bỏ guard "không ghi đè nếu đã FINISHED" trong `endGame()`, xác nhận đúng 1/7 test Red trước khi trả lại Green. `mvn clean install` toàn reactor: BUILD SUCCESS — 5 protocol + 86 gateway + 168 engine (148 cũ + 20 mới) + 2 e2e, không regress.

### Task 24: Tích hợp Kafka Event Publisher với `lms-worker` — ⛔ DỪNG LẠI, chỉ ghi nhận phát hiện (2026-09-09)
- **Mô tả gốc:** Phát `TeamSubmitExerciseEvent`, `GroupDiscussionEvent`, `VoteGroupNameEvent` sang Kafka topic `game.events.v1`.
- **File:** `modules/uni-game-engine/.../events/GameEventPublisher.java`
- **Acceptance Criteria gốc:**
  - [ ] Payload JSON/Protobuf đúng contract mà `SubmitExerciseListener` và `ActiveGroupDiscussionListener` của `lms-worker` mong đợi

- **Đã đọc code thật của `lms-worker`** (người dùng cung cấp đường dẫn cục bộ:
  `D:\Educa\k12-backend-java\k12-lms-service\lms-worker\src\main\java\vn\edupiaclass\lms\worker\listener\event\group_discussion\`)
  — 4 listener (`SubmitExerciseListener`, `ActiveGroupDiscussionListener`, `VoteGroupNameListener`,
  `ChatDiscussionRankListener`) + DTO (`TeamScoreDto`, `GroupMessageDto`, `VoteInput`) +
  `ResultExerciseWorker`. **3 giả định trong mô tả/BDD gốc của Task 24 đều SAI so với hệ thống
  thật:**

  | Giả định trong BDD/Task 24 | Thực tế trong code `lms-worker` |
  |---|---|
  | Topic `game.events.v1` | `SubmitExerciseListener` nghe **`team-submit-exercise-response`**; `VoteGroupNameListener`/`ActiveGroupDiscussionListener`/`ChatDiscussionRankListener` đều nghe **`save-message-queue`** (queue broadcast chung của hệ thống socket cũ, không phải topic riêng cho game event) |
  | "Dạng Protobuf/JSON" | Cả hai đều nhận **String JSON thuần** (`ObjectMapper.readValue(data, TeamScoreDto.class)` / `GroupMessageDto.class`) — không có đường Protobuf nào |
  | `GameEventPublisher` (Task 18) đã sẵn sàng tái dùng | `GameEventPublisher`/`KafkaGameEventSink` hiện tại (Task 18) chỉ gửi **protobuf bytes** (`AnswerAck.toByteArray()`) sang `game.events.v1` — khác hoàn toàn topic + format cần cho lms-worker, cần đường publish JSON riêng (không tái dùng thẳng được) |

- **Gap dữ liệu — lý do chính khiến task này KHÔNG thể code ngay được:** `TeamScoreDto` thật đòi
  `profile_id`, `exercise_id`, `classroom_id`, `session_parent_id` (số nguyên, thuộc domain
  LMS/CMS cũ). `VoteGroupNameListener` đòi thêm `payload.vote.teamNameId` (chọn 1 trong các tên đề
  xuất sẵn) và `groupId` dạng `"{sessionParentId}-{classroomId}"`. **`uni-realtime` hiện không có
  bất kỳ trường nào trong số này** — `JoinTokenClaims` (`modules/uni-websocket-gateway/.../auth/JoinTokenClaims.java`)
  chỉ có `studentId`, `roomId`, `sessionId` (đều là String), không có ID số nguyên LMS nào. Đây
  không phải "thêm 1 Kafka event" đơn thuần — thiếu cả một đường định danh LMS chưa từng chảy vào
  `uni-realtime`, đòi hỏi mở rộng ở tầng join-token/xác thực (ngoài phạm vi module `uni-game-engine`
  mà Task 24 khoanh vùng).
- **Tính năng "vote tên nhóm" chưa tồn tại trong schema uni-realtime:** `VoteGroupNameListener`
  cần chọn giữa NHIỀU tên đội đề xuất sẵn (`teamNameId`); `TeamAssignment.team_name` hiện tại
  (Task 20/22) chỉ là 1 tên cố định/đội, không có cơ chế đề xuất + vote nào. `GroupDiscussionEvent`
  (nhắc trong mô tả gốc) và `ActiveGroupDiscussionListener` cũng không khớp trực tiếp với bất kỳ
  luồng nào Engine hiện có — listener đó được kích hoạt bởi 1 flow "bắt đầu thảo luận nhóm" hoàn
  toàn khác (không phải do `GameOver`).
- **Quyết định (người dùng, 2026-09-09):** dừng Task 24 lại ở bước ghi nhận phát hiện, KHÔNG viết
  code Kafka event nào lúc này — tránh code "trông như tích hợp thật" nhưng thực chất sai topic/
  format/thiếu dữ liệu, sẽ âm thầm không hoạt động khi đấu nối với `lms-worker` thật. Cần quyết
  định rõ nguồn cho `profile_id`/`exercise_id`/`classroom_id`/`session_parent_id` (mở rộng
  `JoinTokenClaims`? một service khác cung cấp?) trước khi mở lại task này.

### Task 25: Unit Tests & RoomActor FSM Tests
- **Mô tả:** Viết Unit Test phủ 100% logic tính tiến trình, phân nhóm, và phạt tài nguyên.
- **File:** `modules/uni-game-engine/src/test/java/.../room/CooperativeRoomActorTest.java`, `TeamRoomActorTest.java`

### Task 26: Xây dựng E2E Cucumber Tests (`uni-e2e`)
- **Mô tả:** Khởi chạy 12 client WebSocket giả lập kiểm thử 3 kịch bản BDD thực chiến.
- **File:** `modules/uni-e2e/src/test/resources/features/inclass_game.feature`

---

## 3. Verification Plan

- `mvn clean test` xanh toàn bộ reactor.
- Chạy Cucumber E2E tests: `mvn -pl :uni-e2e test`.
