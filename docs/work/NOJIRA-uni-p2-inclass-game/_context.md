# Context: NOJIRA-uni-p2-inclass-game

> **Mục đích:** Thư mục lưu trữ bối cảnh công việc thực thi Phase 2: Game Nhóm & Tập Thể Inclass.

---

## Liên kết Tài liệu Chuẩn (Reference Links)

- **System Architecture Spec:** [`docs/architecture/system-architecture.md §10`](../../architecture/system-architecture.md#10-ví-dụ-thực-chiến-chuyển-đổi-yêu-cầu-po-sang-đặc-tả-kỹ-thuật-dev-specs--kịch-bản-bdd)
- **Product Brief V2.1 (Mới nhất):** [`docs/specs/modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games-v2.1.md`](../../specs/modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games-v2.1.md)
- **Product Brief V1.0 (Tiền nhiệm):** [`docs/specs/modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games.md`](../../specs/modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games.md)
- **Yêu cầu PO V2.1 (nguồn gốc mới):** [`docs/specs/modules/engine/PO_Require_Game+nhóm_+tập+thể+Inclass V2.1.doc`](../../specs/modules/engine/PO_Require_Game+nh%C3%B3m_+t%E1%BA%ADp+th%E1%BB%83+Inclass%20V2.1.doc)
- **Yêu cầu PO V1.0 (nguồn gốc cũ):** [`docs/specs/modules/engine/PO_Require_Game+nhóm_+tập+thể+Inclass.doc`](../../specs/modules/engine/PO_Require_Game+nh%C3%B3m_+t%E1%BA%ADp+th%E1%BB%83+Inclass.doc)
- **Technical Design Spec:** [`docs/specs/tech-design/INCLASS-GAME-001-inclass-group-game-tech-design.md`](../../specs/tech-design/INCLASS-GAME-001-inclass-group-game-tech-design.md)
- **BDD Feature Specifications:**
  - [`docs/specs/bdd/INCLASS-GAME-001-cooperative-boss.feature`](../../specs/bdd/INCLASS-GAME-001-cooperative-boss.feature)
  - [`docs/specs/bdd/INCLASS-GAME-002-team-speed-race.feature`](../../specs/bdd/INCLASS-GAME-002-team-speed-race.feature)
  - ~~`INCLASS-GAME-003-lms-worker-sync.feature`~~ — xoá 2026-09-09, tích hợp `lms-worker` bị PO hủy hẳn (Task 24)
- **Implementation Plan:** [`plan.md`](./plan.md)

---

## Trạng thái hiện tại
- Đã chuẩn hoá tài liệu Product Brief V2.1 kèm bảng so sánh V1.0 vs V2.1.
- **2026-09-09: Task 20 (schema) + Task 21 (cooperative) + Task 22 (team + scoped draft sync) + Task 23 (win condition evaluator, phần lớn) đã XONG**, verify bằng `mvn clean install` toàn reactor (BUILD SUCCESS) — xem `plan.md` cho chi tiết + quyết định thiết kế. `GAME_MODE_COOPERATIVE` và `GAME_MODE_TEAM` đều chạy được end-to-end trong `RoomState` kể cả FSM tự chuyển `FINISHED` + broadcast `GameOver` thật (trước đây `GameOver` chưa từng được gửi ở BẤT KỲ đâu trong codebase, kể cả Phase 1 SOLO — phát hiện phụ khi làm Task 23, đã sửa luôn). `GAME_MODE_INDIVIDUAL` và `shared_resource=LIVES` vẫn bị `DefinitionLoader` từ chối có chủ đích (chưa implement).
- **Gap đã biết, không phải thiếu sót của Task 21/22/23:** `RoomSupervisor.spawnRoom()` (đường join thật) vẫn CHƯA có nguồn `GameDefinition` nào để spawn phòng ở chế độ COOPERATIVE/TEAM — không có định dạng "game-definition-authoring" nào được chốt trong repo này (đã ghi nhận từ Task 11, plan.md P1). Toàn bộ logic đã test kỹ ở tầng `RoomState`/`RoomActor`/`RoomSupervisor` (đơn vị + dispatch), nhưng chưa test được qua đường join thật end-to-end (Docker/WalkingSkeletonTest) vì gap này.
- **Gap mới của riêng Task 23 (có chủ ý, ghi trong `WinCondition.MOST_POINTS_WHEN_TIME_UP`'s javadoc):** không có cơ chế timer/deadline nào tự động kết thúc game khi "hết giờ" — logic ĐÁNH GIÁ ai thắng đã đúng (`WinConditionEvaluator` + `RoomState.endGame()`), chỉ thiếu cái TRIGGER tự động; hôm nay chỉ `TeacherCommand.END_GAME` (thủ công) kích hoạt được. `shared_resource=LIVES` vẫn treo vì PO V2.1 không đặc tả số lives ban đầu.
- **❌ 2026-09-09: Task 24 (tích hợp `lms-worker`) đã bị PO chốt HỦY hẳn khỏi phạm vi Phase 2** —
  không còn là "tạm dừng chờ nguồn ID LMS" nữa. Tài liệu phát hiện chi tiết cũ (contract `lms-worker`
  thật khác BDD/mô tả gốc) + BDD feature `INCLASS-GAME-003-lms-worker-sync.feature` đã xoá khỏi
  repo theo yêu cầu; xem lịch sử git nếu cần tra lại.
- **2026-09-09: Task 25 (Unit Test & RoomActor FSM Tests) đã XONG.** Thêm `RoomActor.create()` overload mới (master constructor mang toàn bộ config cooperative/team) + `CooperativeRoomActorTest`/`TeamRoomActorTest` (actor-level, dùng `BehaviorTestKit`, xác nhận qua `RoomActor` thật chứ không chỉ `RoomState` đơn lẻ) — xem `plan.md` chi tiết. `RoomSupervisor.spawnRoom()` vẫn KHÔNG gọi overload mới này (vẫn thiếu nguồn `GameDefinition` thật, gap Task 11 chưa đổi).
- **2026-09-09: Task 26 (E2E) đã XONG — viết JUnit5 thật (không phải Cucumber, theo quyết định người dùng).** `InclassGroupGameE2ETest` (2 test, 12 client WebSocket thật/mỗi test) cover 2 kịch bản BDD còn lại (cooperative-boss + team-speed-race qua Gateway/Engine thật) — kịch bản thứ 3 (`lms-worker` sync) không còn tồn tại (Task 24 đã bị PO hủy). Gap chặn E2E COOPERATIVE/TEAM đã đóng bằng hook mới `RoomSupervisor.SpawnConfiguredRoom` (cùng tinh thần `GetRoomActor`).
- **⚠️ Phát hiện môi trường (2026-09-09, ngoài phạm vi task, người dùng đã xác nhận bỏ qua):** trong lúc làm Task 26, phát hiện một tiến trình KHÔNG rõ nguồn gốc đang xoá javadoc/comment khỏi rất nhiều file `.java` trên đĩa (73 file bị đổi, gồm cả file từ Task 1-18 không hề bị đụng tới trong phiên này) — không phải do git (không có filter/hook nào khớp), có vẻ là 1 IDE plugin/file watcher trên máy người dùng. Bản đã commit trước đó vẫn nguyên vẹn, nhưng commit Task 26 (xem git log) vô tình chốt lại 1 bản `RoomActor.java` đã mất một phần javadoc cũ (class-level + vài chỗ khác) vì lúc kiểm tra trước khi commit chỉ soát phần MỚI thêm, không soát toàn file. Người dùng xác nhận không cần khôi phục, tiếp tục bình thường — ghi lại đây để biết nguyên nhân nếu sau này thấy mất tài liệu ở các file khác.
- **2026-09-09: Nén LZ4 (P1 Task 22) kéo lên GĐ1 theo yêu cầu người dùng** — không thuộc Phase 2, xem `docs/work/NOJIRA-uni-p1-realtime-core/plan.md` Task 22.
- **2026-09-09: Task 24 (`lms-worker`) bị PO chốt HỦY hẳn** — không còn treo trong `plan.md`, tài liệu/BDD liên quan đã xoá.
- **Toàn bộ Phase 2 (Task 20-23, 25, 26) đã XONG; Task 24 đã hủy.**
- **2026-09-11: Task 28 (mới, chưa làm) — đối chiếu PO Spec V2.2 phát hiện gap FSM `RULES_DISPLAY`
  + Engine phải tự động bắn câu hỏi 1 khi GV bấm "Bắt đầu game" (không chờ GV bấm thêm gì), theo
  §4 PO V2.2. Code hiện tại: `START_GAME` chỉ đổi phase, `TeacherCommand.NEXT_STEP` chưa nối dây
  (rơi vào `default -> log.warn` ở `RoomSupervisor`), `RoomActor.StartQuestion` chỉ được gọi từ
  test. Còn 1 câu hỏi PO chưa trả lời (câu 2 trở đi có tự động theo `round_time_limit` hay vẫn cần
  GV bấm `NEXT_STEP` thủ công) — không tự đoán. Chi tiết đầy đủ: `plan.md` Task 28.**
- **2026-09-11 (tiếp, đọc hết PO V2.2): thêm Task 29-32 (đều 🔴 MỚI, CHƯA LÀM) sau khi đối chiếu
  toàn bộ V2.2 §3/§5 với code thật.** Task 29 (Input Schema Group A/B — `GameDefinition` hiện
  thiếu hẳn `max_players`/`late_join_policy`/`team_assignment`/`questions`/`round_time_limit`,
  đây cũng là gap nguồn dữ liệu câu hỏi ghi từ Task 11); Task 30 (tie-break theo tổng thời gian
  trả lời — đóng luôn "luật Hoà chưa chốt" ghi từ Task 27); Task 31 (ngưỡng tham gia ≥50% mới
  cộng tiến trình Phe — đổi hành vi chấm điểm thật, `TeamModeRules` hiện chưa check tỉ lệ tham
  gia); Task 32 (cúp thưởng cuối trận — tính năng hoàn toàn chưa tồn tại, đã grep xác nhận 0 kết
  quả "trophy/cup/cúp" trong code). Còn 4 câu hỏi PO vẫn treo (không tự đoán): luật biên 5 "cấm
  hủy giữa ván" mâu thuẫn với `END_GAME` là trigger duy nhất (V2.2 KHÔNG giải quyết, lặp nguyên
  văn V2.1); điểm sàn `speed_based` chỉ là "ví dụ" chưa chốt số; số lives ban đầu vẫn chưa có;
  câu 2 trở đi tự động hay cần `NEXT_STEP` thủ công (Task 28). `streak_bonus` (gap cũ Task 27)
  không còn xuất hiện trong V2.2 — coi như PO đã rút yêu cầu, không cần task riêng nữa. Chi tiết
  đầy đủ từng task: `plan.md` Task 29-32 + mục "Câu hỏi PO còn treo".**
- **2026-09-11 (tiếp): Task 30 (tie-break theo tổng thời gian trả lời) code xong** —
  `RoomState`/`GameRuleContext`/`TeamModeRules`/`WinConditionEvaluator` + test mới. **Chưa tự chạy
  được `mvn` trong phiên này (không có trên PATH sandbox)** — cần người dùng chạy
  `mvn -pl :uni-game-engine test -Dtest=WinConditionEvaluatorTest,RoomStateWinConditionTest` và
  xác nhận trước khi coi task này là XONG thật. Phát hiện phụ: `IndividualModeRules` chưa hề
  implement `MOST_POINTS_WHEN_TIME_UP` — gap riêng, chưa sửa trong task này.
- **2026-09-11 (tiếp): Task 31 (ngưỡng ≥50% tham gia mới cộng tiến trình Phe) code xong**, sau khi
  hỏi rõ và người dùng xác nhận 2 quyết định: (1) chốt kiểm tra ngưỡng LÚC CÂU HỎI KẾT THÚC (không
  phải real-time); (2) chấp nhận hệ quả lớn đi kèm — tiến trình Phe không còn cập nhật tức thời,
  phải sửa lại ~10 test cũ đang giả định hiệu ứng tức thời (`RoomStateWinConditionTest` x3,
  `TeamRoomActorTest` x1, `InclassGroupGameE2ETest` team-speed-race). Thiết kế: `PlayerRecord`
  thêm `correctCurrent`; `GameModeRules` thêm `finalizeQuestionOutcome` (default no-op, chỉ
  `TeamModeRules` override); `RoomState.startQuestion()`/`endGame()` gọi finalize để "đóng" câu hỏi
  trước đó trước khi mở câu mới/kết thúc game. Phát hiện phụ tốt: sửa luôn 1 bug cũ (progress cộng
  2 lần nếu 2 người cùng team cùng đúng 1 câu). Đã đồng bộ Hot Snapshot (Task 14) cho
  `correctCurrent` + `teamResponseTimeMs` (Task 30) — còn 1 giới hạn nhỏ chưa sửa
  (`teamsRespondedToCurrentQuestion` chưa serialize, ảnh hưởng tie-break rất nhỏ nếu crash đúng
  giữa 1 câu). **Chưa tự chạy được `mvn`** — cần người dùng xác nhận trước khi coi XONG thật. Chi
  tiết đầy đủ: `plan.md` Task 31.
- **2026-09-11 (tiếp): Task 29 (Input Schema Group A/B) code xong phần schema thuần**, sau khi
  người dùng đồng ý hướng thêm `questions` làm field RIÊNG trong `GameDefinition` (không map vào
  `steps`/DAG cũ — audit xác nhận `steps` gần như chết với P2, mọi test COOPERATIVE/TEAM chỉ
  truyền 1 Step giả để qua validation, không đọc `nextStepIds()` ở đâu cả). Thêm 4 file mới
  (`Question`, `TeamAssignmentMode`, `LateJoinPolicy`, `ProgressDisplayMode`) +
  `ScoreAggregation.FIRST_CORRECT_ONLY` + 7 field mới vào `GameDefinition` (additive, không đổi
  chữ ký constructor cũ nào) + 1 constructor mới tự tính `progress_target = questions.size()` +
  validate trong `DefinitionLoader`. Cố ý CHƯA làm: wiring `RoomSupervisor.spawnRoom()` đọc
  `questions` thật (thuộc Task 28); `team_colors` (cần sửa `.proto` + regenerate, không làm được
  trong sandbox không có Maven); `FIRST_CORRECT_ONLY`/`BLOCK_AFTER_START`/`ProgressDisplayMode`
  mới là enum, chưa có logic tiêu thụ. Chi tiết đầy đủ: `plan.md` Task 29.
- **2026-09-11 (tiếp): TÌM RA được `mvn` thật trên máy người dùng (Maven bundled trong IntelliJ
  IDEA, `~/.jdks/ms-25.0.4.1` do IntelliJ tự tải sẵn đúng Java 25) — verify thật Task 29/30/31
  thay vì chỉ rà soát thủ công như 2 entry trước. Phát hiện + sửa 2 bug thật qua chạy test thật:
  (1) thêm field vào `GameDefinition` làm mất constructor 14-tham-số cũ (`steps→winCondition`) vì
  constructor Group A/B mới cũng vô tình 14-tham-số — `javac` báo lỗi kiểu, đã thêm lại overload cũ;
  (2) `RoomActor.onStartQuestion()` thiếu nhánh broadcast `GameOver`+dừng actor khi đóng câu hỏi
  cũng làm game kết thúc (Task 31 chuyển việc này từ `onSubmitAnswer` sang cả `onStartQuestion`) —
  `TeamRoomActorTest` Red đúng chỗ, đã thêm nhánh giống `onSubmitAnswer`. **Kết quả cuối: `mvn clean
  install` toàn reactor thật, exit code 0, 287/287 test pass, 0 lỗi** (7 protocol + 92 gateway +
  184 engine + 4 e2e, gồm cả `InclassGroupGameE2ETest` team-speed-race sau khi sửa kịch bản cho
  đúng ngưỡng 50%). Task 29/30/31 nay đã có thể coi là ✅ XONG thật, không còn "chưa verify". Chi
  tiết đầy đủ: `plan.md` Task 29/30/31.
- **2026-09-11 (tiếp): Task 32 (cúp thưởng) code xong + verify thật.** Thêm `uint32 trophies = 8`
  vào `PlayerState` proto (đã regenerate thật, không còn bị chặn bởi thiếu Maven như Task 29 nữa),
  `PlayerRecord.hasEverAnswered` (không reset qua câu, khác `answeredCurrent`), tính cúp =
  `computeTeamScore` của team đó cho ai đã từng trả lời ≥1 câu, chỉ áp dụng `GAME_MODE_TEAM`. Chủ ý
  dùng đúng số điểm team đã hiển thị (không tính lại có làm tròn khác) để tránh 2 con số lệch nhau.
  `mvn clean install` toàn reactor thật: **289/289 test pass, 0 lỗi.** Toàn bộ P2 Task 29, 30, 31,
  32 giờ đã ✅ XONG thật. Còn Task 28 (wiring FSM `RULES_DISPLAY` + tự động bắn câu hỏi 1) chưa
  làm — lúc bắt đầu code phát hiện thêm 1 câu hỏi thiết kế mới (thời lượng/cơ chế kích hoạt màn
  hình `RULES_DISPLAY` PO không nói rõ, ảnh hưởng tới việc có cần xây timer server-side mới hay
  không — repo này chưa từng có timer tự động nào). Chi tiết: `plan.md` Task 32 và Task 28.
- **2026-09-11 (tiếp): Task 28 code xong phần lõi engine + verify thật, sau khi người dùng chọn
  "RULES_DISPLAY chỉ là giá trị hình thức, không giữ, không xây timer mới, không bịa số giây".**
  Thêm `RULES_DISPLAY` vào `GamePhase` proto (đã regenerate); `RoomState.startGame()` tự bắn câu
  hỏi 1 (`"q-1"`, đáp án đúng quy ước tạm = chỉ số option dạng chuỗi) khi `questions` (Task 29) có
  nội dung, giữ nguyên 100% hành vi cũ khi rỗng (mọi phòng dựng trước Task 29 + đường
  `RoomSupervisor.spawnRoom()` thật hôm nay). Thêm `scheduleFlushIfDirty()` vào `onStartGame`
  (trước đây thiếu). Phát hiện phụ quan trọng, KHÔNG sửa (ngoài phạm vi): `QUESTION_STARTED`
  (MessageType 22) chưa từng được build/gửi ở bất kỳ đâu trong engine — chỉ tồn tại trong
  `DeliveryClassifier`/proto; toàn bộ luồng "câu hỏi bắt đầu" hiện chỉ dựa vào diff
  `ROOM_STATE_SNAPSHOT`. Test mới: `RoomStateAutoStartFirstQuestionTest` (4/4 pass).
  `mvn clean install` toàn reactor thật: **293/293 test pass, 0 lỗi.** Toàn bộ P2 Task 28-32 giờ
  đã ✅ XONG phần lõi engine. Còn lại (KHÔNG thuộc phạm vi "hoàn thiện P2" lần này): wiring
  `RoomSupervisor.spawnRoom()` đường join thật (gap CMS/authoring lớn hơn nhiều, đã ghi từ Task
  11) và câu hỏi PO "câu 2 trở đi tự động hay NEXT_STEP thủ công" vẫn treo. Chi tiết: `plan.md`
  Task 28.
- **2026-09-11 (tiếp): Task 33 (CMS Game Session Provisioning) — ĐÓNG GAP TASK 11 CHO ĐƯỜNG JOIN
  THẬT, code xong + verify thật qua Docker thật.** Theo yêu cầu người dùng: thiết kế REST endpoint
  nội bộ (không phải CMS chạm thẳng Valkey — tách bạch ranh giới hạ tầng, đã thảo luận trước khi
  code) ghi `GameDefinition` vào Valkey (tái dùng đúng connection có sẵn, không mở connection thứ
  hai). `RoomSupervisor.handleJoin` giờ load ĐỒNG THỜI Hot Snapshot VÀ definition đã provision (2
  future async `thenCombine`) — phòng dựng mới HOẶC được khôi phục sau crash đều dùng đúng cấu
  hình đã provision, không còn mặc định SOLO trừ khi thật sự chưa ai provision gì.
  **3 bug thật phát hiện qua test + qua `curl` thật vào Docker (không phải giả định):**
  (1) `DefinitionLoader.load()` bắt buộc `steps` không rỗng VÔ ĐIỀU KIỆN — chặn đứng mọi định
  nghĩa Group A/B thật (mọi test P2 trước đây né được vì tự nhét 1 Step giả không ai đọc) — sửa
  thành chấp nhận `steps` HOẶC `questions`; (2) `@PathVariable String roomId` thiếu tên tường minh
  → 500 lúc chạy thật (project không bật `-parameters`, chỉ lộ qua `curl`, không lộ qua unit test);
  (3) field JSON optional dùng `int` nguyên thủy → 400 lúc CMS bỏ trống field (Jackson 3 trong
  Spring Boot 4 không cho null vào `int`, chỉ lộ qua `curl` thật) — đổi sang `Integer` nullable.
  Phát hiện phụ: Spring Boot 4.1.1 dùng Jackson 3 cho web, KHÔNG tạo bean `ObjectMapper` Jackson 2
  — tự khởi tạo thay vì nhờ Spring tiêm, giống `RoomSupervisor` đã làm. **Verify thật:**
  `mvn -pl :uni-game-engine test`: 198/198. `mvn clean install` toàn reactor: **301/301, 0 lỗi**.
  `docker compose up -d --build` + `curl` thật vào `engine-0:18090` — request hợp lệ trả 200, 2
  request sai đều trả đúng 400 với message rõ ràng; `valkey-cli GET room:definition:...` xác nhận
  đúng JSON + TTL ~7 ngày. Tài liệu API cho CMS: `docs/specs/tech-design/cms-game-session-provisioning.md`
  (mới). Giới hạn còn lại: chỉ áp dụng lần dựng phòng ĐẦU TIÊN của 1 room_id; vẫn phụ thuộc giới
  hạn Task 28 (chỉ câu 1 tự động); không có auth (chỉ an toàn nhờ NetworkPolicy K8s nội bộ); TTL 7
  ngày là placeholder. Chi tiết đầy đủ: `plan.md` Task 33.
- **2026-09-09 (phiên khác, sau refactor DDD lớn trên P1's `RoomActor`/`RoomState`): Task 27 (đối chiếu Product Brief V2.1) mở, một phần xong.** Review phát hiện `RoomStateProtobufMapper.buildFullSnapshot()` là dead code lệch `Math.floor` (bug tưởng là thật lúc đầu, xác nhận lại là code thừa 0 người gọi, không phải hành vi production) — đã xoá. Luật biên §5.6 rule 5 ("GV không hủy giữa ván") **không sửa được bằng code** — mâu thuẫn trực tiếp với thiết kế đã chốt ở Task 23 (`END_GAME` lúc `PLAYING` là trigger duy nhất cho `MOST_POINTS_WHEN_TIME_UP`, chưa có auto-trigger) — cần PO quyết định ranh giới "hủy" vs "báo hết giờ", giống style G1a/G1c. Khoảng trống schema Group A/B (`max_players`, `team_count`, `team_assignment`, `late_join_policy`, `scoring_rule=speed_based`, `progress_display_mode`, `score_aggregation` đủ giá trị) vẫn treo, cần task CMS riêng. Chi tiết: `plan.md` Task 27.

## State (machine-readable)
```yaml
phase: dev
track: feature
last_skill: tdd
next_skill: writing-plans
progress: "2026-09-09: Task 20+21 xong (xem entry truoc). Task 22 (Team mode + Scoped Draft Sync)
  xong cung ngay: GameDefinition.teamRosters (List<TeamAssignment> proto, tinh, quyet dinh
  upstream/CMS - KHONG round-robin tu dong, vi BDD can nhom 3 nguoi LIEN TIEP ma khong field nao
  (max_players) co san de tinh block-assignment dung); DefinitionLoader them checkTeamMode
  (team_count 2-4, moi roster >=1 thanh vien, khong student_id nao trung 2 doi); RoomState them
  teamIdOf()/computeTeamScore() (sum_all|average, floor)/updateDraft() (tra team_id tu ROSTER
  SERVER, khong tin client - cung nguyen tac voi room_id); PlayerState.team_id +
  TeamAssignment.team_score them vao proto (additive). Wire THAT (khac Task 21 chi dung o
  RoomState): RoomActor.Command.UpdateDraft (bypass coalescing) +
  RoomSupervisor's DRAFT_UPDATE dispatch case - KHONG can sua Gateway gi ca vi
  EngineResponseRouter da co san quy tac 'student_id khac rong -> gui rieng 1 nguoi' tu Task 13.
  Test: RoomStateTeamModeTest (8/8 case moi) + DefinitionLoaderTest (23/23, +6 case). Prove-it:
  tam bo dieu kien loai sender khoi danh sach nhan draft, xac nhan dung 2/8 test Red truoc khi
  tra lai Green. mvn clean install toan reactor: BUILD SUCCESS - 5 protocol + 86 gateway + 148
  engine (134 cu + 14 moi) + 2 e2e, khong regress. Gap con lai (khong phai thieu sot): RoomSupervisor.
  spawnRoom() van chua co nguon GameDefinition that de spawn phong COOPERATIVE/TEAM qua duong join
  that - ke thua dung gap da ghi nhan tu Task 11 (P1), khong phai rieng Task 21/22.
  2026-09-09 (tiep, cung phien): Task 23 (WinConditionEvaluator) xong phan lon. WinCondition enum
  (PROGRESS_COMPLETED/FIRST_TO_FINISH/MOST_POINTS_WHEN_TIME_UP) them vao GameDefinition (additive,
  14 tham so, 3 constructor telescoping moi de khong pha call site cu). WinConditionEvaluator
  (scoring/, pure function, khong dung RoomState/actor) - progressCompleted()/firstToFinish() (predicate
  giong nhau nhung tach ten ro cho tung counter)/highestScorers()/singleHighestScorer() (rong khi
  hoa, khong tu chon bua 1 nguoi thang). RoomState: applyCooperativeOutcome doi sang goi
  WinConditionEvaluator that thay vi check inline; them applyTeamOutcome (teamProgress map moi,
  serialize/restore Hot Snapshot them field, additive o cuoi) cho first_to_finish; endGame() sua
  lai - CHI tinh nguoi thang moi khi phase CHUA FINISHED (khong ghi de win condition da chot truoc
  do), tinh most_points_when_time_up qua WinConditionEvaluator.singleHighestScorer tren diem tung
  doi. Them buildGameOver() dung chung cho moi mode.
  Phat hien phu quan trong: GameOver CHUA TUNG duoc gui o BAT KY dau trong toan bo codebase (ke
  ca Phase 1 SOLO) - ton tai trong schema tu Task 1 nhung khong actor nao build/broadcast. Sua
  luon (khong phai scope creep, day la bug that lo ra khi dung Task 23): RoomActor.onEndGame
  broadcast GameOver truoc khi dung (CRITICAL, giong STUDENT_KICKED); onSubmitAnswer them nhanh
  moi - neu submission vua lam FSM tu chuyen FINISHED (progress_completed/first_to_finish) thi
  CUNG broadcast GameOver + dung actor luon (truoc day nhanh tu-ket-thuc nay KHONG dung actor,
  ro ri giong dung bug 'RoomSupervisor khong don phong' da sua o review pass P1). Proto them
  GameOver.winner_id (field 3, additive).
  Khong tao PenaltyCalculator.java rieng (khac ten file trong plan.md goc) - chi co DUNG 1 luat
  phat da implement (TIME, 1 dong code tu Task 21), tach class rieng luc nay la abstraction chua
  co ly do ton tai.
  Test moi: WinConditionEvaluatorTest (9/9, pure logic) + RoomStateWinConditionTest (7/7, ca 3
  win condition + test rieng 'khong ghi de reason') + RoomActorTest (+1: GameOver broadcast that
  qua EndGame, dung TestInbox lam broadcastTarget) + DefinitionLoaderTest (26/26, +3 case
  win_condition hop le/khong hop le theo mode). Prove-it: tam bo guard 'khong ghi de neu da
  FINISHED' trong endGame(), xac nhan dung 1/7 test Red truoc khi tra lai Green. mvn clean install
  toan reactor: BUILD SUCCESS - 5 protocol + 86 gateway + 168 engine (148 cu + 20 moi) + 2 e2e,
  khong regress.
  Chua lam (co chu y, ghi trong WinCondition.MOST_POINTS_WHEN_TIME_UP javadoc): trigger tu dong
  'het gio thi tu ket thuc' - khong co co che timer/deadline nao trong codebase nay tu truoc gio
  (giong het gap TeacherCommand.NEXT_STEP da ghi tu Task 11). Logic DANH GIA ai thang da dung, chi
  thieu cai TRIGGER; hom nay chi TeacherCommand.END_GAME (thu cong) kich hoat duoc nhanh nay.
  shared_resource=LIVES van bi DefinitionLoader tu choi - PO V2.1 khong dac ta so luong lives ban
  dau, doan mot con so la tu bia quyet dinh nghiep vu (dung tinh than G1a/G1c).
  2026-09-09 (tiep, cung phien): bat dau Task 24, dung lai o buoc doc code that (khong code) theo
  quyet dinh nguoi dung. Nguoi dung dua duong dan cuc bo toi source that cua lms-worker
  (D:\Educa\k12-backend-java\k12-lms-service\lms-worker\...\listener\event\group_discussion\) -
  doc 4 listener (SubmitExerciseListener, ActiveGroupDiscussionListener, VoteGroupNameListener,
  ChatDiscussionRankListener) + 3 DTO (TeamScoreDto, GroupMessageDto, VoteInput) +
  ResultExerciseWorker. Phat hien 3 gia dinh SAI trong BDD/mo ta Task 24 goc: (1) topic that la
  team-submit-exercise-response (SubmitExerciseListener) va save-message-queue (3 listener con
  lai), khong phai game.events.v1; (2) format la JSON thuan (ObjectMapper.readValue(data,
  TeamScoreDto.class)/GroupMessageDto.class), khong co duong Protobuf nao; (3) GameEventPublisher/
  KafkaGameEventSink hien tai (Task 18) chi gui protobuf bytes sang game.events.v1 - khac hoan
  toan topic+format can, khong tai dung thang duoc.
  Gap chinh chan code: TeamScoreDto that doi profile_id/exercise_id/classroom_id/session_parent_id
  (so nguyen, domain LMS/CMS cu) - JoinTokenClaims (uni-websocket-gateway/.../auth/) chi co
  studentId/roomId/sessionId (String), khong co ID so nguyen LMS nao. VoteGroupNameListener con
  doi them teamNameId (chon giua nhieu ten de xuat san) - tinh nang nay khong ton tai trong
  TeamAssignment.team_name hien tai (1 ten co dinh/doi, khong co co che de xuat+vote).
  Hoi nguoi dung 3 lua chon (chi build phan publish de trong ID LMS / dung lai chi ghi nhan /
  nguoi dung cung cap them thong tin nguon ID) - nguoi dung chon 'dung lai chi ghi nhan phat
  hien'. KHONG viet code nao cho Task 24 luc nay - chi cap nhat plan.md + _context.md voi day du
  phat hien that de nguoi lam viec sau co co so chinh xac.
  2026-09-09 (tiep, cung phien): Task 25 (Unit Tests & RoomActor FSM Tests) xong. Phan lon
  coverage da co san tu Task 21-23 (TDD ngay luc code, khong phai lam rieng sau). Task 25 lap 2
  khoang trong con lai: (1) RoomActor.create() them 1 overload master constructor moi (P2 Task 25)
  mang toan bo config cooperative/team (gameMode/progressTarget/progressStages/sharedResourceType/
  sharedResourcePenalty/teamRosters/scoreAggregation/winCondition) vao actor - additive-overload
  dung chuoi da co (Task 14/17/18); RoomSupervisor.spawnRoom() VAN KHONG goi overload nay (van
  thieu nguon GameDefinition that, gap Task 11 chua doi) - overload nay hien chi phuc vu test,
  giong het vi tri missedStepPolicy tung o giua Task 14 va 17. (2) CooperativeRoomActorTest (3
  case: auto-FINISHED + GameOver broadcast + actor tu dung; khong finish khi progress chua du;
  stage_index dung qua full snapshot luc join) + TeamRoomActorTest (3 case: JoinRoom reply mang
  dung team_id/roster; GameOver.winner_id dung khi first_to_finish; UpdateDraft fan-out scoped qua
  actor that) - dung BehaviorTestKit, xac nhan qua RoomActor that thay vi chi RoomState don le.
  Prove-it that (khong phai test gia): TeamRoomActorTest's fan-out test ban dau Red vi ly do DUNG -
  hanh vi tu nhien cua RoomActor (flush ngay lap tuc khi now - lastFlushAtMs >= 200ms, do
  Clock.fixed nam o nam 2026 con lastFlushAtMs khoi tao 0), khong phai bug - sua assertion loc
  dung loai message thay vi dem tong so trong broadcastInbox. Them 1 test bien:
  RoomStateTeamModeTest.should_notAdvanceAnyTeamProgress_when_correctAnswerComesFromAStudent
  NotOnAnyRoster (nhanh phong thu applyTeamOutcome's teamId.isEmpty(), khac nhanh tuong tu da test
  o updateDraft). mvn clean install toan reactor: BUILD SUCCESS - 5 protocol + 86 gateway + 175
  engine (168 cu + 7 moi) + 2 e2e, khong regress.
  2026-09-09 (tiep, cung phien): Task 26 (E2E) xong. Nguoi dung chon 'viet theo dung convention
  JUnit5 hien co cua repo' thay vi them Cucumber (repo nay khong co Cucumber o dau ca - da kiem
  tra truoc: WalkingSkeletonTest/DockerComposeChaosIT deu la JUnit5 thuan). Them
  RoomSupervisor.SpawnConfiguredRoom (Command moi, cung tinh than test/ops-only voi GetRoomActor
  da co) - spawn phong voi config cooperative/team TRUOC khi client nao join, dong gap
  'RoomSupervisor.spawnRoom() duong join that chi spawn duoc SOLO' (gap Task 11) chi rieng cho
  E2E test, khong dong gap that o production. InclassGroupGameE2ETest (uni-e2e, moi) - 2 test,
  12 client WebSocket that/moi test, dung dung convention WalkingSkeletonTest (FakeJoinTokenVerifier,
  GetRoomActor cho noi dung cau hoi). Cover 2/3 kich ban BDD that: cooperative-boss (progress
  30%/70%/100%, GameOver toi ca nguoi chua tung tra loi) + team-speed-race (UPDATE_DRAFT scoped
  dung doi, first_to_finish + GameOver.winner_id toi CA 4 doi). Khong cover duoc
  lms-worker-sync.feature (Task 24 van dung). Debug 1 lan Red khong xac dinh (khong phai loi
  logic - ca 2 ANSWER_ACK deu accepted=true khi kiem tra) - la race dieu kien thoi gian trong
  CHINH test (gui cau tra loi thu 2 khong doi ACK cau 1 truoc), sua bang cach doi ANSWER_ACK moi
  lan nop bai truoc khi tiep tuc - chay lai 3/3 lan lien tiep deu xanh. mvn clean install toan
  reactor: BUILD SUCCESS - 5 protocol + 86 gateway + 175 engine + 4 e2e (2 cu + 2 moi).
  Phat hien ngoai pham vi (da bao nguoi dung, nguoi dung xac nhan bo qua): mot tien trinh khong
  ro nguon goc dang xoa javadoc khoi RAT NHIEU file .java tren dia (73 file bi doi, gom ca file
  Task 1-18 khong he dung toi trong phien nay) - khong phai do git (da loai tru filter/hook).
  Ban da commit truoc do van nguyen ven, nhung commit Task 26 vo tinh chot lai 1 ban RoomActor.java
  da mat mot phan javadoc cu (chi kiem tra phan MOI truoc khi commit, khong soat toan file).
  Nguoi dung xac nhan khong can khoi phuc. Toan bo Phase 2 (Task 20-23, 25, 26) da XONG. Task 24
  van dung o buoc ghi nhan phat hien, cho quyet dinh nguon ID LMS.
  2026-09-09 (tiep, phien khac - P1 Task 22 LZ4): xem docs/work/NOJIRA-uni-p1-realtime-core/plan.md,
  khong thuoc Phase 2 nay.
  2026-09-09 (tiep): PO chot HUY han Task 24 (tich hop lms-worker) khoi pham vi - khong con la
  'tam dung cho nguon ID' nua. Xoa docs/specs/bdd/INCLASS-GAME-003-lms-worker-sync.feature (toan
  bo file). Sua cross-reference o: plan.md (xoa muc 4 scope, xoa risk row 'lech diem lms-worker',
  thay nguyen phan Task 24 bang 1 doan ngan noi HUY, sua dong Task 26 nhac feature -003), _context.md
  nay (link BDD -003 gach bo, khoi Task 24 rut gon, dong Task 26, dong tong ket Phase 2),
  system-architecture.md SS10 (xoa row bang 'Tuong thich He thong Cu lms-worker', xoa cau Kafka/
  lms-worker cuoi kich ban BDD 2, xoa buoc 'Worker Integration' trong workflow dieu SS10.3),
  INCLASS-GAME-001-inclass-group-game-tech-design.md (xoa bullet + row lms-worker), docs/specs/
  modules/engine/INCLASS-GAME-001-inclass-group-cooperative-games.md (xoa muc 4 yeu cau + link BDD
  -003, xoa row 'Tich hop LMS cu' o SS5 phat hien them sau), modules/uni-e2e/.../InclassGroupGameE2ETest.java
  (sua javadoc, khong nhac feature da xoa nua). KHONG dung code nao trong modules/ tham chieu
  lms-worker truoc do (da grep xac nhan tu luc Task 24 dung lai - chi co 1 dong comment trong
  InclassGroupGameE2ETest, da sua). Khong dung file PO_Require...doc goc (van la tai lieu nguon PO
  cung cap, khong tu sua/xoa).
  2026-09-09 (tiep, sau khi sua project-context.yaml + chuan bi push): phat hien moi truong nghiem
  trong hon nhieu so voi lan truoc - tien trinh nen dang XOA/TRUNCATE noi dung file that (khong chi
  javadoc), vi du EdTech_Game_Realtime_Architecture_v3.0.md 1540->318 dong, note.md 806->43 dong, va
  anh huong ca file vua sua xong (project-context.yaml bi cat vai giay sau edit). Dung lai truoc khi
  push, KHONG dung git checkout hang loat (bi auto-mode classifier chan). Nguoi dung xac nhan dung
  cach khac: git show HEAD:<path> (read-only) lay noi dung sach + tu tay ap lai dung cac edit that
  su cua minh roi Write de. Chi tiet day du o docs/work/NOJIRA-uni-p1-realtime-core/_context.md
  (Task 22's state block), vi day la van de xuyen suot ca 2 work package, khong rieng P2.
  2026-09-09 (phien khac, sau khi P1's RoomActor/RoomState bi refactor DDD lon boi nguoi dung):
  code-review yeu cau danh gia tuong thich voi INCLASS-GAME-001-v2.1.md. Phat hien
  RoomStateProtobufMapper.buildFullSnapshot()/computeStageIndex() dung Math.round thay vi
  Math.floor (khac luat SS5.6 rule 6) - ban dau tuong la bug production that, nhung grep xac nhan
  0 noi goi 2 method nay (RoomState.flush() dung dung ban rieng cua RoomState, da floor dung tu
  Task 21) - la dead code sot lai tu dot tach file DDD, khong phai bug hanh vi. Da xoa 2 method +
  import thua khoi RoomStateProtobufMapper.java. Rieng luat SS5.6 rule 5 (GV khong duoc huy giua
  van) KHONG sua bang code - doc lai Task 23 xac nhan END_GAME luc PLAYING la co che trigger DUY
  NHAT cho MOST_POINTS_WHEN_TIME_UP (chua co auto-trigger), va test
  RoomStateWinConditionTest.should_computeHighestScoringTeam_whenEndGameFiresUnderMostPointsWhenTimeUp
  goi endGame() giua van truoc progress_target ma khong doi deadlineMs troi qua - 1 guard chan
  EndGame luc PLAYING se pha chinh test nay. Ghi lai thanh cau hoi nghiep vu can PO quyet dinh
  (giong G1a/G1c/LIVES), khong tu doan ranh gioi 'huy' vs 'bao het gio'. mvn -pl :uni-game-engine
  test: 175/175 pass, khong regress. Chi tiet: plan.md Task 27.
  2026-09-10 (tiep, nguoi dung yeu cau cap nhat Task 23): xac nhan 2 muc 'chua lam' cu cua Task 23
  (trigger tu dong most_points_when_time_up + shared_resource=LIVES) va muc 'chua sua' cua Task 27
  (luat bien 5 - GV khong duoc huy giua van) THUC RA la CUNG 1 cau hoi nghiep vu goc, khong phai 3
  gap doc lap: TeacherCommand.END_GAME luc PLAYING dang la co che DUY NHAT cho
  most_points_when_time_up, nen bat ky guard nao chan EndGame de tuan luat bien 5 se vo hieu hoa
  luon con duong do. Doc lai INCLASS-GAME-001-v2.1.md SS3-SS5 lan nua de kiem tra co thong tin moi
  khong - KHONG co (khong co field 'tong thoi gian van', khong co dinh nghia 'het gio' cho
  most_points_when_time_up ngoai 'GV bam Ket thuc'). Khong tu doan, khong code them - Task 23 giu
  nguyen 'PHAN LON XONG', chua doi thanh 'XONG'. Da ghi cross-reference 2 chieu giua Task 23 va
  Task 27 trong plan.md.
  2026-09-10 (tiep): nguoi dung hoi v2.1 co gi moi so voi v1 khong. Thay vi tin bang tom tat co san
  trong file .md, decode 2 file .doc goc that (hoa ra la Confluence export dang MIME/HTML, khong
  phai Word binary that - dung antiword phat hien, roi tu decode quoted-printable bang Python) va
  diff truc tiep. Phat hien bang so sanh V1.0-vs-V2.1 trong INCLASS-GAME-001-v2.1.md SAI 4/6 dong
  (Input Schema Group A/B, progress_display_mode, tach Mode/Mechanic, phat shared_resource - ca 4
  da co nguyen van tu V1.0, khong phai diem moi that su). Da sua lai bang do. Diem MOI that su xac
  nhan qua diff: SS5.2 game loop/FSM, SS5.3 dieu kien thang thua (PO TU FLAG luat 'Hoa' chua chot -
  khac cac gap khac vi khong can doan y PO, PO da ghi ro day la diem treo), SS5.4 cach tinh diem
  (them streak_bonus nhung KHONG co trong bang schema chinh thuc SS7 - PO tu mau thuan trong chinh
  tai lieu), SS5.5 luat tuong tac, SS5.6 6 luat bien (dung nhu bang cu da ghi), SS9 'Chi so do
  luong' moi nhung de trong. Xac nhan SS7 Input Schema giong het 100% giua 2 ban - khong co gap
  schema moi nao ngoai backlog da ghi tu truoc. Da ghi 2 gap moi (streak_bonus, luat Hoa) vao
  plan.md Task 27.
  2026-09-11: Task 28 (moi, CHUA lam - chi moi ghi nhan phat hien + pham vi). Doi chieu truc tiep
  PO_Require V2.2 (§4 - Luong Van Hanh Server FSM, file da git-add tu truoc nhung chua tung duoc
  diff/doi chieu code) voi RoomActor/RoomState/RoomSupervisor that: PO V2.2 chot FSM moi
  WAITING_FOR_PLAYERS -> RULES_DISPLAY (state MOI) -> IN_PROGRESS (tu dong, khong cho GV bam them)
  -> ENDED, va noi ro 'Engine tu dong chuyen IN_PROGRESS & phat cau hoi 1 (khong cho GV bam
  them)' - khac V2.1 chi ghi 'Bat dau game do GV bam' (im lang ve viec co tu ban cau hoi 1 hay
  khong, la goc gap Task 11 cu). Doi chieu code xac nhan 3 lo hong: (1) FSM hien tai khong co
  RULES_DISPLAY (chi LOBBY/PLAYING/FINISHED, khac ten PO dung); (2) TeacherCommand.START_GAME chi
  doi phase (RoomState.startGame(), 1 dong), khong tu ban cau hoi nao; (3) TeacherCommand.NEXT_STEP
  hoan toan CHUA noi day - roi vao nhanh default -> log.warn trong
  RoomSupervisor.dispatchTeacherCommand(); (4) RoomActor.StartQuestion (command that su
  build+broadcast QUESTION_STARTED) chi duoc goi tu test code (grep xac nhan 8 file test, 0 noi
  goi tu duong dispatch production that). Cau hoi PO CHUA tra loi (khong tu doan): tu cau hoi 2 tro
  di co tu dong chuyen theo round_time_limit het han hay van can GV bam NEXT_STEP thu cong - PO V2.2
  §4 khong noi ro. Chi tiet day du + phan viec can lam: plan.md Task 28.
  2026-09-11 (tiep, cung phien - doc het V2.2): them Task 29-32 (deu MOI, CHUA lam) sau khi doi
  chieu toan bo PO V2.2 SS3/SS5 voi code that (audit that, khong suy doan). Task 29: Input Schema
  Group A/B - GameDefinition hien KHONG co max_players/late_join_policy/team_assignment/
  team_names/team_colors/questions/round_time_limit/intro_narrative/progress_display_mode/
  progress_stages; ScoreAggregation enum chi co SUM_ALL/AVERAGE, thieu FIRST_CORRECT_ONLY; gap
  'questions' chinh la gap nguon du lieu cau hoi ghi tu Task 11 (P1), PO V2.2 da tra loi PHAN
  'cau hoi o dau ra' (Group A, hoc thuat tu nhap), chi con thieu code doc field do. Task 30:
  tie-break theo tong thoi gian tra loi (nguoi tra loi DAU TIEN neu cau co >=2 nguoi tra loi) -
  dong luon gap 'luat Hoa chua chot' da ghi tu Task 27; WinConditionEvaluator hien chua track
  response_time nao theo team. Task 31: nguong tham gia >=50% moi duoc cong tien trinh/diem Phe
  (khac V2.1 chi can 1 nguoi dung) - TeamModeRules.applyAnswerOutcome hien KHONG check ti le tham
  gia; can PO/BA xac nhan thoi diem CHECK nguong (de xuat: chot luc cau hoi ket thuc, khong tinh
  real-time giua chung) truoc khi code. Task 32: cup thuong cuoi tran (1 diem = 1 cup, lam tron
  LEN) - grep toan bo uni-game-engine + uni-protocol xac nhan 0 ket qua 'trophy/cup/cup' - tinh
  nang HOAN TOAN chua ton tai, can them field protobuf moi (additive, dung ADR-1). Con 4 cau hoi
  PO van treo, khong tu doan: (1) luat bien 5 'cam huy giua van' V2.2 KHONG giai quyet, lap
  nguyen van V2.1, van mau thuan voi END_GAME la trigger duy nhat cho most_points_when_time_up;
  (2) diem san toi thieu cong thuc speed_based chi la 'vi du: 2 diem' chua chot so that; (3) so
  luong lives ban dau van chua co (gap cu Task 23); (4) cau hoi 2 tro di tu dong hay can GV bam
  NEXT_STEP thu cong (Task 28). streak_bonus (gap cu Task 27) KHONG con xuat hien trong V2.2 -
  coi nhu PO da rut yeu cau, khong can task rieng nua. Chi tiet day du: plan.md Task 29-32 + muc
  'Cau hoi PO con treo'."
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-11"
```
