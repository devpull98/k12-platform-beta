# Product Brief & Specification: In-class Group & Cooperative Games (Version 2.2)

> **Artifact ID:** `INCLASS-GAME-001-V2.2`  
> **Layer:** Product Brief & Business Specification (Tài liệu Nghiệp vụ Giai đoạn 2)  
> **Nguồn gốc:** [`PO_Require_Game+nhóm_+tập+thể+Inclass V2.2.doc`](./PO_Require_Game+nhóm_+tập+thể+Inclass%20V2.2.doc) (Ngày cập nhật: 11/09/2026)  
> **Tài liệu tiền nhiệm:**  
> - [`INCLASS-GAME-001-inclass-group-cooperative-games-v2.1.md`](./INCLASS-GAME-001-inclass-group-cooperative-games-v2.1.md) (Bản V2.1)  
> - [`INCLASS-GAME-001-inclass-group-cooperative-games.md`](./INCLASS-GAME-001-inclass-group-cooperative-games.md) (Bản V1.0)  

---

## 📊 Bảng So Sánh Tổng Quan V2.1 vs V2.2

> [!IMPORTANT]
> **Cập nhật V2.2 (11/09/2026)** — Phiên bản V2.2 bổ sung và chuẩn hóa các quy tắc quan trọng về luồng FSM (thêm `RULES_DISPLAY`, tự động `START`), quy tắc phân định thắng thua khi hòa (Tie-break by total response time), quy tắc tính điểm cá nhân / phe / cúp, xử lý chi tiết trường hợp rớt mạng / thoát game chủ động, và điều chỉnh Input Schema (chuyển `questions` sang Group A, loại `progress_target` khỏi Group B).

| Tiêu chí | Bản V2.1 | Bản V2.2 (Mới nhất) | Tác động kỹ thuật Backend (Engine/Protocol) |
|---|---|---|---|
| **Server State Machine (§5.2)** | `WAITING_FOR_PLAYERS` $\rightarrow$ `IN_PROGRESS` $\rightarrow$ `ENDED`. Bắt đầu game do GV bấm. | `WAITING_FOR_PLAYERS` $\rightarrow$ **`RULES_DISPLAY`** $\rightarrow$ `IN_PROGRESS` $\rightarrow$ `ENDED`. **`START` chuyển tự động** sang `IN_PROGRESS`. | Cập nhật `RoomActor` FSM thêm state `RULES_DISPLAY` và tự động transition mà không chờ tín hiệu `START` thủ công từ GV. |
| **Tính `progress_target` (§5.2, §7)** | LLM sinh ra trong Schema Group B. | **Engine tự tính** = số phần tử trong `questions` (Group A). | Bỏ đọc `progress_target` từ LLM config; Engine tự gán `progressTarget = questions.size()`. |
| **Quy tắc Hòa / Tie-break (§5.3)** | Chưa chốt luật phụ (*"PO flag edge case"*). | **Đã chốt**: Bằng % tiến trình $\rightarrow$ Phe/Cá nhân có **tổng thời gian trả lời ngắn hơn sẽ thắng**. | Cập nhật `WinConditionEvaluator` tính `total_response_time` (lấy thời gian của HS trả lời đầu tiên nếu câu hỏi có từ 2 HS trả lời trở lên). |
| **Điểm cá nhân không trả lời đúng (§5.4)** | Bạn cùng team làm đúng $\rightarrow$ được 50% điểm. | **Loại bỏ 50% điểm**. Cá nhân không trả lời đúng $\rightarrow$ **0 điểm**. | Sửa logic `ScoreCalculator`: chỉ cộng điểm cá nhân khi bản thân trả lời đúng. |
| **Điều kiện cộng điểm cho Phe (§5.4)** | Có 1 HS trả lời đúng là tính điểm cho team. | **Có 1 HS trả lời đúng VÀ $\ge 50\%$ số HS trong team có tham gia trả lời**. | Thêm check tỉ lệ tham gia `participated_ratio >= 0.5` trước khi cộng tiến trình/điểm nhóm. |
| **Quy đổi Cúp (§5.4)** | Đề cập chung điểm quy đổi ra cúp. | **Chốt cứng**: `Số cúp nhận được = Số điểm của Team` (1 điểm = 1 cúp, làm tròn lẻ). | Engine broadcast số cúp và bắn Kafka event kết quả ở state `ENDED`. |
| **Công thức `speed_based` (§5.4)** | `base_points * (thời gian còn lại / round_time_limit)`. | Sửa thành `điểm tối đa * (thời gian còn lại / round_time_limit)` + ví dụ và điểm sàn cố định. | Cập nhật công thức tính điểm thời gian thực chuẩn hóa có điểm sàn (floor limit). |
| **Rớt mạng / Thoát game (§5.6)** | Coi như bỏ cuộc, không ảnh hưởng điểm/tiến trình đội. | Phân loại rõ **Thoát chủ động** & **Mất kết nối**: Điểm câu bỏ qua = 0, giữ điểm cũ, **vẫn nhận cúp nếu trả lời $\ge 1$ câu**. | Xử lý trong `RoomActor`: tính 0đ các câu trễ/bỏ qua bằng cơ chế deadline (`deadlineMs`), **không dùng `missed_step_policy`**; đồng thời bảo lưu cúp thưởng cuối trận. |
| **Input Schema Group A (§7)** | `questions` thuộc LLM Group B, `score_aggregation` có `majority_vote`. | **`questions` chuyển sang Group A** (Học thuật nhập), `max_players` tối đa 12, **bỏ `majority_vote`**. | Cập nhật DTO Group A form khởi tạo game; bỏ enum `majority_vote`. |
| **Input Schema Group B (§7)** | Có `progress_target`, chưa có lời dẫn. | **Bỏ `progress_target`**, bổ sung **`intro_narrative`** (lời dẫn màn hình luật chơi). | Thêm trường `introNarrative: String` hiển thị ở màn hình `RULES_DISPLAY`. |

