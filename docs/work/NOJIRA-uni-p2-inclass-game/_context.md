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
- **2026-09-09: Task 20 (schema protobuf) + Task 21 (cooperative mode) + Task 22 (team mode + scoped draft sync) đã XONG**, verify bằng `mvn clean install` toàn reactor (BUILD SUCCESS) — xem `plan.md` cho chi tiết + quyết định thiết kế. `GAME_MODE_COOPERATIVE` và `GAME_MODE_TEAM` đều chạy được end-to-end trong `RoomState`; `UPDATE_DRAFT` đã wire thật qua `RoomActor`/`RoomSupervisor` (không chỉ dừng ở đơn vị `RoomState`). `GAME_MODE_INDIVIDUAL` và `shared_resource=LIVES` vẫn bị `DefinitionLoader` từ chối có chủ đích (chưa implement, ngoài phạm vi Task 21/22).
- **Gap đã biết, không phải thiếu sót của Task 21/22:** `RoomSupervisor.spawnRoom()` (đường join thật) vẫn CHƯA có nguồn `GameDefinition` nào để spawn phòng ở chế độ COOPERATIVE/TEAM — không có định dạng "game-definition-authoring" nào được chốt trong repo này (đã ghi nhận từ Task 11, plan.md P1). Toàn bộ logic Task 21/22 đã test kỹ ở tầng `RoomState`/`RoomActor`/`RoomSupervisor` (đơn vị + dispatch), nhưng chưa test được qua đường join thật end-to-end (Docker/WalkingSkeletonTest) vì gap này — giống hệt tình trạng `missedStepPolicy` từng trải qua giữa Task 11 và Task 17.
- Tiếp theo: Task 23 (Win Condition Evaluator đầy đủ cho `first_to_finish`/`most_points_when_time_up` + `PenaltyCalculator` cho `shared_resource=LIVES`).

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
  that - ke thua dung gap da ghi nhan tu Task 11 (P1), khong phai rieng Task 21/22. Tiep theo:
  Task 23 (WinConditionEvaluator + PenaltyCalculator cho shared_resource=LIVES)."
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-09"
```
