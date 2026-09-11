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
- **Cập nhật 2026-09-10 (đối chiếu trực tiếp 2 file `.doc` gốc, không phải bảng tóm tắt trong
  `.md`):** decode MIME/quoted-printable 2 file `PO_Require_Game+nhóm_+tập+thể+Inclass.doc` (V1.0)
  và `...V2.1.doc` rồi diff trực tiếp — phát hiện bảng so sánh V1.0 vs V2.1 trong
  `INCLASS-GAME-001-inclass-group-cooperative-games-v2.1.md` **sai 4/6 dòng** (đã sửa lại file đó
  cùng ngày, xem note trong chính file). 2 gap MỚI phát hiện qua diff, chưa từng ghi ở đâu trong
  repo trước đây:
  - [ ] **`streak_bonus` mâu thuẫn ngay trong tài liệu PO V2.1:** §5.4 (Cách tính điểm) mô tả
    `streak_bonus` (đúng liên tiếp N câu → +% thưởng, reset khi sai 1 câu) như 1 giá trị của
    `scoring_rule`, nhưng bảng schema chính thức §7 (Input Schema) **vẫn chỉ liệt kê
    `fixed`/`speed_based`** — PO tự mâu thuẫn giữa 2 mục trong cùng 1 tài liệu. Thêm vào backlog
    schema Group A ở trên (`GameDefinition`/`FormulaScoreCalculator` chưa có `streak_bonus`) —
    cần PO xác nhận đây có phải scope thật hay chỉ là ví dụ minh hoạ chưa chốt.
  - [ ] **Luật "Hoà" khi `first_to_finish`/`most_points_when_time_up` — PO TỰ FLAG là chưa chốt**
    (§5.3 V2.1, nguyên văn: "2 phe chạm đích cùng thời điểm server, hoặc bằng điểm khi hết giờ: cần
    luật phụ... ví dụ ai có nhiều câu trả lời đúng nhanh hơn thắng, hoặc hiển thị Hoà"). Khác các
    gap khác (LIVES, luật biên 5) — mục này KHÔNG cần đoán ý PO, vì chính PO đã ghi rõ đây là điểm
    treo. Hiện tại `WinConditionEvaluator.singleHighestScorer()` trả rỗng khi hoà (đúng, không tự
    bịa 1 người thắng) — hành vi này ĐÃ ĐÚNG tinh thần "không tự quyết", chỉ cần biết đây là do
    PO chưa trả lời, không phải thiếu sót code.
  - Không có gap MỚI nào khác về mặt input schema — diff xác nhận §7 giống hệt 100% giữa V1.0/V2.1,
    nên danh sách backlog schema Group A ở trên (từ review 2026-09-09) vẫn đầy đủ và chính xác.
- **Verification:** `mvn -pl :uni-game-engine test`: 175/175 pass (dead-code cleanup only, không đổi hành vi runtime).

### Task 28: Đối chiếu PO Spec V2.2 — gap FSM `RULES_DISPLAY` + tự động bắn câu hỏi 1 — ✅ XONG PHẦN LÕI ENGINE (2026-09-11, verify thật), CHƯA WIRE VÀO ĐƯỜNG JOIN THẬT
- **Nguồn gốc:** PO gửi `INCLASS-GAME-001-inclass-group-cooperative-games-v2.2.md` +
  `PO_Require_Game+nhóm_+tập+thể+Inclass V2.2.doc` ngày 2026-09-11 (2 file này mới chỉ `git add`,
  chưa từng được đối chiếu với V2.1 hay với code trước phiên này). Phát hiện dưới đây là kết quả
  đối chiếu trực tiếp §4 "Luồng Vận Hành Server FSM" (Mục 5.2 PO V2.2) của file `.md` đó với code
  `RoomActor`/`RoomState`/`RoomSupervisor` thật — không phải suy đoán.
- **PO V2.2 yêu cầu (nguyên văn rút gọn từ §4):**
  ```
  [WAITING_FOR_PLAYERS] → [RULES_DISPLAY] (MỚI ở V2.2) → [IN_PROGRESS] (START Tự Động)
  ```
  "Engine tự động chuyển IN_PROGRESS & phát câu hỏi 1 (không chờ GV bấm thêm)." Bảng so sánh
  V2.1-vs-V2.2 trong cùng file xác nhận đây là thay đổi CHỦ Ý, không phải câu chữ mơ hồ: V2.1 chỉ
  ghi "Bắt đầu game do GV bấm" (im lặng về việc có tự bắn câu hỏi 1 hay không — đây chính là gốc
  gap "Task 11" đã ghi nhiều lần trong file này); V2.2 chốt rõ **GV chỉ bấm "Bắt đầu game" đúng 1
  lần**, mọi thứ sau đó (hiển thị luật chơi → bắn câu hỏi 1) là Engine tự làm.
- **Đối chiếu với code thật — 3 lỗ hổng cụ thể, không phải 1:**
  1. **FSM hiện tại không có state `RULES_DISPLAY`.** `RoomState`/`RoomActor` chỉ có
     `LOBBY → PLAYING → FINISHED` (khác tên gọi PO dùng `WAITING_FOR_PLAYERS`/`IN_PROGRESS`/`ENDED`
     — cần map đúng khi implement, đừng nhầm 2 bộ tên là 2 FSM khác nhau).
  2. **`TeacherCommand.START_GAME` chỉ đổi `phase` (`RoomState.startGame()` — 1 dòng, không làm gì
     khác), không tự bắn câu hỏi nào.** Xác nhận qua `RoomSupervisor.dispatchTeacherCommand()`
     (dòng ~186): `START_GAME` gọi đúng `RoomActor.StartGame`, hết.
  3. **`TeacherCommand.NEXT_STEP` (lệnh đáng lẽ dùng để chuyển câu) hoàn toàn CHƯA nối dây** — rơi
     vào nhánh `default -> log.warn("TeacherCommand.{} not wired for room {} yet")` cùng hàm trên
     (dòng ~189). Gửi lệnh này từ client thật hiện tại không có tác dụng gì.
  4. **`RoomActor.StartQuestion`** (command actor thật sự build+broadcast `QUESTION_STARTED`) **chỉ
     được gọi từ test code** — grep xác nhận xuất hiện ở `RoomActorTest`, `TickCoalescingTest`,
     `RoomActorSnapshotTest`, `RoomActorGameEventTest`, `RoomSupervisorTest`,
     `CooperativeRoomActorTest`, `TeamRoomActorTest`, và 3 file `uni-e2e` (qua hook test-only
     `GetRoomActor`/`SpawnConfiguredRoom`) — **0 nơi gọi nó từ đường dispatch production thật**.
- **Không phải bug mới — là tiếp nối gap Task 11 (P1)/Task 23/27 (P2) đã ghi nhiều lần** ("chưa có
  định dạng authoring câu hỏi nào được chốt", "chưa có cơ chế timer/deadline tự động chuyển FSM") —
  nhưng đây là lần ĐẦU TIÊN có 1 câu trả lời PO chính thức, rõ ràng cho ĐÚNG 1 phần của gap đó (bắn
  câu hỏi 1 lúc bắt đầu game là tự động), thay vì PO im lặng như V2.1.
- **Câu hỏi PO CHƯA trả lời (không tự đoán, đúng tinh thần G1a/G1c/LIVES):** PO V2.2 §4 chỉ nói rõ
  câu hỏi 1 là tự động. Từ câu 2 trở đi, đoạn "Đếm ngược `round_time_limit` từng câu... Kiểm tra
  `win_condition` hoặc hết câu hỏi -> chuyển `ENDED`" **không nói rõ** việc chuyển sang câu kế tiếp
  là Engine tự làm theo `round_time_limit` hết hạn, hay vẫn cần GV bấm `NEXT_STEP` thủ công cho
  từng câu sau câu 1. Cần hỏi PO xác nhận trước khi code — không suy đoán.
- **Phạm vi ban đầu (đối chiếu lại sau khi code xong bên dưới):**
  1. [x] Thêm state `RULES_DISPLAY` vào FSM `RoomState`/`RoomActor` — XONG.
  2. [x] Nối `TeacherCommand.START_GAME` → tự động chuyển `RULES_DISPLAY` → `IN_PROGRESS` → tự gọi
     `RoomActor.StartQuestion` cho câu hỏi đầu tiên — XONG (qua `RoomState.startGame()` thật, không
     qua `TeacherCommand`/`RoomSupervisor` vì đường đó vẫn dùng đúng `RoomActor.StartGame` sẵn có).
  3. [ ] Gap CHƯA đóng từ Task 11/21, KHÔNG thuộc phạm vi Task 28: `RoomSupervisor.spawnRoom()`
     (đường join thật) vẫn không có nguồn `GameDefinition` thật để lấy danh sách câu hỏi — xem mục
     "Việc còn lại" cuối task này.
  4. [ ] Vẫn chờ câu trả lời PO cho "câu 2 trở đi" — `TeacherCommand.NEXT_STEP` vẫn CHƯA nối dây
     (không đổi trong task này, không tự đoán).
- **Phụ thuộc:** Task 29 (bên dưới) — Engine cần `questions` tồn tại trong `GameDefinition` trước
  khi có nội dung thật để tự bắn câu hỏi 1. **Task 29 đã XONG trước, đúng thứ tự dự tính** — gap
  này mở ra nhưng phát sinh thêm 1 câu hỏi thiết kế mới khi bắt đầu code thật (xem ngay dưới đây).
- **Câu hỏi thiết kế MỚI phát hiện (2026-09-11, lúc bắt đầu code) — cần quyết định trước khi làm
  tiếp, không tự đoán:** PO V2.2 §4 không nói `RULES_DISPLAY` tồn tại bao lâu hay điều gì kích hoạt
  việc rời khỏi state đó. Đối chiếu kỹ thuật: nếu server chuyển `RULES_DISPLAY → PLAYING` NGAY
  TRONG CÙNG 1 lần xử lý lệnh `START_GAME` (không có gì chờ ở giữa), thì `RULES_DISPLAY` không bao
  giờ thực sự được broadcast riêng cho client thấy — vì cơ chế flush hiện tại chỉ gửi state SAU KHI
  actor xử lý xong lệnh, lúc đó `phase` đã là `PLAYING` mất rồi. Để `RULES_DISPLAY` có ý nghĩa thật
  (client thấy được màn hình luật chơi), Engine cần: **hoặc (a)** giữ ở `RULES_DISPLAY` một khoảng
  thời gian cố định trước khi tự chuyển tiếp — nhưng cần 1 con số giây thật (PO không cho), và đây
  sẽ là **timer tự động ĐẦU TIÊN trong toàn bộ codebase này** (đã ghi nhận nhiều lần trước đây:
  "chưa có cơ chế timer/deadline nào tự động chuyển FSM ở bất kỳ đâu") — xây timer mới chỉ cho
  riêng bước này, trong khi câu hỏi tương tự cho "tự động chuyển câu 2 trở đi" (mục trên) vẫn đang
  treo, có thể tạo ra 2 cơ chế timer không nhất quán nếu làm vội; **hoặc (b)** coi `RULES_DISPLAY`
  chỉ là 1 giá trị enum mang tính hình thức, broadcast xong chuyển ngay (không giữ), chấp nhận
  client gần như không kịp thấy nó trên thực tế (không đúng tinh thần "màn hình luật chơi" PO mô
  tả, nhưng không cần xây timer mới, không cần bịa con số giây). Chưa chọn phương án — hỏi người
  dùng trước khi code tiếp phần này của Task 28.
- **Quyết định người dùng (2026-09-11):** chọn phương án (b) — `RULES_DISPLAY` là giá trị hình
  thức, không giữ, không xây timer mới, không bịa số giây. Chấp nhận client gần như không kịp thấy
  state này trên thực tế cho tới khi có quyết định khác.
- **Đã code thật:**
  - `common.proto`: thêm `RULES_DISPLAY = 5;` vào `GamePhase` (additive) — đã regenerate thật.
  - `GameDefinition`/`RoomState` thêm field `questions`/`introNarrative`/`roundTimeLimitSeconds`
    (Task 29 định nghĩa, Task 28 mới thật sự DÙNG) — thread qua constructor nhận `GameDefinition`
    additive, KHÔNG đổi chữ ký constructor cũ nào (đã grep xác nhận 2 call site thật trong
    `RoomState.java`/`RoomActor.java` vẫn nguyên).
  - `RoomState.startGame()`: nếu `questions` rỗng (MỌI phòng dựng trước Task 29, và vẫn là đường
    DUY NHẤT `RoomSupervisor.spawnRoom()` chạm tới hôm nay) → giữ nguyên hành vi cũ 100% (chỉ đổi
    `phase = PLAYING`). Nếu có `questions` → set `RULES_DISPLAY` rồi NGAY LẬP TỨC `PLAYING` + tự
    gọi `startQuestion("q-1", ...)` cho câu đầu tiên trong danh sách, dùng
    `round_time_limit_seconds * 1000` làm thời lượng (fallback 25000ms — SỐ ĐÃ DÙNG SẴN KHẮP nơi
    trong test/E2E của repo này làm chuẩn thời lượng câu hỏi, không phải số mới bịa ra).
  - Đáp án đúng của câu hỏi trắc nghiệm quy ước tạm là **chỉ số đáp án dạng chuỗi** (`"0".."3"`,
    tức `String.valueOf(question.correctOptionIndex())`) — PO chưa đặc tả option có ID riêng
    (§3.1 chỉ nói "options A–D"), quy ước này cần xác nhận lại khi có client contract thật.
  - `RoomActor.onStartGame`: thêm `scheduleFlushIfDirty()` (trước đây KHÔNG có — vì trước Task 28,
    `startGame()` không bao giờ tự đổi state nào khác ngoài `phase`, giờ có thể tự bắn câu hỏi 1
    nên cần thử flush giống `onStartQuestion` đã làm).
  - **Phát hiện phụ quan trọng, KHÔNG sửa trong task này (ngoài phạm vi, ghi lại để không quên):**
    grep xác nhận `QUESTION_STARTED` (MessageType 22) **chưa từng được build/gửi ở bất kỳ đâu**
    trong `uni-game-engine` — chỉ tồn tại trong `DeliveryClassifier` (phân loại độ tin cậy phía
    Gateway) và trong proto. Toàn bộ luồng "câu hỏi bắt đầu" hiện tại (kể cả đường cũ qua
    `RoomActor.StartQuestion` mà nhiều test P2 trước đây đã dùng) chỉ dựa vào `ROOM_STATE_SNAPSHOT`
    phản ánh `current_question_id`/`deadline_ms` thay đổi, client tự suy ra câu hỏi mới — không có
    message `QUESTION_STARTED` rời rạc nào thật sự tồn tại trên wire. Task 28 KHÔNG tạo ra gap này
    (nó có sẵn từ trước, ảnh hưởng cả đường `StartQuestion` thủ công cũ), chỉ đi qua đúng cơ chế
    (`isDirty()`/`scheduleFlushIfDirty()`) mà đường cũ đã dùng — không mở rộng thêm phạm vi để sửa.
- **Test mới:** `RoomStateAutoStartFirstQuestionTest` (4 case: tự bắn câu 1 đúng phase/deadline;
  chấp nhận đáp án đúng cấu hình; fallback 25s khi không set `round_time_limit`; hành vi CŨ giữ
  nguyên 100% khi không có `questions`).
- **Verify thật:** `mvn -pl :uni-game-engine test -Dtest=RoomStateAutoStartFirstQuestionTest`:
  4/4 pass. `mvn clean install` toàn reactor thật: **293/293 test pass, 0 lỗi**, exit code 0.
- **Việc còn lại lúc viết task này (2026-09-11, TRƯỚC Task 33) — ngoài phạm vi Task 28, thuộc gap
  Task 11 lớn hơn nhiều:** `RoomSupervisor.spawnRoom()` (đường join thật, giáo viên/học sinh join
  qua Gateway thật) vẫn CHỈ spawn được `GAME_MODE_SOLO`, không đọc `GameDefinition`/`questions` từ
  đâu cả — mọi thứ Task 28 vừa code chỉ chứng minh được qua `RoomState` trực tiếp hoặc hook
  test-only (`SpawnConfiguredRoom`), giống hệt cách toàn bộ P2 (Task 20-27) đã hoạt động từ trước
  tới giờ. Đóng gap này cần 1 nguồn "game-definition-authoring" thật (CMS/API nào đó tạo
  `GameDefinition` cho 1 lớp học cụ thể).
  **✅ ĐÃ ĐÓNG (2026-09-11, cùng phiên, sau task này) — xem `Task 33`:** CMS Game Session
  Provisioning (REST endpoint nội bộ ghi `GameDefinition` vào Valkey, `RoomSupervisor.handleJoin`
  đọc lại lúc join thật) đã cung cấp đúng nguồn dữ liệu này. Từ Task 33 trở đi, tự động bắn câu hỏi
  1 (phần vừa code ở Task 28 này) hoạt động được qua đường join THẬT, không chỉ qua test hook nữa —
  đoạn này giữ nguyên làm lịch sử (mô tả đúng tình trạng tại THỜI ĐIỂM viết Task 28), không xoá để
  khỏi mất bối cảnh vì sao Task 33 cần thiết.
- **Câu hỏi PO còn treo (không đổi bởi Task 28):** "câu 2 trở đi tự động hay cần `NEXT_STEP` thủ
  công" — vẫn chưa có câu trả lời, `TeacherCommand.NEXT_STEP` vẫn chưa nối dây.

### Task 29: Input Schema Group A/B theo PO V2.2 (§3) — ✅ XONG phần schema thuần (2026-09-11, verify thật), CHƯA WIRE VÀO ĐƯỜNG JOIN THẬT (thuộc Task 28)
- **Vì sao làm ngay bây giờ:** PO V2.2 §3 lần đầu tiên đưa ra đủ tên trường + kiểu dữ liệu cho
  Group A/B — đóng phần lớn "backlog schema Group A/B" đã ghi từ Task 27 (`max_players`,
  `team_assignment`, `late_join_policy`, `scoring_rule=speed_based`, giá trị `score_aggregation`).
  Không còn phải đoán CMS sẽ gửi field nào nữa.
- **Đã audit code thật trong phiên này (không suy đoán):** `GameDefinition` hiện **không có bất kỳ
  field nào** trong số: `max_players`, `late_join_policy`, `team_assignment`, `team_names`,
  `team_colors`, `questions` (danh sách câu hỏi), `round_time_limit`, `intro_narrative`,
  `progress_display_mode`, `progress_stages`. `ScoreAggregation` (enum riêng,
  `definition/ScoreAggregation.java`) hiện chỉ có `SUM_ALL`/`AVERAGE` — thiếu `FIRST_CORRECT_ONLY`;
  ngược lại PO V2.2 §3.1 liệt kê đúng `sum_all | first_correct_only`, không nhắc `AVERAGE` (cần hỏi
  PO có giữ `AVERAGE` không, không tự xoá field đang có test cover).
- **Gap lớn nhất trong nhóm này:** hiện tại **không có khái niệm "danh sách câu hỏi" nào trong
  `GameDefinition` cả** — mọi câu hỏi trước giờ chỉ tồn tại qua tham số truyền trực tiếp vào
  `RoomActor.StartQuestion` (test-only, xem Task 28). Đây chính là gap nguồn dữ liệu câu hỏi đã ghi
  từ Task 11 (P1) và nhắc lại xuyên suốt Task 21/25/26 (P2) dưới tên "`RoomSupervisor.spawnRoom()`
  chưa có nguồn `GameDefinition` thật" — PO V2.2 giờ đã trả lời PHẦN "câu hỏi ở đâu ra" (Group A,
  Học thuật tự nhập `question_text`/`options` A-D/`correct_option_index`), chỉ còn thiếu phần code
  đọc field đó vào `GameDefinition`.
- **AC:**
  - [ ] Thêm các field ở bảng Group A/B (PO V2.2 §3.1/§3.2) vào `GameDefinition` — additive
        constructor overload, đúng convention đã dùng từ Task 14/17/18/25 (không phá call site cũ).
  - [ ] `DefinitionLoader` validate: `max_players` trong khoảng 1-12; `late_join_policy` chỉ 2 giá
        trị (`allow_with_zero_score`/`block_after_start`); `team_assignment` chỉ `random`/`manual`.
  - [ ] `ScoreAggregation` thêm `FIRST_CORRECT_ONLY`; giữ nguyên `AVERAGE` cho tới khi có xác nhận
        PO (không tự xoá).
  - [ ] `questions`/`round_time_limit` đưa vào `GameDefinition` dạng cấu trúc mới (Group A) —
        `RoomSupervisor.spawnRoom()` đọc từ đây để tự tạo phòng với câu hỏi thật, thay vì chỉ qua
        hook test-only (`SpawnConfiguredRoom`/`GetRoomActor`).
  - [ ] `progress_target` bỏ đọc từ Group B — Engine tự gán `= questions.size()` (PO V2.2 §3.2 note
        cuối bảng).
- **Quyết định người dùng (2026-09-11):** đồng ý thêm `questions` làm field RIÊNG trong
  `GameDefinition`, song song `steps` (không map vào DAG `steps` sẵn có) — lý do đã thống nhất:
  `GameDefinition` trong code hiện tại đã là "cấu hình đầy đủ của 1 phòng cụ thể" (có sẵn tiền lệ
  `teamRosters` — danh sách học sinh cụ thể của đúng 1 phòng), không phải template trừu tượng dùng
  chung nhiều lớp; PO V2.2 §6 cũng gộp `questions` chung 1 form với `team_count`/`max_players`,
  khớp đúng mô hình "1 Game = 1 cấu hình trọn gói" đã có trong code. Audit thêm xác nhận `steps`
  gần như chỉ còn phục vụ P1 SOLO — mọi test P2 (COOPERATIVE/TEAM) đều truyền 1 `Step` giả
  (`List.of(new Step("q1", 25_000, List.of()))`) chỉ để qua được check `DefinitionLoader.load()`
  bắt buộc `steps` không rỗng, không hề đọc `step.nextStepIds()` ở bất kỳ đâu trong luồng
  COOPERATIVE/TEAM thật — càng củng cố `questions` phải là field mới, không phải tái dùng `steps`.
- **Đã code thật (file mới):** `Question` (record: `questionText`/`options`/`correctOptionIndex`),
  `TeamAssignmentMode` (`RANDOM`/`MANUAL`), `LateJoinPolicy` (`ALLOW_WITH_ZERO_SCORE`/`BLOCK_AFTER_START`),
  `ProgressDisplayMode` (`SIMPLE_BAR`/`STAGED_VISUAL`). `ScoreAggregation` thêm `FIRST_CORRECT_ONLY`
  (giữ `AVERAGE` — chưa có xác nhận PO để xoá).
- **`GameDefinition`:** thêm 7 field mới ở CUỐI record (`maxPlayers`, `questions`,
  `roundTimeLimitSeconds`, `lateJoinPolicy`, `teamAssignmentMode`, `introNarrative`,
  `progressDisplayMode`) — additive đúng convention đã dùng xuyên suốt Task 14/17/18/25/28: cả 4
  constructor cũ (6/8/11/13-arg) chỉ thêm giá trị mặc định (`0`/`List.of()`/`ALLOW_WITH_ZERO_SCORE`/
  `MANUAL`/`""`/`SIMPLE_BAR`) vào lời gọi `this(...)` sẵn có — **không đổi chữ ký public nào**, đã
  grep xác nhận `RoomState.java`/`RoomActor.java` (2 nơi construct `GameDefinition` thật duy nhất
  trong `main/`) vẫn dùng đúng constructor 8-arg cũ, không bị ảnh hưởng. Thêm 1 constructor MỚI
  (Group A/B, 14-arg, nhận `questions` thay vì `progressTarget`) — tự tính `progressTarget =
  questions.size()` ngay trong constructor (không qua field riêng để "tin" giá trị từ ngoài, đúng
  tinh thần server-authoritative xuyên suốt repo này).
- **`DefinitionLoader`:** thêm `checkPoV22Schema()` (gọi cho MỌI definition, không riêng mode nào)
  — `max_players` phải trong [1,12] khi có set (0 = sentinel "chưa set", không phải giá trị hợp
  lệ để so [1,12] nên bỏ qua check); mỗi `Question` phải ≥2 `options` và `correct_option_index`
  hợp lệ. `late_join_policy`/`team_assignment` KHÔNG cần check runtime "chỉ 1 trong N giá trị" vì
  đã là Java enum thật (khác `shared_resource` — kiểu cũ hơn cũng enum nhưng có thêm rule theo
  mode) — kiểu dữ liệu tự đảm bảo, viết thêm check sẽ là dead code không bao giờ chạy tới.
- **Test mới:** `GameDefinitionTest` (mới, 2 case: `progress_target` tự tính đúng =
  `questions.size()` qua constructor Group A/B; mọi field V2.2 mới đều có default đúng qua
  constructor cũ) + `DefinitionLoaderTest` (+7 case: max_players hợp lệ/biên trên/biên dưới/chưa
  set, question thiếu option/sai index/hợp lệ).
- **CỐ Ý CHƯA LÀM trong task này (ghi rõ ranh giới, không phải bỏ sót):**
  - **`RoomSupervisor.spawnRoom()` (đường join thật) CHƯA đọc `questions` để tự tạo phòng** —
    đúng như đã nói trước khi code: việc này thuộc Task 28 (nối `RULES_DISPLAY`/tự động bắn câu hỏi
    1), Task 29 chỉ mở khoá (cung cấp NGUỒN DỮ LIỆU `questions`), không tự làm luôn phần dùng nó.
  - **`team_colors`** (PO V2.2 §3.1) — cần thêm field mới vào `TeamAssignment` proto
    (`modules/uni-protocol/.../common.proto`), nhưng **không thể regenerate code Protobuf trong
    sandbox này** (không có `mvn`/`protoc` khả dụng) — sửa `.proto` mà không chạy được
    `generate-sources` sẽ để lại repo ở trạng thái nguồn/code sinh ra lệch nhau, rủi ro hơn là
    không sửa. Chưa đụng vào file `.proto` nào. Cần làm ở máy có Maven đầy đủ.
  - **`ScoreAggregation.FIRST_CORRECT_ONLY`** mới chỉ là giá trị enum — `RoomState.computeTeamScore()`
    chưa có nhánh xử lý cho giá trị này (hiện chỉ biết `AVERAGE` vs "mọi giá trị khác thì SUM").
  - **`LateJoinPolicy.BLOCK_AFTER_START`** mới chỉ là giá trị enum — `RoomState.joinRoom()` chưa
    đọc field này, vẫn luôn cho vào muộn (áp `missedStepPolicy` để chấm điểm, không có nhánh chặn).
  - **`ProgressDisplayMode`/`introNarrative`** chưa có đường dây broadcast ra client (cần 1 field
    protobuf mới ở `RoomStateSnapshot` hoặc message `RULES_DISPLAY` mới của Task 28) — cùng lý do
    protobuf ở trên, để dành cho Task 28.
- **Bug thật phát hiện qua chạy test thật, đã sửa trước khi Green:** thêm 7 field mới làm record
  canonical constructor thành 21-tham-số đã VÔ TÌNH xoá mất khả năng gọi trực tiếp constructor
  14-tham-số CŨ (`steps→winCondition`, dùng bởi ~10 test có sẵn trong `DefinitionLoaderTest`) —
  constructor Group A/B MỚI cũng đúng 14 tham số nhưng khác kiểu (`GameMode` ở vị trí 1 thay vì
  `List<Step>`), nên `javac` báo lỗi kiểu không khớp thay vì âm thầm gọi nhầm constructor (an toàn
  hơn silent-wrong-binding, nhưng vẫn phải sửa). **Sửa:** thêm lại đúng constructor 14-tham-số cũ
  làm overload riêng (delegate sang canonical 21-tham-số với default cho 7 field mới) — khôi phục
  100% khả năng gọi cũ, không sửa test nào khác ngoài việc thêm test mới.
- **Đã tự chạy `mvn` thật (2026-09-11)** — `mvn -pl :uni-game-engine test -Dtest=GameDefinitionTest,DefinitionLoaderTest`:
  **2/2 + 33/33 pass thật** (33 vì `DefinitionLoaderTest` đã có sẵn 26 case cũ + 7 case mới của
  task này, không phải chỉ case mới). Xem thêm mục "Đã tự chạy `mvn` thật" ở cuối Task 31 — 1 lượt
  `mvn clean install` toàn reactor thật đo được **287/287 test pass, 0 lỗi**, trong đó có cả 2 file
  test của task này.
- **Phụ thuộc:** Không phụ thuộc task nào ở trên; Task 28 phụ thuộc NGƯỢC vào task này (nay đã có
  `questions` để đọc, nhưng phần WIRING thật vẫn ở Task 28, chưa làm).

### Task 30: Tie-break theo tổng thời gian trả lời (PO V2.2 §5.1) — ✅ XONG (2026-09-11, verify thật)
- **PO yêu cầu:** Khi 2+ phe/cá nhân bằng % tiến trình lúc hết giờ (`most_points_when_time_up`),
  phe nào có **tổng thời gian trả lời ngắn hơn** thắng. Thời gian trả lời 1 câu = thời gian của
  người trả lời **đầu tiên** trong phe nếu câu đó có ≥2 người trả lời. Đây là câu trả lời PO cho
  đúng phần "luật Hoà chưa chốt" đã ghi từ Task 27 (2026-09-10) — **phần đó nay đã đóng**.
- **Đã audit code:** `WinConditionEvaluator.singleHighestScorer()` hiện chỉ so điểm, trả rỗng khi
  hoà tuyệt đối — chưa có bất kỳ tracking `response_time` nào theo team.
- **AC:**
  - [ ] Track tổng thời gian trả lời từng team (cộng dồn thời gian của người trả lời đầu tiên mỗi
        câu — không cộng thời gian của người trả lời thứ 2 trở đi cùng câu).
  - [ ] `WinConditionEvaluator` thêm bước tie-break: khi ≥2 phe cùng điểm cao nhất, so tổng thời
        gian trả lời, phe nào ít hơn thắng.
  - [ ] Nếu vẫn hoà tuyệt đối sau tie-break (PO không đề cập trường hợp này) — giữ nguyên hành vi
        hiện tại (trả rỗng, không tự bịa người thắng), không tự đoán thêm luật phụ.
- **Verification:** `WinConditionEvaluatorTest` (+4 case: thắng theo điểm bất kể response time,
  tie-break có tác dụng, tie-break vẫn hoà thì trả rỗng, key thiếu response time coi là 0ms) +
  `RoomStateWinConditionTest` (+1 case tích hợp qua `RoomState.submitAnswer`/`endGame` thật, dùng
  `MutableClock` để tạo 2 team cùng điểm nhưng khác thời gian trả lời).
  **Đã tự chạy `mvn` thật (2026-09-11)** — tìm được Maven bundled trong IntelliJ IDEA của người
  dùng (`.../plugins/maven/lib/maven3/bin/mvn`) + JDK 25 do IntelliJ tự tải (`~/.jdks/ms-25.0.4.1`,
  đúng yêu cầu `maven.compiler.release=25`), không cần cài thêm gì ngoài máy sẵn có.
  `mvn -pl :uni-game-engine test -Dtest=WinConditionEvaluatorTest,RoomStateWinConditionTest`:
  **13/13 + 8/8 pass thật**, không phải giả định.
- **Thiết kế:** `RoomState` track `Map<teamId, tổng response time>` + `Set<teamId đã trả lời câu
  hiện tại>` (reset ở `startQuestion`), cộng dồn thời gian của người trả lời ĐẦU TIÊN mỗi team cho
  mỗi câu — tính ngay sau khi qua được check `PAST_DEADLINE` (chỉ tính submit hợp lệ, không tính
  submit bị từ chối). `GameRuleContext` thêm `teamResponseTimeMs(String teamId)`.
  `WinConditionEvaluator` thêm `singleHighestScorerByResponseTime(...)` (giữ nguyên
  `singleHighestScorer` cũ, không sửa hành vi cũ — các test khác dùng hàm cũ không bị ảnh hưởng).
- **Phát hiện phụ (ngoài phạm vi task này, không tự sửa):** `IndividualModeRules.evaluateTimeUp()`
  hiện LUÔN trả `"teacher_ended"`, chưa hề implement `MOST_POINTS_WHEN_TIME_UP` cho chế độ
  `individual` (chỉ `TeamModeRules` có) — PO V2.2 §5.1 áp dụng tie-break cho cả "team" lẫn
  "individual", nên gap này cũng cần đóng, nhưng là 1 task riêng (thêm logic tie-break cho cá nhân,
  không phải sửa nhỏ trong Task 30) — chưa ghi task riêng, nêu ở đây để không quên.
- **Phụ thuộc:** Không phụ thuộc Task 28/29.

### Task 31: Ngưỡng tham gia ≥50% mới cộng tiến trình/điểm Phe (PO V2.2 §5.2) — ✅ XONG (2026-09-11, verify thật)
- **PO yêu cầu:** Phe chỉ được cộng tiến trình/điểm câu đó khi **có 1 học sinh trả lời đúng VÀ ≥50%
  số học sinh trong phe có tham gia trả lời câu hỏi đó** — khác V2.1 (chỉ cần 1 người đúng là tính).
- **Quyết định người dùng (2026-09-11):** thời điểm CHECK ngưỡng 50% chốt **khi câu hỏi KẾT THÚC**
  (không phải real-time mỗi lần nộp bài).
- **Hệ quả lớn đã xác nhận với người dùng trước khi code (không phải chỉ 1 chỗ sửa nhỏ):** hành vi
  cộng tiến trình Phe đổi từ "tức thời, biết ngay khi có người trả lời đúng" (như V2.1/code cũ)
  sang "chỉ biết khi câu hỏi đóng lại" — đổi cả UX cạnh tranh thời gian thực của `first_to_finish`.
  Người dùng xác nhận chấp nhận đánh đổi này và chấp nhận sửa lại các test cũ đang giả định hiệu
  ứng tức thời.
- **Thiết kế thật đã code:**
  - `PlayerRecord` thêm `correctCurrent` (song song `answeredCurrent` có sẵn) — đúng câu trả lời
    của học sinh CHO CÂU HIỆN TẠI, reset ở `startQuestion()` giống `answeredCurrent`.
  - `GameRuleContext` thêm `hasAnsweredCurrentQuestion(String)` / `hasAnsweredCurrentQuestionCorrectly(String)`.
  - `GameModeRules` thêm `default void finalizeQuestionOutcome(GameRuleContext context) {}` (no-op
    mặc định — chỉ `TeamModeRules` override, Solo/Individual/Cooperative không đổi gì).
  - `TeamModeRules.applyAnswerOutcome` giờ KHÔNG làm gì nữa (progress không còn cộng tức thời).
    `TeamModeRules.finalizeQuestionOutcome` (mới) duyệt từng roster: `participatedRatio =
    (số đã trả lời trong roster) / (tổng số thành viên roster)`; nếu có ≥1 đúng VÀ
    `participatedRatio >= 0.5` thì mới `incrementTeamProgress` + check `first_to_finish`.
  - `RoomState.startQuestion()` gọi `modeRules.finalizeQuestionOutcome(this)` NGAY ĐẦU HÀM (đóng
    câu hỏi TRƯỚC đó) trước khi ghi đè `currentQuestionId`/reset `answeredCurrent`/`correctCurrent`
    cho câu mới — chỉ gọi nếu `currentQuestionId != null` (không có câu nào trước đó thì bỏ qua).
    `RoomState.endGame()` cũng gọi `finalizeQuestionOutcome` trước `evaluateTimeUp`, có guard kép
    (`if phase != FINISHED`) 2 lần để tránh `evaluateTimeUp` ghi đè `gameOverReason` nếu chính
    `finalizeQuestionOutcome` đã kết thúc game (vd. `first_to_finish` đạt đúng lúc câu cuối đóng).
  - Đếm "đã tham gia trả lời" dùng ĐÚNG roster **tĩnh** (số lượng cấu hình trong `TeamAssignment`,
    không phải số học sinh ĐÃ join) — 1 học sinh chưa từng join không thể "trả lời" nên tự động bị
    tính là chưa tham gia, khớp đúng tinh thần PO (không cần field riêng đếm "online").
- **Phát hiện phụ tốt (side-effect đúng của refactor, không phải bug mới):** code CŨ tăng tiến
  trình Phe MỖI LẦN có 1 người trả lời đúng — nếu 2 người cùng team cùng trả lời đúng 1 câu, tiến
  trình bị cộng 2 LẦN cho đúng 1 câu (không khớp PO "1 học sinh đúng là tính điểm CHO ĐỘI", ý là
  tính 1 lần/câu). Thiết kế mới (đánh giá 1 lần lúc đóng câu) tự nhiên sửa luôn lỗi này, không cần
  sửa thêm.
- **Test đã cập nhật (không né tránh việc phải sửa, đúng như đã báo trước):**
  - `RoomStateWinConditionTest`: 3 test `first_to_finish` (`should_setFirstToFinishReasonAndWinnerTeamId_whenATeamFinishesFirst`,
    `should_stopTheActorFromDoubleFinishing_whenTheLosingTeamAnswersAfterward`,
    `should_notOverwriteWinConditionReason_when_endGameIsCalledAfterAnAutomaticFinish`) thêm bước
    `room.startQuestion("q-2", ...)` sau submit để ĐÓNG câu 1 trước khi assert — 2 test còn lại
    (`most_points_when_time_up`) đã gọi `endGame()` sẵn từ trước, không cần sửa (dựa vào
    `computeTeamScore`/điểm cá nhân, không phụ thuộc tiến trình Phe).
  - `TeamRoomActorTest.should_broadcastGameOverWithWinningTeamId_when_firstToFinish`: thêm
    `JoinRoom` (bắt buộc phải join thật để có `PlayerRecord` — trước đây test bỏ qua bước join vì
    chấm điểm không cần, giờ đếm tham gia thì cần) + `StartQuestion("q-2",...)` để đóng câu 1;
    đổi `broadcastInbox.receiveMessage()` (giả định GameOver là tin NHẤT) sang lọc
    `getAllReceived()` theo `MessageType.GAME_OVER` (vì giờ có thêm broadcast từ `JoinRoom`/`StartQuestion` xen giữa).
  - `InclassGroupGameE2ETest`'s kịch bản "team-speed-race": Team A có 3 thành viên
    (student-01/02/03) — kịch bản CŨ chỉ 1 người/câu trả lời (33% < 50%, sẽ KHÔNG còn đạt ngưỡng
    theo luật mới) — sửa thành **2 người** (student-01 VÀ student-02) trả lời mỗi câu (2/3 ≈ 67% ≥
    50%), thêm bước đóng câu (`startQuestion` câu kế) trước khi chờ `GameOver`. `student-03` cố ý
    tiếp tục KHÔNG bao giờ trả lời, giữ nguyên đúng ý nghĩa gốc của test "GameOver vẫn tới cả thành
    viên chưa từng trả lời".
  - `RoomStateTeamModeTest`: không cần sửa gì — 2 test aggregate-score dùng `computeTeamScore`/điểm
    cá nhân (không đổi); test "ghost student not on any roster" không gọi bước đóng câu nào nên
    hành vi giữ nguyên (không advance progress, đúng cả trước và sau thay đổi).
- **Hot Snapshot (Task 14) — cập nhật đồng bộ để không mất dữ liệu khi Engine pod crash giữa
  chừng:** `RoomStateSerializer` thêm ghi/đọc `PlayerRecord.correctCurrent` (cạnh `answeredCurrent`
  đã có) và map `teamResponseTimeMs` (Task 30, additive ở cuối format, cùng vị trí logic với
  `teamProgress`). **Giới hạn còn sót lại (nhỏ, đã biết, chưa sửa):** `teamsRespondedToCurrentQuestion`
  (Set nội bộ dùng riêng cho tie-break "người trả lời ĐẦU TIÊN mỗi câu", Task 30) chưa được
  serialize — nếu Engine crash+restore ĐÚNG GIỮA 1 câu hỏi đang mở, đội đã có người trả lời trước
  khi crash có thể bị tính lại là "chưa ai trả lời" sau restore, cộng thêm 1 lần response-time nữa
  cho người trả lời tiếp theo sau restore. Ảnh hưởng: sai lệch NHỎ trong số liệu tie-break ở đúng
  tình huống hiếm (crash giữa câu), không ảnh hưởng điểm số/tiến trình chính. Chưa sửa vì tỉ lệ xảy
  ra rất thấp và không có test nào cho crash-giữa-câu hiện tại — ghi lại để không quên, không phải
  bỏ sót không biết.
- **Phát hiện phụ khác (ngoài phạm vi, không tự sửa):** logic `startQuestion()` gọi
  `finalizeQuestionOutcome` NGAY CẢ KHI phase đã `FINISHED` (không có guard) — nếu ai đó gọi
  `startQuestion` cho câu tiếp theo SAU KHI game đã kết thúc (hiện chưa ai làm vậy trong code thật,
  đây chính là gap Task 28: chưa có luồng "next question" thật), phương thức vẫn tiếp tục ghi đè
  `currentQuestionId`/`deadlineMs` dù đã FINISHED. Không sửa ở đây vì thuộc phạm vi thiết kế "ai
  gọi startQuestion khi nào" của Task 28, chưa phải bug thấy được trong hành vi thật hiện tại.
- **Bug thật phát hiện qua chạy test thật (không phải giả định, đúng tinh thần "prove-it" của repo
  này) — đã sửa trước khi Green:** `RoomActor.onStartQuestion()` gọi `state.startQuestion(...)`
  nhưng KHÔNG kiểm tra `phase == FINISHED` sau đó để broadcast `GameOver` + dừng actor — khác hẳn
  `onSubmitAnswer`/`onResync` vốn đã có đúng nhánh này từ trước. Trước Task 31, mọi lượt "kết thúc
  game" luôn xảy ra bên trong `onSubmitAnswer` (chấm điểm tức thời) nên nhánh đó đủ dùng; sau Task
  31, việc đóng câu hỏi (`startQuestion` cho câu kế) cũng có thể tự kết thúc game (Team đạt
  `progress_target` lúc `finalizeQuestionOutcome`), nhưng `onStartQuestion` chưa từng có nhánh
  broadcast tương ứng — `TeamRoomActorTest.should_broadcastGameOverWithWinningTeamId_when_firstToFinish`
  Red đúng chỗ (`NoSuchElementException` vì không tìm thấy `GameOver` nào được broadcast) đã bắt
  đúng lỗi này. Sửa: thêm đúng nhánh `if (state.phase() == GamePhase.FINISHED) { broadcast + stop
  }` vào `onStartQuestion`, giống hệt pattern đã có ở `onSubmitAnswer`.
- **Đã tự chạy `mvn` thật (2026-09-11)** — dùng Maven bundled trong IntelliJ IDEA của người dùng +
  JDK 25 do IntelliJ tự tải (`~/.jdks/ms-25.0.4.1`) như đã tìm ở Task 30. Kết quả thật:
  - `mvn -pl :uni-game-engine test` (toàn bộ module, không chỉ các file liên quan Task 29-31):
    **184/184 pass, 0 lỗi.**
  - `mvn install -DskipTests` cho `uni-protocol`/`uni-observability`/`uni-game-engine`/
    `uni-websocket-gateway` (để `.m2` có bản mới nhất trước khi `uni-e2e` build — lần chạy đầu
    `uni-e2e` dùng nhầm jar `uni-game-engine` CŨ trong `.m2` từ trước phiên này, khiến
    `InclassGroupGameE2ETest` Red sai chỗ do version lệch, không phải do code Task 31 sai — xác
    nhận lại bằng cách install rồi chạy lại, Green ngay).
  - `mvn -pl :uni-e2e test -Dtest=InclassGroupGameE2ETest`: **2/2 pass** (cooperative-boss +
    team-speed-race, sau khi sửa kịch bản team-speed-race cho đúng ngưỡng 50% mới — xem trên).
  - `mvn clean install` **toàn reactor thật (không `-q` che lỗi, exit code 0)**: **287/287 test
    pass, 0 lỗi** — 7 protocol + 92 gateway (bao gồm cả `IpAdmissionControllerTest` sau khi giảm
    ngưỡng L1 xuống 2000) + 184 engine + 4 e2e. Đây là số liệu thật đo được trong phiên này, không
    phải số liệu lịch sử copy lại.
- **Phụ thuộc:** Không phụ thuộc Task 28/29/30 để code, nhưng Task 32 (cúp thưởng) nên làm SAU task
  này vì `computeTeamScore` giờ phản ánh đúng luật ≥50% mới.

### Task 32: Cúp thưởng (Trophy) cuối trận (PO V2.2 §5.2) — ✅ XONG (2026-09-11, verify thật)
- **PO yêu cầu:** Khi trận kết thúc, số cúp mỗi học sinh nhận = số điểm của Team mình (1 điểm = 1
  cúp), làm tròn LÊN nếu điểm lẻ. Học sinh thoát/mất kết nối giữa chừng vẫn nhận cúp nếu đã trả lời
  ≥1 câu (§6 luật biên 4, PO V2.2).
- **Đã code thật:**
  - `common.proto`: thêm `uint32 trophies = 8;` vào `PlayerState` (additive, đúng ADR-1 — 1 schema
    dùng chung, `final_standings` trong `GameOver` là `repeated PlayerState` sẵn có). Đã regenerate
    thật bằng `mvn -pl :uni-protocol install` (có Maven+JDK25 rồi nên làm được, khác Task 29 lúc
    chưa tìm ra Maven).
  - `PlayerRecord` thêm `hasEverAnswered` (khác `answeredCurrent`/`correctCurrent` — KHÔNG bao giờ
    reset qua các câu, set `true` một lần trong `submitAnswer` khi có submit hợp lệ) — đúng cơ chế
    cần cho luật biên 4 §6 ("đã tham gia trả lời ít nhất 1 câu hỏi").
  - `RoomStateProtobufMapper.computeTrophies()`: trả 0 nếu không phải `GAME_MODE_TEAM` hoặc
    `!hasEverAnswered`; ngược lại trả đúng `computeTeamScore(roster)` của đội học sinh đó — CHỦ Ý
    dùng đúng số đã hiển thị cho học sinh (không tính lại riêng có làm tròn khác), tránh lệch giữa
    "điểm team hiển thị" và "số cúp nhận" (2 con số khác nhau cho cùng 1 khái niệm sẽ gây khó hiểu
    hơn PO). Chỉ áp dụng trong `buildGameOver()` (không lẫn vào snapshot khi đang chơi — đã audit
    xác nhận `RoomStateProtobufMapper.buildPlayerState` chỉ được gọi từ `buildGameOver`, snapshot
    sống dùng bản `buildPlayerState(String)` riêng của `RoomState` không đụng tới cúp).
  - **Chưa làm — cần PO/BA quyết định trước khi mở rộng, không tự đoán:** làm tròn LÊN chỉ có ý
    nghĩa khi điểm team có phần lẻ, nhưng `computeTeamScore()` hiện luôn trả `int` (kể cả
    `AVERAGE` đã tự floor bằng phép chia nguyên) — nếu sau này có công thức tính điểm cho ra số
    lẻ thật, cần quay lại xác nhận "làm tròn lên" áp dụng NGAY LÚC tính team score hay chỉ lúc quy
    đổi cúp (2 cách cho kết quả khác nhau).
- **Test mới:** `RoomStateWinConditionTest` (+2 case: cúp = điểm team cho người đã trả lời, 0 cúp
  cho người chưa từng trả lời dù cùng team thắng; 0 cúp cho mode COOPERATIVE).
- **Verify thật:** `mvn -pl :uni-game-engine test -Dtest=RoomStateWinConditionTest`: 10/10 pass.
  `mvn clean install` toàn reactor thật: **289/289 test pass, 0 lỗi** (287 trước Task 32 + 2 case
  mới), exit code 0.
- **Phụ thuộc:** Làm sau Task 31 như dự tính — `computeTeamScore` đã phản ánh đúng luật ≥50%.

### Task 33: CMS Game Session Provisioning — đóng gap Task 11 cho đường join thật — ✅ XONG (2026-09-11, verify thật qua Docker thật)
- **Bối cảnh:** người dùng yêu cầu thiết kế + code thật cách CMS (cùng hạ tầng K8s) đưa
  `GameDefinition` vào Engine cho 1 phòng cụ thể TRƯỚC khi học sinh join — đóng đúng gap đã nhắc đi
  nhắc lại từ Task 11 (P1) tới Task 28 (P2): `RoomSupervisor.spawnRoom()` (đường join thật) không
  có nguồn nào để biết phòng chơi game gì. Quyết định kiến trúc (đã thống nhất qua thảo luận trước
  khi code): REST endpoint nội bộ (Spring MVC, KHÔNG phải hot path Netty) ghi vào Valkey (đã là
  hard dependency sẵn có) — không để CMS chạm thẳng Valkey (tách bạch ranh giới hạ tầng), không cần
  domain/TLS mới (cùng K8s cluster, dùng internal Service DNS + NetworkPolicy).
- **Đã code thật (9 file mới/sửa):**
  - `GameSessionDefinitionStore` (interface, `room/`) + `NoopGameSessionDefinitionStore` (test
    default) + `DistributedGameSessionDefinitionStore` (`persistence/`, tái dùng ĐÚNG connection
    Valkey của `DistributedRoomSnapshotStore` — không mở connection thứ hai, đúng nguyên tắc đã
    dùng từ Task 21/`EnginePodPresence`). Key `room:definition:{room_id}`, TTL 7 ngày (placeholder
    thực dụng, ghi rõ trong javadoc — chưa có lifecycle "session kết thúc thì xoá" thật).
  - `GameSessionDefinitionRequest`/`QuestionRequest`/`ProgressStageRequest`/`TeamRequest`
    (`definition/`, DTO JSON cho CMS) + `GameSessionDefinitionMapper` (parse + validate qua
    `DefinitionLoader` trước khi cho vào Valkey — sai thì chặn ngay tại API, không rơi xuống thành
    crash actor sau này).
  - `GameSessionProvisioningController` (`provisioning/`, `POST /internal/game-sessions/{room_id}/definition`)
    + `GameSessionProvisioningConfig` (Spring `@Bean` wiring) — chạy trên cổng quản trị Spring MVC
    (8090), không phải cổng Netty hot path, nên không vi phạm luật "không I/O trên Netty EventLoop".
  - `RoomSupervisor`: `handleJoin` giờ load ĐỒNG THỜI Hot Snapshot (đã có từ Task 14) VÀ definition
    đã provision (mới) qua `thenCombine` 2 future async — 1 bên lỗi không làm hỏng bên kia. Câu
    hỏi mới không tồn tại trước đây tự nhiên có luôn câu trả lời đúng: room được RESTORE (crash
    recovery) của 1 game KHÔNG-SOLO giờ cũng dùng đúng definition đã provision, không chỉ phòng
    tạo mới — vì cùng 1 lần load, không phân biệt 2 trường hợp.
- **3 bug thật phát hiện qua test + Docker thật (không phải giả định), đã sửa trước khi coi XONG:**
  1. **`DefinitionLoader.load()` bắt buộc `steps` không rỗng VÔ ĐIỀU KIỆN** — chặn đứng MỌI định
     nghĩa Group A/B thật (chỉ có `questions`, không có `steps`). Mọi test P2 trước đây "né" được
     lỗi này chỉ vì tự tay nhét 1 `Step` giả không ai đọc tới (`RoomStateTeamModeTest` và tương tự)
     — `RoomSupervisorTest` mới (định nghĩa thật từ mapper, không có `Step` giả) mới lộ ra lỗi
     này. Sửa: chấp nhận `steps` HOẶC `questions` không rỗng (một trong hai), chỉ chạy validate DAG
     khi `steps` thật sự được dùng.
  2. **`@PathVariable String roomId` (thiếu tên tường minh) → 500 lúc runtime thật** — project
     không bật cờ compiler `-parameters`, nên Spring không suy ra được tên tham số qua reflection.
     Chỉ lộ ra khi gọi `curl` thật qua Docker, không lộ qua bất kỳ unit test nào (Spring context
     test dùng `MockMvc`/không thật sự định tuyến HTTP theo cách này trong bộ test hiện có). Sửa:
     `@PathVariable("roomId")`.
  3. **`int` nguyên thủy cho field JSON optional → 400 lúc runtime thật** khi CMS bỏ trống field
     (`Cannot map 'null' into type 'int'`, do Jackson 3 trong Spring Boot 4's web auto-config —
     cũng chỉ lộ qua `curl` thật, không qua unit test vì test Java luôn truyền giá trị số tường
     minh). Sửa: đổi `maxPlayers`/`sharedResourcePenalty`/`roundTimeLimitSeconds` sang `Integer`
     (nullable), mapper tự coi `null` = `0`.
  4. **Phát hiện phụ (không phải bug, chỉ là hiểu sai ban đầu):** Spring Boot 4.1.1 dùng Jackson 3
     (`tools.jackson.*`) cho web auto-config, KHÔNG tạo bean `com.fasterxml.jackson.databind.ObjectMapper`
     (Jackson 2, vẫn có trên classpath dạng thư viện thuần) — `@Autowired ObjectMapper` (Jackson 2)
     làm context Spring load thất bại (`EngineApplicationTests` đỏ đúng chỗ). Sửa: tự khởi tạo
     `new ObjectMapper()` (Jackson 2) trực tiếp trong `@Bean`, không nhờ Spring tiêm — giống hệt
     cách `RoomSupervisor` đã làm cho mapper riêng của nó.
- **Verify thật (không chỉ unit test):**
  - `mvn -pl :uni-game-engine test`: 198/198 pass (thêm `GameSessionDefinitionMapperTest` 6/6,
    `RoomSupervisorTest` +2 case: dùng đúng definition đã provision / fallback SOLO khi chưa
    provision gì).
  - `mvn clean install` toàn reactor thật: **301/301 test pass, 0 lỗi**, exit code 0.
  - **Chạy thật qua `docker compose -f docker-compose.dev.yml up -d --build`** (build lại 2
    Dockerfile multi-stage) + `curl` thật vào `engine-0` (`localhost:18090`):
    - Request hợp lệ (TEAM, 2 đội, 1 câu hỏi) → `200 {"roomId":"room-demo-1","status":"provisioned"}`.
    - Thiếu `gameMode` → `400 {"error":"game_mode is required"}`.
    - Chỉ 1 đội cho TEAM mode → `400 {"error":"team_count must be within [2, 4] for GAME_MODE_TEAM, got 1"}`.
    - Xác nhận trực tiếp bằng `valkey-cli GET room:definition:room-demo-1` — đúng JSON đã gửi, TTL
      ~604788s (khớp 7 ngày như thiết kế).
- **Tài liệu cho team CMS:** `docs/specs/tech-design/cms-game-session-provisioning.md` (mới) — hợp
  đồng API đầy đủ (request/response, ví dụ thật, giới hạn đã biết) để team CMS tích hợp trực tiếp,
  không cần hỏi lại backend.
- **Giới hạn đã biết, KHÔNG phải "sắp xong" (đã ghi rõ trong tài liệu CMS ở trên, nhắc lại đây):**
  - Chỉ áp dụng cho LẦN DỰNG PHÒNG ĐẦU TIÊN của 1 `room_id` — provision lại 1 phòng ĐANG chạy
    không có tác dụng cho tới khi phòng đó bị dựng lại từ đầu.
  - Chỉ đóng được phần "nguồn dữ liệu" — vẫn phụ thuộc Task 28's giới hạn (chỉ câu hỏi 1 tự động,
    `NEXT_STEP` chưa nối dây, `introNarrative`/`progressDisplayMode` chưa có đường broadcast).
  - Không có auth trên endpoint — chỉ an toàn nhờ NetworkPolicy K8s nội bộ.
  - TTL 7 ngày là placeholder, chưa có lifecycle "session kết thúc thì dọn" thật.