---

## 1. Bối cảnh, Tâm lý Học sinh & Mục tiêu (Mục 1 PO V2.2)

### 1.1. Bối cảnh & Tâm lý Học sinh
- **Vấn đề:** Công cụ hiện tại chỉ tạo game cho 1 học sinh chơi solo. Lớp học online thiếu tương tác xã hội (học sinh không ngồi cạnh nhau).
- **Giải pháp tâm lý:** Game nhóm tạo cảm giác **thuộc về tập thể** (belongingness). Khi trả lời sai, áp lực được san sẻ cho cả nhóm giúp học sinh yếu/nhút nhát dám tham gia nhiều hơn. Cảm xúc tập thể tạo trải nghiệm mạnh hơn so với điểm số cá nhân.

### 1.2. Mục tiêu Nâng cấp
Hỗ trợ 3 chế độ chơi (**Game Mode**):
1. **`cooperative` (Tập thể):** Cả lớp (tối đa 12 học sinh) cùng chơi 1 game (cùng đánh Boss, cùng vượt chướng ngại vật).
2. **`team` (Chia nhóm):** Chia lớp thành $X$ nhóm ($2–4$ nhóm) thi đấu với nhau.
3. **`individual` (Cá nhân mở rộng):** Từng học sinh thi đấu trực tiếp nhưng gắn với tài nguyên/mốc tiến trình chung.

Mechanic ban đầu duy nhất: **`progress_meter`** (Thanh tiến trình chung — con số tăng dần tới đích), thể hiện qua các themes nội dung: Đánh Boss, Xây công trình, Vượt chướng ngại vật.

---

## 2. Nguyên Tắc Thiết Kế Cốt Lõi (Mục 4 PO V2.2)

1. **Mechanic (cơ chế) tách khỏi Content (nội dung):**
   - **Mechanic:** Luật vận hành, tính điểm, FSM. Dev lập trình **1 lần**, có tham số.
   - **Content:** Chủ đề, câu hỏi, hình ảnh SVG. LLM/Đội học thuật nạp vào Schema cố định mà **không đụng tới code dev**.
