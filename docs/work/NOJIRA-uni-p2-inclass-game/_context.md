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
- **2026-09-09: Task 20 (schema protobuf) + Task 21 (GameDefinition/RoomState cooperative mode) đã XONG**, verify bằng `mvn clean install` toàn reactor (BUILD SUCCESS) — xem `plan.md` cho chi tiết + quyết định thiết kế. `GAME_MODE_COOPERATIVE` chạy được end-to-end trong `RoomState` (progress tracking, stage visual, shared_resource TIME penalty, tự chuyển FINISHED khi đạt `progress_target`). `GAME_MODE_TEAM`/`INDIVIDUAL` và `shared_resource=LIVES` bị `DefinitionLoader` từ chối có chủ đích (chưa implement) — tiếp theo là Task 22 (Team mode + scoped draft sync).
- Tiếp theo: Task 22 (chia nhóm + `UPDATE_DRAFT` scoped fan-out theo `team_id`).

## State (machine-readable)
```yaml
phase: dev
track: feature
last_skill: tdd
next_skill: tdd
progress: "2026-09-09: Task 20+21 xong, verify that (mvn clean install toan reactor BUILD SUCCESS,
  134 test uni-game-engine + 7 test uni-protocol moi/doi). Task 22-26 chua lam."
dev_selftest: pending
qc_status: pending
trace: pending
updated: "2026-09-09"
```
