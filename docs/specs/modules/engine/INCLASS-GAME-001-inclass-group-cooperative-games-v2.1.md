# Product Brief & Specification: In-class Group & Cooperative Games (Version 2.1)

> **Artifact ID:** `INCLASS-GAME-001-V2.1`  
> **Layer:** Product Brief & Business Specification (Tài liệu Nghiệp vụ Giai đoạn 2)  
> **Nguồn gốc:** [`PO_Require_Game+nhóm_+tập+thể+Inclass V2.1.doc`](./PO_Require_Game+nhóm_+tập+thể+Inclass%20V2.1.doc) (Ngày cập nhật: 09/09/2026)  
> **Tài liệu tiền nhiệm:** [`INCLASS-GAME-001-inclass-group-cooperative-games.md`](./INCLASS-GAME-001-inclass-group-cooperative-games.md) (Bản V1.0)  

---

## 📊 Bảng So Sánh Tổng Quan V1.0 vs V2.1

> [!IMPORTANT]
> **Sửa lại 2026-09-10** — bảng dưới đây trước đó ghi sai 4/6 dòng: "Input Schema Group A/B",
> "`progress_display_mode`", "tách Mode/Mechanic", và "phạt `shared_resource`" **đã có nguyên văn
> từ V1.0** (`INCLASS-GAME-001-inclass-group-cooperative-games.md` §7/§4.2), không phải điểm mới
> của V2.1. Bảng này được viết lại sau khi diff trực tiếp 2 file `.doc` gốc
> (`PO_Require_Game+nhóm_+tập+thể+Inclass.doc` vs `...V2.1.doc`) thay vì suy đoán — §7 Input
> Schema **giống hệt 100%** giữa 2 bản, không đổi 1 chữ.

| Tiêu chí | Bản V1.0 | Bản V2.1 (Mới nhất) | Tác động kỹ thuật Backend (Engine/Protocol) |
|---|---|---|---|
| **Input Schema (§7), `progress_display_mode`, tách Mode/Mechanic (§4.2), `shared_resource`** | Đã có nguyên văn, giống hệt V2.1 | **Không đổi** — 0 khác biệt khi diff trực tiếp | Không có tác động mới nào riêng cho các mục này |
| **Server Game Loop & FSM (§5.2, mới)** | Chưa quy định FSM cụ thể | Chuẩn hoá: `INIT` → `WAITING_FOR_PLAYERS` → `IN_PROGRESS` → `ENDED`, có mô tả từng bước (kể cả nhánh hết câu hỏi mà chưa ngã ngũ) | Khớp hoàn toàn với Pekko `RoomActor` FSM của Phase 1 |
| **Điều kiện thắng thua (§5.3, mới)** | Chưa đề cập | Chi tiết theo từng mode + **PO tự flag 1 edge case CHƯA CHỐT**: "Hoà — 2 phe chạm đích cùng thời điểm server, hoặc bằng điểm khi hết giờ: cần luật phụ" | `WinConditionEvaluator.singleHighestScorer()` hiện trả rỗng khi hoà — **đúng tinh thần "không tự bịa" cho tới khi PO chốt luật hoà**, không phải thiếu sót |
| **Cách tính điểm (§5.4, mới)** | Chưa đề cập | 2 loại điểm song song (cá nhân vs tiến trình phe), đồng đội đúng → 50% điểm, quy đổi điểm→cúp, và **thêm `streak_bonus`** (đúng liên tiếp N câu → +% thưởng) | `streak_bonus` **KHÔNG có trong bảng schema chính thức §7** (`scoring_rule` ở đó vẫn chỉ ghi `fixed`/`speed_based`) — mâu thuẫn ngay trong tài liệu PO, xem `plan.md` Task 27 |
| **Cách chơi / luật tương tác (§5.5, mới)** | Chưa đề cập | Câu hỏi hiện đồng thời, không thấy đáp án người khác, tự chuyển câu khi hết giờ (GV vẫn có nút override thủ công) | Chưa có điểm gắn trong repo — `TeacherCommand.NEXT_STEP` vẫn là gap cũ từ Task 11 |
| **Luật Biên (§5.6, mới)** | Chưa đề cập | Bổ sung 6 luật biên chi tiết | Đổi đáp án draft, Hard disconnect, Cấm GV hủy giữa ván, Floor % |
| **"Chỉ số đo lường" (§9, mới)** | Không có | Thêm tiêu đề mục nhưng **để trống hoàn toàn**, không có nội dung | Không có gì để implement — chỉ ghi nhận PO chưa điền |
| **Hạ tầng real-time (đoạn cũ ở §6)** | Có câu hỏi mở: tự xây hay dùng SDK bên thứ 3 (Agora/Twilio), roster đọc trực tiếp được không | **Đã xoá khỏi V2.1** | Không còn liên quan — `uni-realtime` đã tự xây xong (Netty/Pekko) |

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