2. **Mode (chế độ) tách khỏi Mechanic:**
   - **Mode** quyết định *cấu trúc phe* (`cooperative` = 1 phe; `team` = $\ge 2$ phe; `individual` = tối đa 12 phe).
   - **Mechanic** quyết định *luật tương tác giữa các phe*.

---

## 3. Chi Tiết Input Schema (Mục 7 PO V2.2)

### 3.1. Group A — Đội Học thuật chọn/nhập trên Form
| Trường | Kiểu / Giá trị | Áp dụng Mode | Ghi chú & Hành vi |
|---|---|---|---|
| `game_mode` | `solo` \| `cooperative` \| `team` \| `individual` | Tất cả | Xác định cấu trúc phe trong phòng |
| `game_mechanic` | `progress_meter` | Tất cả | Cơ chế tính tiến trình duy nhất ở bản này |
| `(chủ đề nội dung)`| String | Tất cả | Mô tả nội dung bài học/chủ đề |
| `max_players` | Integer (tối đa 12) | Tất cả | Sức chứa tối đa của phòng (**Note: tối đa 12 học sinh**) |
| `questions` | List[Question] | Tất cả | **Chuyển sang Group A**: Học thuật tự nhập (`question_text`, `options` A–D, `correct_option_index`) |
| `shared_resource` | `time` \| `lives` \| `none` | `cooperative` | Tài nguyên chung bị trừ khi sai |
| `team_count`, `team_assignment`, `team_names`, `team_colors` | Struct | `team` | Cấu hình số nhóm, phân nhóm (`random`/`manual`), tên & màu nhóm |
| `score_aggregation` | `sum_all` \| `first_correct_only` | `team` | Cách gộp điểm nhóm (**Loại bỏ `majority_vote`**) |
| `scoring_rule` | `fixed` \| `speed_based` | `team`, `individual` | Quy tắc tính điểm câu trả lời |
| `round_time_limit` | Integer (giây) | Tất cả | Thời gian tối đa cho 1 câu hỏi |
| `late_join_policy` | `allow_with_zero_score` \| `block_after_start` | Tất cả | HS vào giữa chừng nhận điểm ban đầu = 0 và nhảy vào đúng thời gian hiện tại |

### 3.2. Group B — LLM Tự Sinh theo Schema Tĩnh
| Trường | Kiểu / Giá trị | Engine sử dụng để... |
|---|---|---|
| `intro_narrative` | String | **Bổ sung V2.2**: Lời dẫn theo chủ đề hiển thị trên màn hình luật chơi (`RULES_DISPLAY`) (ví dụ: *"Rồng Số Học đang đe dọa..."*) |
| `progress_display_mode` | `simple_bar` \| `staged_visual` | `simple_bar` $\rightarrow$ thanh % đơn giản; `staged_visual` $\rightarrow$ đổi ảnh SVG theo mốc |
| `progress_stages` | List `{ milestone_pct, svg_url }` | Engine so % hiện tại với mốc, tự đổi ảnh SVG tương ứng trên màn hình HS |
| `win_condition` | `progress_completed` \| `first_to_finish` \| `most_points_when_time_up` | Xác định điều kiện kết thúc game và tìm phe thắng cuộc |

*(Lưu ý: `progress_target` đã bị loại bỏ khỏi Group B trong V2.2, Engine tự gán `progress_target = questions.size()`)*

---

## 4. Luồng Vận Vận Hành Server FSM (Mục 5.2 PO V2.2)

