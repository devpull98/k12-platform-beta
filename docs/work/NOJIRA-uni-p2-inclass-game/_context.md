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
- **2026-09-09 (phiên khác, sau refactor DDD lớn trên P1's `RoomActor`/`RoomState`): Task 27 (đối chiếu Product Brief V2.1) mở, một phần xong.** Review phát hiện `RoomStateProtobufMapper.buildFullSnapshot()` là dead code lệch `Math.floor` (bug tưởng là thật lúc đầu, xác nhận lại là code thừa 0 người gọi, không phải hành vi production) — đã xoá. Luật biên §5.6 rule 5 ("GV không hủy giữa ván") **không sửa được bằng code** — mâu thuẫn trực tiếp với thiết kế đã chốt ở Task 23 (`END_GAME` lúc `PLAYING` là trigger duy nhất cho `MOST_POINTS_WHEN_TIME_UP`, chưa có auto-trigger) — cần PO quyết định ranh giới "hủy" vs "báo hết giờ", giống style G1a/G1c. Khoảng trống schema Group A/B (`max_players`, `team_count`, `team_assignment`, `late_join_policy`, `scoring_rule=speed_based`, `progress_display_mode`, `score_aggregation` đủ giá trị) vẫn treo, cần task CMS riêng. Chi tiết: `plan.md` Task 27.

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
  Task 27 trong plan.md."
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-09"
```