- **Phụ thuộc:** Cần Task 29 (schema `questions`) đã xong; mở khoá thật cho Task 28 (giờ có nguồn
  để tự động bắn câu hỏi 1 qua đường join THẬT, không chỉ qua test hook `SpawnConfiguredRoom` nữa).

### Câu hỏi PO còn treo — chưa trả lời, không tự đoán (cập nhật 2026-09-11 sau khi đọc hết V2.2)
- **Luật biên 5 "Cấm hủy giữa ván" (§6 PO V2.2, dòng 158) — VẪN CHƯA GIẢI QUYẾT**, dù V2.2 lặp lại
  nguyên văn câu này từ V2.1. Mâu thuẫn đã ghi ở Task 27 vẫn còn nguyên: `TeacherCommand.END_GAME`
  lúc `PLAYING` vẫn là cơ chế trigger DUY NHẤT cho `most_points_when_time_up` (chưa có
  timer/deadline tự động toàn ván) — 1 guard chặn `EndGame` giữa ván sẽ vô hiệu hoá luôn con đường
  đó. PO V2.2 không thêm field "tổng thời gian ván" hay định nghĩa "hết giờ" nào mới — câu hỏi này
  KHÔNG được V2.2 trả lời, dù đã đọc lại toàn bộ tài liệu.