```text
  [INIT]
    │  • Giáo viên bấm "Bắt đầu game" → Engine nạp config (Nhóm A) + content (Nhóm B).
    │  • Khởi tạo: tiến trình = 0, question_index = 1, shared_resource ban đầu.
    │  • progress_target = số phần tử trong questions (Nhóm A) — do Engine tự tính.
    ▼
  [WAITING_FOR_PLAYERS]
    │  • Học sinh bấm "Vào chơi" → thêm vào danh sách, gán team_id / student_index.
    │  • Late join: xử lý theo late_join_policy (allow_with_zero_score / block_after_start).
    ▼
  [RULES_DISPLAY] (Mới ở V2.2)
    │  • Hiển thị màn hình luật chơi & lời dẫn chủ đề (intro_narrative).
    ▼
  [IN_PROGRESS] (START Tự Động)
    │  • Engine tự động chuyển IN_PROGRESS & phát câu hỏi 1 (không chờ GV bấm thêm).
    │  • Đếm ngược round_time_limit từng câu.
    │  • Học sinh nộp / sửa đáp án nháp (UPDATE_DRAFT).
    │  • Đánh giá kết quả từng câu:
    │      + Phe cộng tiến trình/điểm khi: 1 HS đúng VÀ >= 50% số HS trong phe có trả lời.
    │      + Cá nhân đúng -> nhận điểm (Fixed/Speed-based). Cá nhân sai/không làm -> 0 điểm.
    │  • Kiểm tra win_condition hoặc hết câu hỏi -> chuyển ENDED.
    ▼
  [ENDED]
       • Engine tính kết quả cuối, hiển thị bảng xếp hạng cho học sinh.
       • Tính & cộng cúp thưởng: Số cúp mỗi HS = Số điểm của Team (1 điểm = 1 cúp).
       • Broadcast kết quả cuối & đẩy sự kiện Kafka kết quả.
```

---

## 5. Quy Tắc Thắng Thua, Tính Điểm & Thưởng Cúp (Mục 5.3 & 5.4 PO V2.2)

### 5.1. Quy tắc Thắng / Thua & Tie-break (Mục 5.3)
- **Tập thể (`cooperative`):**
  - **Thắng:** Tiến trình chạm `progress_target` trước khi hết tài nguyên chung.
  - **Thua:** Hết tài nguyên chung mà tiến trình chưa đầy. (Nếu `shared_resource = none`, ván kết thúc ở trạng thái "hoàn thành" khi hết câu hỏi — không có khái niệm thua).
- **Chia nhóm (`team`) / Cá nhân (`individual`):**
  - Mỗi phe/học sinh chạy song song 1 tiến trình riêng.
  - **Thắng:** Phe đầu tiên chạm `progress_target` (`first_to_finish`). Hết giờ/hết câu hỏi mà chưa ai chạm đích $\rightarrow$ phe có % tiến trình cao nhất thắng (`most_points_when_time_up`).
  - **Quy tắc Hòa (Tie-break):** Nếu % tiến trình bằng nhau $\rightarrow$ **phe nào có tổng thời gian trả lời ngắn hơn sẽ thắng**.
  - **Tính thời gian trả lời:** Tổng thời gian trả lời các câu hỏi của phe đó. Nếu 1 câu hỏi được trả lời bởi từ 2 người trở lên trong nhóm $\rightarrow$ thời gian trả lời của câu đó = thời gian của người trả lời **đầu tiên**.

### 5.2. Quy tắc Tính điểm & Thưởng Cúp (Mục 5.4)
- **Cá nhân:**
  - Cá nhân trả lời đúng câu hỏi $\rightarrow$ nhận đủ điểm theo `scoring_rule`.
  - Cá nhân trả lời sai hoặc không trả lời $\rightarrow$ **0 điểm** (Bỏ luật 50% điểm ăn theo đồng đội ở V2.1).
- **Điểm Phe (Team):**
  - Phe được tính điểm/tiến trình câu đó khi: **Có 1 học sinh trả lời đúng VÀ có từ $50\%$ số học sinh trong phe tham gia trả lời câu hỏi**.
- **Cúp Thưởng:**
  - **Số cúp mỗi người nhận được khi kết thúc trận = Số điểm của Team**.
  - Quy đổi: $1 \text{ điểm} = 1 \text{ cúp}$ (làm tròn lên $1$ cúp nếu có điểm lẻ).
