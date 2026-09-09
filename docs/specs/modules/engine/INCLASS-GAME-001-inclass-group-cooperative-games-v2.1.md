# Product Brief & Specification: In-class Group & Cooperative Games (Version 2.1)

> **Artifact ID:** `INCLASS-GAME-001-V2.1`  
> **Layer:** Product Brief & Business Specification (Tài liệu Nghiệp vụ Giai đoạn 2)  
> **Nguồn gốc:** [`PO_Require_Game+nhóm_+tập+thể+Inclass V2.1.doc`](./PO_Require_Game+nhóm_+tập+thể+Inclass%20V2.1.doc) (Ngày cập nhật: 09/09/2026)  
> **Tài liệu tiền nhiệm:** [`INCLASS-GAME-001-inclass-group-cooperative-games.md`](./INCLASS-GAME-001-inclass-group-cooperative-games.md) (Bản V1.0)  

---

## 📊 Bảng So Sánh Tổng Quan V1.0 vs V2.1

| Tiêu chí | Bản V1.0 | Bản V2.1 (Mới nhất) | Tác động kỹ thuật Backend (Engine/Protocol) |
|---|---|---|---|
| **Mô hình Input Schema** | Sơ khai, chưa phân loại trường cấu hình | Tách bạch **Group A** (Form học thuật điền) & **Group B** (LLM tự sinh) | Thêm enum & message Protobuf cho Group A & Group B (§5) |
| **Kiểu hiển thị Tiến trình** | Thanh % đơn giản | Thêm `progress_display_mode`: `simple_bar` hoặc `staged_visual` | `RoomState` quản lý chuyển đổi mốc ảnh SVG khi % vượt mốc |
| **Phân định Nguyên tắc** | Trộn lẫn chế độ và luật chơi | Tách rõ **Mode** (cấu trúc phe) và **Mechanic** (luật chơi) | Giúp Engine mở rộng thêm Mechanic mới sau này không sửa Mode |
| **Server Game Loop & FSM** | Chưa quy định FSM cụ thể | Chuẩn hoá 4 bước FSM (`INIT` → `WAITING` → `IN_PROGRESS` → `ENDED`) | Khớp hoàn toàn với Pekko `RoomActor` FSM của Phase 1 |
| **Luật Biên (Edge Cases)** | Chưa đề cập | Bổ sung 6 luật biên chi tiết (§6) | Đổi đáp án draft, Hard disconnect, Cấm GV hủy giữa ván, Floor % |
| **Tài nguyên phạt** | Phạt chung | Phạt `shared_resource` (`time`/`lives`) khi trả lời sai (`cooperative`) | `RoomActor` giảm thời gian hoặc mạng của cả phòng khi sai |

---

## 1. Bối cảnh, Tâm lý Học sinh & Mục tiêu (Mục 1 PO V2.1)

### 1.1. Bối cảnh & Tâm lý Học sinh
- **Vấn đề:** Công cụ hiện tại chỉ tạo game cho 1 học sinh chơi solo. Lớp học online thiếu tương tác xã hội (học sinh không ngồi cạnh nhau).
- **Giải pháp tâm lý:** Game nhóm tạo cảm giác **thuộc về tập thể** (belongingness). Khi trả lời sai, áp lực được san sẻ cho cả nhóm giúp học sinh yếu/nhút nhát dám tham gia nhiều hơn. Đồng lực nhóm ("không muốn làm đội thua") duy trì tập trung lâu hơn.

### 1.2. Mục tiêu Nâng cấp
Hỗ trợ 3 chế độ chơi (**Game Mode**):
1. **`cooperative` (Tập thể):** Cả lớp 12 học sinh cùng chơi 1 game (cùng đánh Boss, cùng vượt chướng ngại vật).
2. **`team` (Chia nhóm):** Chia lớp thành $X$ nhóm ($2–4$ nhóm) thi đấu với nhau.
3. **`individual` (Cá nhân mở rộng):** Từng học sinh thi đấu trực tiếp nhưng gắn với tài nguyên/mốc tiến trình chung.

Mechanic ban đầu duy nhất: **`progress_meter`** (Thanh tiến trình chung — con số tăng dần tới đích), thể hiện qua 3 themes nội dung: Đánh Boss, Xây công trình, Vượt chướng ngại vật.

---

## 2. Nguyên Tắc Thiết Kế Cốt Lõi (Mục 4 PO V2.1)

1. **Mechanic (cơ chế) tách khỏi Content (nội dung):**
   - **Mechanic:** Luật vận hành, tính điểm, FSM. Dev lập trình **1 lần**, có tham số.
   - **Content:** Chủ đề, câu hỏi, hình ảnh SVG. LLM sinh vô hạn nạp vào Schema cố định mà **không đụng tới code dev**.
2. **Mode (chế độ) tách khỏi Mechanic:**
   - **Mode** quyết định *cấu trúc phe* (`cooperative` = 1 phe; `team` = $\ge 2$ phe; `individual` = 12 phe).
   - **Mechanic** quyết định *luật tương tác giữa các phe*.

---

## 3. Chi Tiết Input Schema (Mục 7 PO V2.1)