- **Điểm sàn tối thiểu của công thức `speed_based` (§5.4)** — PO chỉ ghi "*ví dụ: 2 điểm*", chưa
  chốt số thật. Không tự chọn số khi implement Task cần công thức này.
- **`shared_resource = lives`: số lives ban đầu** — V2.2 vẫn chỉ liệt kê `lives` là 1 giá trị hợp
  lệ của `shared_resource` (§3.1), không nói số lượng ban đầu là bao nhiêu. Gap cũ từ Task 23 vẫn
  còn nguyên.
- **`streak_bonus`** — gap ghi ở Task 27 (PO tự mâu thuẫn V2.1 §5.4 vs bảng schema §7) **không còn
  xuất hiện ở đâu trong V2.2** (đã grep xác nhận). Coi như PO đã tự rút yêu cầu này khỏi scope —
  không cần task riêng nữa, chỉ ghi lại để không ai thắc mắc "sao gap cũ biến mất".
- **Câu hỏi thứ 2 trở đi có tự động chuyển theo `round_time_limit` hết hạn hay vẫn cần GV bấm
  `NEXT_STEP` thủ công** — đã ghi ở Task 28, nhắc lại ở đây vì liên quan trực tiếp Task 31 (thời
  điểm chốt tỉ lệ tham gia 50% phụ thuộc vào việc chuyển câu là tự động hay thủ công).

