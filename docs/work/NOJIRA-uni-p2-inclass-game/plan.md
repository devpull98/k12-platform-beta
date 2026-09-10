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

> **2026-09-09: mục "Tích hợp `lms-worker`" (Task 24) đã bị PO chốt BỎ hẳn** — không còn trong
> phạm vi Phase 2. Tài liệu/BDD/tài liệu tìm hiểu liên quan đã xoá; xem lịch sử git nếu cần tham
> khảo lại phát hiện cũ về contract `lms-worker` thật.

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **High Traffic Draft Sync (`UPDATE_DRAFT`) gây sập socket** | Med | **High** | Ép client debounce 150ms. Engine chỉ broadcast draft cho các thành viên trong *cùng nhóm*, không broadcast toàn phòng |
| **Race Condition khi 2 HS cùng Submit làm vượt mốc 100% Progress** | Med | Med | `RoomActor` xử lý đơn luồng sequential. Dùng `AtomicInteger` / State check để đảm bảo chỉ trigger `GameOver` đúng 1 lần |
| **Kafka lag làm block Engine thread ở chế độ Team** | Low | **High** | Đã xử lý ở Phase 1: `max.block.ms=0`, đẩy event qua worker thread riêng biệt |

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
  - **Cập nhật 2026-09-10 (phiên khác, review code sau refactor DDD + đối chiếu Product Brief V2.1
    — xem `plan.md` Task 27):** gap "trigger tự động" ở trên hoá ra ĐAN XEN với 1 câu hỏi nghiệp vụ
    khác chưa có lời giải — INCLASS-GAME-001-v2.1.md §5.6 luật biên 5 ("GV không được hủy giữa
    ván"). Vì `TeacherCommand.END_GAME` lúc `PLAYING` hiện là cơ chế DUY NHẤT kích hoạt
    `most_points_when_time_up`, thêm 1 guard chặn `EndGame` để tuân luật biên 5 sẽ VÔ HIỆU HOÁ luôn
    con đường duy nhất đang có cho win condition này — 2 gap này thực ra là 1: đều cần PO trả lời
    "hết giờ" nghĩa là gì (hết giờ của 1 câu hỏi? hết giờ toàn ván theo đồng hồ riêng chưa có field
    nào trong schema? hay đơn thuần là quyết định thủ công của GV, và nếu vậy ranh giới "hủy" vs
    "báo hết giờ" là gì). Đã đọc lại `INCLASS-GAME-001-v2.1.md` §3-§5 lần nữa — **không có thông tin
    mới nào** trả lời được câu hỏi này (không có field "tổng thời gian ván", không có định nghĩa
    "hết giờ" cho `most_points_when_time_up` ngoài "GV bấm Kết thúc" ở §6.3). Cả 2 mục "chưa làm"
    trong Task 23 (trigger tự động + `LIVES`) và mục "chưa sửa" của Task 27 (luật biên 5) **cùng
    chờ 1 câu trả lời PO duy nhất** — không tách 3 task riêng cho 3 câu hỏi con của cùng 1 gap gốc.
    Không tự đoán, không tự code thêm — trạng thái Task 23 giữ nguyên "PHẦN LỚN XONG", không đổi
    thành "XONG" cho tới khi có câu trả lời đó.
- **Verification:** `WinConditionEvaluatorTest` (9/9 case mới, pure logic) + `RoomStateWinConditionTest` (7/7 case mới, cả 3 win condition + không ghi đè reason) + `RoomActorTest` (10/10, +1 case: `GameOver` broadcast thật qua `EndGame`) + `DefinitionLoaderTest` (26/26, +3 case win_condition). Prove-it: tạm bỏ guard "không ghi đè nếu đã FINISHED" trong `endGame()`, xác nhận đúng 1/7 test Red trước khi trả lại Green. `mvn clean install` toàn reactor: BUILD SUCCESS — 5 protocol + 86 gateway + 168 engine (148 cũ + 20 mới) + 2 e2e, không regress.

### Task 24: Tích hợp Kafka Event Publisher với `lms-worker` — ❌ HỦY (2026-09-09, quyết định PO)
- Task này từng dừng ở bước ghi nhận phát hiện (contract `lms-worker` thật khác BDD/mô tả gốc —
  sai topic, sai format, thiếu ID định danh LMS). PO sau đó chốt **bỏ hẳn** tích hợp `lms-worker`
  khỏi phạm vi Phase 2 — không chỉ hoãn. Tài liệu chi tiết phát hiện cũ + BDD feature liên quan đã
  xoá khỏi repo theo yêu cầu; xem lịch sử git (`git log -- docs/specs/bdd/INCLASS-GAME-003-lms-worker-sync.feature`)
  nếu cần tra lại.

### Task 25: Unit Tests & RoomActor FSM Tests — ✅ XONG (2026-09-09)
- **Mô tả:** Viết Unit Test phủ 100% logic tính tiến trình, phân nhóm, và phạt tài nguyên.
- **File:** `modules/uni-game-engine/src/test/java/.../room/CooperativeRoomActorTest.java`, `TeamRoomActorTest.java` (cả 2 tạo mới đúng tên plan.md gợi ý).
- **Phần lớn coverage đã có sẵn từ Task 21-23** (TDD trong lúc code, không phải task riêng biệt sau này) — Task 25 lấp 2 khoảng trống còn lại:
  1. **Actor-level FSM test** (khác `RoomState`-level đơn lẻ đã có): `CooperativeRoomActorTest`/`TeamRoomActorTest` dùng `BehaviorTestKit`, xác nhận qua `RoomActor` thật — auto-`FINISHED` → broadcast `GameOver` → actor tự dừng; `JoinRoom` reply mang đúng `team_id`/roster; `UpdateDraft` fan-out scoped qua actor thật (không chỉ `RoomState.updateDraft()` trả về list đúng).
  2. **Overload mới cho `RoomActor.create()`** (P2 Task 25's own master constructor) — mang toàn bộ config cooperative/team (`gameMode`, `progressTarget`, `progressStages`, `sharedResourceType`, `sharedResourcePenalty`, `teamRosters`, `scoreAggregation`, `winCondition`) vào actor, additive-overload đúng chuỗi đã có (Task 14/17/18). **`RoomSupervisor.spawnRoom()` vẫn KHÔNG gọi overload này** — vẫn thiếu nguồn `GameDefinition` thật (gap Task 11), nên overload này hiện chỉ phục vụ test, giống hệt vị trí `missedStepPolicy` từng ở giữa Task 14 và Task 17.
- Thêm 1 test biên: `RoomStateTeamModeTest.should_notAdvanceAnyTeamProgress_when_correctAnswerComesFromAStudentNotOnAnyRoster` (nhánh phòng thủ `applyTeamOutcome`'s `teamId.isEmpty()`, khác với nhánh tương tự đã test ở `updateDraft`).
- **Verification:** `CooperativeRoomActorTest` (3/3, mới) + `TeamRoomActorTest` (3/3, mới) + `RoomStateTeamModeTest` (9/9, +1). Prove-it thật (không phải test giả): 1 test ban đầu Red vì lý do đúng (behavior tự nhiên của `RoomActor` — flush ngay lập tức khi `now - lastFlushAtMs >= 200ms`, không phải bug), sửa assertion để lọc đúng loại message thay vì đếm tổng. `mvn clean install` toàn reactor: BUILD SUCCESS — 5 protocol + 86 gateway + 175 engine (168 cũ + 7 mới) + 2 e2e, không regress.

### Task 26: Xây dựng E2E Tests (`uni-e2e`) — ✅ XONG (2026-09-09, JUnit5 thay vì Cucumber)
- **Mô tả gốc:** Khởi chạy 12 client WebSocket giả lập kiểm thử 3 kịch bản BDD thực chiến.
- **File gốc đề xuất:** `modules/uni-e2e/src/test/resources/features/inclass_game.feature` — **không tạo**, xem quyết định bên dưới.
- **Quyết định (người dùng, 2026-09-09):** repo này **không có Cucumber ở bất kỳ đâu** (đã kiểm tra trước khi bắt đầu — toàn bộ `uni-e2e` từ trước tới giờ là JUnit5 thuần: `WalkingSkeletonTest`, `DockerComposeChaosIT`...). Viết theo đúng convention JUnit5 hiện có thay vì thêm Cucumber mới.
- **File thật:** `modules/uni-e2e/src/test/java/com/uni/realtime/e2e/InclassGroupGameE2ETest.java` — 2 test, 12 client WebSocket **thật** (không `EmbeddedChannel`), đi qua Gateway/Engine thật, giống hệt convention `WalkingSkeletonTest` (socket thật, `FakeJoinTokenVerifier` đứng thay G1a/G1c, nội dung câu hỏi qua `RoomSupervisor.GetRoomActor`).
- **Gap chặn E2E thật cho COOPERATIVE/TEAM đã đóng bằng 1 hook mới:** `RoomSupervisor.SpawnConfiguredRoom` — cùng tinh thần test/ops-only với `GetRoomActor` đã có — spawn phòng với config cooperative/team TRƯỚC khi client nào join, vì `RoomSupervisor.spawnRoom()` (đường join thật) vẫn chỉ spawn được `SOLO` (chưa có nguồn `GameDefinition` thật, gap Task 11). `RoomActor.create()` thêm 1 overload master constructor mới mang toàn bộ config (đã thêm từ Task 25, tái dùng lại ở đây).
- **2 kịch bản BDD được cover thật (không phải giả định):**
  1. `INCLASS-GAME-001-cooperative-boss.feature`: 12 học sinh join thật, 3 vòng câu hỏi (3+4+3 học sinh trả lời đúng), xác nhận `progress_percentage`/`stage_index` đúng qua từng mốc (30%/70%/100%), `GameOver` (`reason=progress_completed`, `winner_id` rỗng) tới CẢ học sinh chưa từng trả lời câu nào (chứng minh broadcast toàn phòng, không chỉ người nộp bài).
  2. `INCLASS-GAME-002-team-speed-race.feature`: `UPDATE_DRAFT` chỉ tới đồng đội (Team B không bao giờ nhận được draft của Team A — assert bằng `assertNoMoreMessagesFor`), `first_to_finish` khi Team A đạt `progress_target` trước, `GameOver.winner_id="A"` tới TOÀN BỘ 4 đội (không chỉ đội thắng).
- Kịch bản BDD thứ 3 (`lms-worker` sync) không còn tồn tại — Task 24 đã bị PO hủy hẳn khỏi phạm vi (xem ghi chú ở đầu file).
- **Bài học từ debug (đáng ghi lại):** lần chạy đầu tiên của kịch bản team bị Red không xác định (không phải mỗi lần) — điều tra bằng debug print xác nhận cả 2 `ANSWER_ACK` đều `accepted=true`, nghĩa là logic Engine đúng 100%; nguyên nhân là **race điều kiện thời gian trong chính test** (gửi câu trả lời thứ 2 ngay sau câu 1 mà không đợi ACK, đôi khi khiến việc chờ `GameOver` timeout trước khi message kịp tới). Sửa bằng cách đợi `ANSWER_ACK` của MỖI lần nộp bài trước khi tiếp tục — chạy lại 3/3 lần liên tiếp đều xanh sau khi sửa.
- **Verification:** `mvn clean install` toàn reactor: BUILD SUCCESS — 5 protocol + 86 gateway + 175 engine + **4 e2e** (2 cũ + 2 mới). Chạy riêng `InclassGroupGameE2ETest` 3 lần liên tiếp: 3/3 xanh, không flaky.

### Task 27: Đối chiếu tương thích với Product Brief V2.1 & dọn dead code — ⚠️ MỘT PHẦN XONG (2026-09-09)
- **Mô tả:** Sau khi refactor kiến trúc lớn (DDD domain separation, tách `RoomState` thành `RoomStateProtobufMapper`/`RoomStateSerializer`), đối chiếu lại toàn bộ code hiện tại với `INCLASS-GAME-001-inclass-group-cooperative-games-v2.1.md` §3.1/§3.2/§4/§5/§6, phát sinh từ 1 phiên code-review.
- **Đã fix (2026-09-09):**
  - [x] **`RoomStateProtobufMapper.buildFullSnapshot(RoomState)` + `computeStageIndex()` là dead code, đã xoá.** Điều tra ban đầu (từ review) tưởng đây là 1 bug "tính % không nhất quán giữa full/delta snapshot" (`Math.round` thay vì `Math.floor`, khác luật §5.6 rule 6 và khác `RoomState.buildProgressMeterSnapshot()` đang dùng đúng `Math.floor`, có test `should_floorThePercentage_ratherThanRound`) — nhưng grep xác nhận **0 nơi gọi `RoomStateProtobufMapper.buildFullSnapshot()`**: `RoomState.flush()` (đường hot path thật) dùng đúng method riêng của nó (`RoomState.buildFullSnapshot()`, private, đã floor đúng), không hề đụng tới bản trong mapper. Đây là code thừa sót lại từ đợt tách file DDD (chỉ tách được `buildAck`/`buildGameOver`/`buildPlayerState` hoàn chỉnh, còn `buildFullSnapshot` bị nhân đôi dở dang và không ai xoá bản cũ hay wire bản mới) — không phải bug production thật, nhưng là rủi ro bảo trì thật (2 bản logic có thể lệch nhau ngầm, không test nào phát hiện vì không ai gọi). Xoá `buildFullSnapshot`/`computeStageIndex` + import thừa (`ProgressStage`, `SharedResourceType`, `ProgressMeterSnapshot`, `RoomStateSnapshot`, `SharedResourceState`, `List`, `Map`) khỏi `RoomStateProtobufMapper.java`, giữ nguyên `buildPlayerState`/`buildAck`/`buildGameOver` (đang được dùng thật). `mvn -pl :uni-game-engine test`: 175/175 pass, không regress.
- **KHÔNG fix — cố ý, cần quyết định PO (giống tinh thần G1a/G1c/LIVES):**
  - [ ] **§5.6 luật biên 5 ("GV không được hủy giữa ván") — KHÔNG phải bug 1-dòng như đánh giá ban đầu của review.** Đọc lại `plan.md` Task 23 xác nhận: `TeacherCommand.END_GAME` gọi được trong `PLAYING` **là cơ chế trigger DUY NHẤT hiện có** cho `MOST_POINTS_WHEN_TIME_UP` (chưa có timer/deadline tự động — gap đã ghi từ Task 23). Test `RoomStateWinConditionTest.should_computeHighestScoringTeam_whenEndGameFiresUnderMostPointsWhenTimeUp` gọi `room.endGame()` giữa ván, TRƯỚC `progress_target`, KHÔNG đợi `deadlineMs` trôi qua (`Clock.fixed`, không advance) — 1 guard chặn `EndGame` lúc `phase==PLAYING` (dù đơn giản hay dựa vào `deadlineMs`) sẽ phá chính hành vi đã test kỹ này. Vấn đề thật: schema/PO chưa phân biệt "hủy nửa chừng" (bị cấm) và "GV/hệ thống báo hết giờ để chấm" (bắt buộc phải cho phép, vì auto-trigger chưa tồn tại) — đây là 1 câu hỏi nghiệp vụ chưa có câu trả lời, không phải chỗ thiếu code. Không tự đoán ranh giới (đúng nguyên tắc đã áp dụng cho G1a/G1c và `shared_resource=LIVES`).
- **Chưa làm — backlog, cần task/plan riêng (không phải sửa nhỏ):**
  - [ ] §3.1 Group A schema còn thiếu hoàn toàn: `max_players`, `team_count`, `team_assignment` (random/manual), `late_join_policy`, `scoring_rule=speed_based`; thiếu 1 phần: `team_names/colors` (chưa có màu), `score_aggregation` (chỉ có `SUM_ALL`/`AVERAGE`, thiếu `first_correct_only`/`majority_vote`).
  - [ ] §3.2 `progress_display_mode` (`simple_bar`/`staged_visual`) chưa có field — engine luôn gửi stage index, không có cờ bật/tắt hiển thị theo kiểu client cần.
  - Đây là việc CMS/form/Group A authoring thật (§6 hạ tầng), không thuộc `uni-game-engine` — cần Product Brief/task riêng, không tự mở rộng `GameDefinition` mà không biết CMS sẽ gửi field nào thật.
- **Verification:** `mvn -pl :uni-game-engine test`: 175/175 pass (dead-code cleanup only, không đổi hành vi runtime).

---

## 3. Verification Plan

- `mvn clean test` xanh toàn bộ reactor.
- E2E: `mvn -pl :uni-e2e test` (JUnit5 thật, không phải Cucumber — xem Task 26).