### 3.1. Group A — Đội Học thuật chọn trên Form
| Trường | Kiểu / Giá trị | Áp dụng Mode | Mục đích / Hành vi |
|---|---|---|---|
| `game_mode` | `solo` \| `cooperative` \| `team` \| `individual` | Tất cả | Xác định cấu trúc phe trong phòng |
| `game_mechanic` | `progress_meter` | Tất cả | Cơ chế tính tiến trình (mặc định bản V2.1) |
| `max_players` | Integer (vd: 12) | Tất cả | Sức chứa tối đa của phòng |
| `shared_resource` | `time` \| `lives` \| `none` | `cooperative` | Loại tài nguyên bị trừ khi trả lời sai |
| `team_count` | Integer (2–4) | `team` | Số lượng nhóm chia trong phòng |
| `team_assignment` | `random` \| `manual` | `team` | Cách thức phân học sinh vào nhóm |
| `team_names` / `colors` | Array[String] | `team` | Tên và mã màu hiển thị của từng nhóm |
| `score_aggregation` | `sum_all` \| `first_correct_only` \| `majority_vote` | `team` | Cách gộp điểm số cho nhóm |
| `scoring_rule` | `fixed` (100đ) \| `speed_based` | `team`, `individual` | Quy tắc tính điểm câu trả lời |
| `round_time_limit` | Integer (giây) | Tất cả | Thời gian tối đa cho 1 câu hỏi |
| `late_join_policy` | `allow_with_zero_score` \| `block_after_start` | Tất cả | Chính sách cho học sinh vào sau |

### 3.2. Group B — LLM Tự Sinh theo Schema Tĩnh
| Trường | Kiểu / Giá trị | Engine sử dụng để... |
|---|---|---|
| `progress_target` | Integer | Làm mẫu số tính `% tiến trình = (số câu đúng / progress_target) * 100` |
| `progress_display_mode` | `simple_bar` \| `staged_visual` | `simple_bar` → vẽ thanh % đơn giản; `staged_visual` → đổi ảnh SVG theo mốc |
| `progress_stages` | List `{ milestone_pct, svg_url }` | Engine so % hiện tại với mốc, tự đổi ảnh SVG tương ứng trên màn hình HS |
| `win_condition` | `progress_completed` \| `first_to_finish` \| `most_points_when_time_up` | Xác định điều kiện kết thúc game và tìm phe thắng cuộc |

---

## 4. Luồng Vận Hành Server FSM (Mục 5.2 PO V2.1)

```text
  [INIT]
    │  • Nạp config Group A + Group B theo game_id.
    │  • Khởi tạo shared_resource, progress_target, question_index = 1.
    ▼
  [WAITING_FOR_PLAYERS]
    │  • Học sinh bấm "Vào chơi" → thêm vào danh sách, gán team_id / student_index.
    │  • Late join: xử lý theo late_join_policy.
    ▼
  [IN_PROGRESS]
    │  • Phát câu hỏi, đếm ngược round_time_limit.
    │  • Học sinh nộp / sửa đáp án nháp (UPDATE_DRAFT).
    │  • Trả lời ĐÚNG  ──► +1 tiến trình, Engine tự tính %, cập nhật stage SVG nếu có.
    │  • Trả lời SAI   ──► Trừ shared_resource (time/lives) nếu được cấu hình.
    ▼
  [ENDED]
       • Kích hoạt khi thỏa mãn win_condition.
       • Chốt điểm cuối cùng, broadcast kết quả, đẩy sự kiện Kafka kết quả.
```

---

## 5. Các Luật Biên Chi Tiết (Mục 5.6 PO V2.1)

1. **Thứ tự câu hỏi:** Cố định theo thứ tự đội học thuật nhập.
2. **Dạng câu hỏi:** Mặc định trắc nghiệm (Multiple Choice 1/4).
3. **Đổi đáp án (`UPDATE_DRAFT`):** Học sinh được phép thay đổi lựa chọn trước khi hết `round_time_limit`. Engine xử lý draft sync real-time trong cùng `team_id`.
4. **Hard Disconnect (Thoát giữa chừng):** Tính là bỏ cuộc, **không ảnh hưởng** tới điểm số và tiến trình hiện tại của đội.
5. **Cấm hủy giữa ván:** Giáo viên không được phép hủy ván giữa chừng khi game đang chạy.
6. **Làm tròn % tiến trình:** Làm tròn xuống số nguyên (`floor`), ví dụ 33.33% → 33%.
7. **Không có LLM khi đang chơi:** LLM chỉ sinh cấu hình tĩnh trước đó; toàn bộ logic real-time, tính %, đổi ảnh stage SVG, win condition do **Engine tự chạy 100% trên Server**.

---

## 6. Thành Phần Hạ Tầng Cần Triển Khai (Mục 6 PO V2.1)

1. **Game Engine (Backend - `uni-game-engine`):** Đọc Input Schema Group A/B + nội dung LLM, chạy mechanic FSM, tính điểm server-authoritative, broadcast WebSocket real-time.
2. **CMS (gán Game ↔ Lớp học):** Lưu liên kết `class_id ↔ game_id` để Teacher Dashboard đọc game đúng lớp.
3. **Teacher Dashboard (Điều khiển):** Tự hiển thị game đã gán cho lớp; Giáo viên bấm Bắt đầu, Chuyển câu, Xem tiến độ, Kết thúc.
4. **Student UI (Giao diện học sinh nhúng):** Màn hình chung (tiến trình, điểm nhóm, XH) + màn hình riêng (câu hỏi, nút chọn, điểm cá nhân).