### Tổng hợp việc còn tồn đọng (cập nhật 2026-09-11, sau khi Task 33 xong) — không phải task mới, chỉ gom lại cho dễ theo dõi

Toàn bộ Task 20-33 nay đã ✅ XONG hoặc ❌ HỦY (PO quyết định) — không còn task nào "đang code dở".
Phần còn lại là 3 nhóm việc tồn đọng thật, nằm rải rác trong các task ở trên, gom về đây để không
phải lục từng task:

1. **Chờ PO trả lời (không tự đoán)** — xem mục "Câu hỏi PO còn treo" ngay phía trên: luật biên 5
   (cấm hủy giữa ván, mâu thuẫn với `END_GAME` là trigger duy nhất), điểm sàn `speed_based`, số
   `lives` ban đầu, câu 2 trở đi tự động hay `NEXT_STEP` thủ công.
2. **Enum/field đã có trong schema (Task 29/30) nhưng CHƯA có logic tiêu thụ** — không chặn ai
   dùng hệ thống hôm nay (giá trị mặc định vẫn chạy đúng), chỉ là chưa có nhánh xử lý khi CMS gửi
   giá trị khác mặc định:
   - `ScoreAggregation.FIRST_CORRECT_ONLY` — `RoomState.computeTeamScore()` chưa có nhánh riêng.
   - `LateJoinPolicy.BLOCK_AFTER_START` — `RoomState.joinRoom()` chưa đọc field này, luôn cho vào
     muộn.
   - `ProgressDisplayMode`/`introNarrative` — chưa có đường broadcast ra client (cần field proto
     mới ở `RoomStateSnapshot`).
   - `TeamAssignment.team_colors` (PO V2.2 §3.1) — cần thêm field `.proto` mới; KHÔNG còn bị chặn
     bởi thiếu Maven như lúc viết Task 29 (đã tìm ra Maven bundled trong IntelliJ từ Task 30), chỉ
     là chưa ai yêu cầu làm.
   - `IndividualModeRules.evaluateTimeUp()` chưa implement `MOST_POINTS_WHEN_TIME_UP` (chỉ
     `TeamModeRules` có, phát hiện phụ ở Task 30) — PO V2.2 §5.1 áp dụng tie-break cho cả team lẫn
     individual.
