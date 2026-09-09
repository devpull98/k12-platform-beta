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
  - [`docs/specs/bdd/INCLASS-GAME-003-lms-worker-sync.feature`](../../specs/bdd/INCLASS-GAME-003-lms-worker-sync.feature)
- **Implementation Plan:** [`plan.md`](./plan.md)

---

## Trạng thái hiện tại
- Đã chuẩn hoá tài liệu Product Brief V2.1 kèm bảng so sánh V1.0 vs V2.1.
- **2026-09-09: Task 20 (schema) + Task 21 (cooperative) + Task 22 (team + scoped draft sync) + Task 23 (win condition evaluator, phần lớn) đã XONG**, verify bằng `mvn clean install` toàn reactor (BUILD SUCCESS) — xem `plan.md` cho chi tiết + quyết định thiết kế. `GAME_MODE_COOPERATIVE` và `GAME_MODE_TEAM` đều chạy được end-to-end trong `RoomState` kể cả FSM tự chuyển `FINISHED` + broadcast `GameOver` thật (trước đây `GameOver` chưa từng được gửi ở BẤT KỲ đâu trong codebase, kể cả Phase 1 SOLO — phát hiện phụ khi làm Task 23, đã sửa luôn). `GAME_MODE_INDIVIDUAL` và `shared_resource=LIVES` vẫn bị `DefinitionLoader` từ chối có chủ đích (chưa implement).
- **Gap đã biết, không phải thiếu sót của Task 21/22/23:** `RoomSupervisor.spawnRoom()` (đường join thật) vẫn CHƯA có nguồn `GameDefinition` nào để spawn phòng ở chế độ COOPERATIVE/TEAM — không có định dạng "game-definition-authoring" nào được chốt trong repo này (đã ghi nhận từ Task 11, plan.md P1). Toàn bộ logic đã test kỹ ở tầng `RoomState`/`RoomActor`/`RoomSupervisor` (đơn vị + dispatch), nhưng chưa test được qua đường join thật end-to-end (Docker/WalkingSkeletonTest) vì gap này.
- **Gap mới của riêng Task 23 (có chủ ý, ghi trong `WinCondition.MOST_POINTS_WHEN_TIME_UP`'s javadoc):** không có cơ chế timer/deadline nào tự động kết thúc game khi "hết giờ" — logic ĐÁNH GIÁ ai thắng đã đúng (`WinConditionEvaluator` + `RoomState.endGame()`), chỉ thiếu cái TRIGGER tự động; hôm nay chỉ `TeacherCommand.END_GAME` (thủ công) kích hoạt được. `shared_resource=LIVES` vẫn treo vì PO V2.1 không đặc tả số lives ban đầu.
- Tiếp theo: Task 24 (Kafka Event Publisher cho `lms-worker` — `TeamSubmitExerciseEvent`/`GroupDiscussionEvent`/`VoteGroupNameEvent`).

## State (machine-readable)
```yaml
phase: dev
track: feature
last_skill: tdd
next_skill: tdd
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
  dau, doan mot con so la tu bia quyet dinh nghiep vu (dung tinh than G1a/G1c). Tiep theo: Task 24
  (Kafka Event Publisher cho lms-worker)."
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-09"
```