- **Công thức `speed_based`:**
  $$\text{Điểm} = \text{Điểm tối đa của câu} \times \left(\frac{\text{Thời gian còn lại}}{\text{round\_time\_limit}}\right)$$
  - *Ví dụ:* Điểm tối đa = 10đ, `round_time_limit` = 30s, thời gian còn lại = 15s $\rightarrow$ Điểm = $10 \times (15 / 30) = 5$ điểm.
  - Có điểm sàn tối thiểu (ví dụ: 2 điểm) nếu trả lời đúng sát hạn thời gian.

---

## 6. Các Luật Biên Chi Tiết (Mục 5.6 PO V2.2)

1. **Thứ tự câu hỏi:** Cố định theo thứ tự đội học thuật nhập.
2. **Dạng câu hỏi:** Mặc định trắc nghiệm (Multiple Choice 1/4).
3. **Đổi đáp án (`UPDATE_DRAFT`):** Học sinh được sửa lựa chọn trước khi hết giờ. Engine xử lý draft sync real-time trong cùng `team_id`.
4. **Xử lý Mất kết nối & Thoát game chủ động:**
   - **Thoát chủ động:** Học sinh chủ động bấm thoát game (ấn nút thoát, kill app, rời buổi học).
   - **Mất kết nối:** Hệ thống nhận được tín hiệu mất kết nối network.
   - **Quy tắc xử lý nghiệp vụ:** Cả 2 trường hợp đều ghi nhận **0 điểm** cho các câu hỏi không tham gia trả lời. Giữ nguyên điểm cá nhân đã tích lũy trước đó. Khi trận kết thúc, học sinh **vẫn được cộng cúp** như các học sinh khác **nếu có tham gia trả lời ít nhất 1 câu hỏi**.
   - **Lưu ý triển khai kỹ thuật Backend:** Việc tính 0 điểm cho các câu bỏ qua này được thực hiện qua **cơ chế đếm ngược Timeout (`deadlineMs` + `round_time_limit`)** khi `RoomActor` chuyển câu hỏi, **KHÔNG sử dụng `missed_step_policy`** (`missed_step_policy` chỉ dùng 1 lần duy nhất khi học sinh mới thực hiện `joinRoom` để tính điểm các câu đã diễn ra trước đó).
5. **Cấm hủy giữa ván:** Giáo viên không được phép hủy ván giữa chừng khi game đang chạy.
6. **Làm tròn % tiến trình:** Làm tròn xuống số nguyên (`floor`), ví dụ 33.33% $\rightarrow$ 33%.
7. **Không sử dụng LLM thời gian thực:** LLM chỉ sinh cấu hình tĩnh trước trận; toàn bộ logic đếm ngược, tính điểm, chuyển câu, kiểm tra điều kiện thắng thua và trao cúp do **Engine vận hành 100% trên Server**.

---

## 7. Thành Phần Hạ Tầng Cần Triển Khai (Mục 6 PO V2.2)

1. **Game Engine (Backend - `uni-game-engine`):**
   - Đọc Input Schema Group A/B.
   - Quản lý FSM bao gồm state `RULES_DISPLAY` và tự động transition `START`.
   - Tính điểm server-authoritative theo quy tắc 50% tham gia phe, tie-break theo tổng thời gian trả lời, và quy đổi cúp thưởng.
   - Broadcast WebSocket real-time cho học sinh và giáo viên.
2. **CMS (gán Game ↔ Lớp học):** Lưu liên kết `class_id ↔ game_id` để Teacher Dashboard đọc game đúng lớp.
3. **Teacher Dashboard (Điều khiển):** Hiển thị game đã gán cho lớp; Giáo viên bấm Bắt đầu (`INIT`), xem tiến độ real-time của các nhóm/cá nhân, tổng kết lớp.
4. **Student UI (Giao diện học sinh nhúng):**
   - Màn hình chung (hiển thị `intro_narrative` ở state `RULES_DISPLAY`, thanh tiến trình, điểm nhóm, bảng xếp hạng).
   - Màn hình riêng (câu hỏi, đồng hồ đếm ngược, nút chọn đáp án, điểm cá nhân, số cúp đạt được).