3. **Giới hạn nhỏ đã biết, chấp nhận được, không phải bug** (mỗi mục đã ghi rõ lý do trong task
   gốc, không lặp lại chi tiết ở đây):
   - Task 31: `teamsRespondedToCurrentQuestion` chưa serialize vào Hot Snapshot (sai lệch tie-break
     rất nhỏ nếu crash đúng giữa 1 câu).
   - Task 32: quy tắc "làm tròn lên" cho cúp chưa cần áp dụng thật vì `computeTeamScore()` luôn trả
     `int`; cần PO/BA xác nhận lại nếu sau này có công thức điểm cho ra số lẻ.
   - Task 33: chỉ áp dụng cho lần dựng phòng ĐẦU TIÊN của 1 `room_id`; không có auth trên endpoint
     provisioning (dựa vào NetworkPolicy K8s nội bộ); TTL 7 ngày là placeholder chưa gắn với
     lifecycle session thật; `QUESTION_STARTED` (MessageType 22, gap từ Task 28) vẫn chưa từng
     được build/gửi ở bất kỳ đâu — client vẫn chỉ suy ra câu hỏi mới qua diff `ROOM_STATE_SNAPSHOT`.

---

## 3. Verification Plan

- `mvn clean test` xanh toàn bộ reactor.
- E2E: `mvn -pl :uni-e2e test` (JUnit5 thật, không phải Cucumber — xem Task 26).
