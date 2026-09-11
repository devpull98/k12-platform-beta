# Tech Design — CMS Game Session Provisioning (đóng gap Task 11)

**Trạng thái:** Code thật đã triển khai và test xanh (2026-09-11, `uni-game-engine`). Tài liệu này
là hợp đồng kỹ thuật cho team CMS tích hợp — không phải đề xuất, là API đã tồn tại trong code.

**Bối cảnh:** xem `docs/work/NOJIRA-uni-p1-realtime-core/plan.md` Task 11 và
`docs/work/NOJIRA-uni-p2-inclass-game/plan.md` Task 28 để biết đầy đủ lịch sử gap này. Tóm tắt:
trước khi có tài liệu này, đường học sinh join phòng thật (`RoomSupervisor.spawnRoom()`) không có
cách nào biết phòng đó chơi game gì — luôn mặc định SOLO trống. Endpoint dưới đây là cách CMS "nạp"
nội dung 1 game cụ thể (câu hỏi, chia đội, luật tính điểm...) vào Engine **trước khi** học sinh đầu
tiên join, để Engine dùng đúng cấu hình đó thay vì mặc định.

---

## 1. Luồng vận hành

```
1. Giáo viên/CMS quyết định lớp X chơi game Y, có nội dung câu hỏi/đội cụ thể.
2. CMS gọi POST /internal/game-sessions/{room_id}/definition (endpoint dưới đây) — TRƯỚC khi
   học sinh đầu tiên join phòng room_id đó.
3. Engine validate + lưu vào Valkey (room-store), trả 200 nếu hợp lệ.
4. Học sinh join phòng room_id qua Gateway như bình thường (không đổi gì phía client/Gateway).
5. Engine tự đọc lại cấu hình đã lưu ở bước 3 lúc phòng được tạo lần đầu, dùng ĐÚNG cấu hình đó
   thay vì SOLO mặc định.
```

**Quan trọng — thứ tự bắt buộc:** bước 2 phải xảy ra TRƯỚC bước 4 cho cùng `room_id`. Nếu học sinh
join trước khi CMS provision xong, phòng đã lỡ dựng SOLO mặc định rồi — gọi provision sau đó
KHÔNG có tác dụng gì với phòng đang chạy (chỉ ảnh hưởng lần dựng phòng tiếp theo, nếu `room_id`
được tái sử dụng sau khi phòng cũ kết thúc).

## 2. Endpoint

```
POST /internal/game-sessions/{room_id}/definition
Content-Type: application/json
```

**Chạy trên cổng quản trị (8090 Engine), KHÔNG phải cổng WebSocket 9000/9100.** Chỉ dùng K8s
internal Service DNS (cùng cluster K8s với CMS) — không public, không cần domain/TLS riêng, chỉ
cần NetworkPolicy cho phép namespace CMS gọi tới namespace Engine. Xem
`docs/runbook/production-deployment-and-configuration.md` §1 cho bề mặt mạng đầy đủ.

**Chưa có xác thực (auth) trên endpoint này** — bắt buộc chỉ mở trong mạng nội bộ qua
NetworkPolicy cho tới khi có quyết định về service-to-service auth. Không expose ra Internet dưới
bất kỳ hình thức nào.

### Request body

| Field | Kiểu | Bắt buộc | Ghi chú |
|---|---|---|---|
| `gameMode` | string | **Có** | `SOLO` \| `COOPERATIVE` \| `TEAM` \| `INDIVIDUAL` (chưa implement ở Engine) |
| `questions` | list | **Có** | Danh sách câu hỏi, xem §3 |
| `maxPlayers` | int | Không | 1-12, 0 = không giới hạn (mặc định) |
| `progressStages` | list | Không | Xem §3 — chỉ dùng khi `progressDisplayMode=STAGED_VISUAL` |
| `sharedResourceType` | string | Không | `NONE` (mặc định) \| `TIME` \| `LIVES` (chưa implement) |
| `sharedResourcePenalty` | int | Không | Chỉ có ý nghĩa khi `sharedResourceType=TIME` |
| `teams` | list | Chỉ khi `gameMode=TEAM` | 2-4 đội, xem §3. Roster cố định — Engine KHÔNG tự chia ngẫu nhiên |
| `scoreAggregation` | string | Không | `SUM_ALL` (mặc định) \| `AVERAGE` \| `FIRST_CORRECT_ONLY` (chưa implement) |
| `winCondition` | string | Không | `PROGRESS_COMPLETED` (mặc định) \| `FIRST_TO_FINISH` \| `MOST_POINTS_WHEN_TIME_UP` |
| `roundTimeLimitSeconds` | int | Không | Giây cho MỖI câu hỏi, 0 = mặc định 25s |
| `lateJoinPolicy` | string | Không | `ALLOW_WITH_ZERO_SCORE` (mặc định) \| `BLOCK_AFTER_START` (chưa implement chặn thật) |
| `teamAssignmentMode` | string | Không | `MANUAL` (mặc định, dùng `teams` ở trên) \| `RANDOM` (chưa implement) |
| `introNarrative` | string | Không | Lời dẫn màn hình luật chơi — hiện CHƯA có đường broadcast ra client (xem §5) |
| `progressDisplayMode` | string | Không | `SIMPLE_BAR` (mặc định) \| `STAGED_VISUAL` — hiện CHƯA có đường broadcast ra client (xem §5) |

### `questions[]`

| Field | Kiểu | Ghi chú |
|---|---|---|
| `questionText` | string | Nội dung câu hỏi |
| `options` | list[string] | Các đáp án — số lượng tối thiểu 2, không giới hạn tối đa 4 (chưa validate) |
| `correctOptionIndex` | int | Chỉ số (0-based) của đáp án đúng trong `options` |

**Lưu ý quy ước tạm — chưa có client contract thật:** khi học sinh gửi `SUBMIT_ANSWER`, giá trị
`answer_ids` phải là **chỉ số đáp án dạng chuỗi** (`"0"`, `"1"`, `"2"`, `"3"`), KHÔNG phải nội dung
đáp án hay ký tự A/B/C/D. Đây là quy ước kỹ thuật tạm thời do Task 28 tự chọn (PO chưa đặc tả ID
đáp án) — cần CMS/client xác nhận lại khi có hợp đồng client thật (PH-3).

### `teams[]` (chỉ khi `gameMode=TEAM`)

| Field | Kiểu | Ghi chú |
|---|---|---|
| `teamId` | string | Định danh đội, duy nhất trong phòng |
| `teamName` | string | Tên hiển thị |
| `studentIds` | list[string] | Roster cố định — mỗi học sinh chỉ được ở đúng 1 đội |

### `progressStages[]`

| Field | Kiểu | Ghi chú |
|---|---|---|
| `milestonePercent` | int | 0-100, phải tăng dần strictly giữa các phần tử |
| `svgUrl` | string | URL ảnh hiển thị khi đạt mốc này |

## 3. Response

- **200 OK** — `{"roomId": "...", "status": "provisioned"}`.
- **400 Bad Request** — `{"error": "..."}` — request không hợp lệ (thiếu `gameMode`, enum sai, hoặc
  vi phạm guardrail của `DefinitionLoader`, ví dụ `team_count` phải 2-4). Sửa request rồi gọi lại —
  **không có gì được lưu khi 400**.
- **503 Service Unavailable** — Engine mới khởi động chưa kịp kết nối Valkey (rất hiếm, chỉ ngay
  lúc pod vừa start), hoặc Valkey không phản hồi được lúc ghi. Retry sau vài giây.

Provisioning **idempotent** — gọi lại cùng `room_id` sẽ ghi đè cấu hình cũ, không bị từ chối vì
"đã tồn tại".

## 4. Ví dụ request thật

**Cooperative quiz đơn giản:**
```json
{
  "gameMode": "COOPERATIVE",
  "questions": [
    { "questionText": "2 + 2 = ?", "options": ["3", "4", "5", "6"], "correctOptionIndex": 1 },
    { "questionText": "Thủ đô Việt Nam?", "options": ["Hà Nội", "Huế", "TP.HCM"], "correctOptionIndex": 0 }
  ],
  "winCondition": "PROGRESS_COMPLETED",
  "roundTimeLimitSeconds": 30,
  "introNarrative": "Rồng Số Học đang đe dọa lớp học..."
}
```

**Team speed race, 2 đội:**
```json
{
  "gameMode": "TEAM",
  "questions": [
    { "questionText": "5 x 5 = ?", "options": ["20", "25", "30"], "correctOptionIndex": 1 }
  ],
  "teams": [
    { "teamId": "A", "teamName": "Đội Rồng", "studentIds": ["student-01", "student-02", "student-03"] },
    { "teamId": "B", "teamName": "Đội Phượng", "studentIds": ["student-04", "student-05", "student-06"] }
  ],
  "winCondition": "FIRST_TO_FINISH",
  "roundTimeLimitSeconds": 20
}
```

## 5. Giới hạn đã biết — chưa đóng, không phải "sắp xong"

- **`RoomSupervisor.spawnRoom()` chỉ dùng cấu hình này cho LẦN DỰNG PHÒNG ĐẦU TIÊN.** Nếu Engine
  pod crash và phòng được khôi phục từ Hot Snapshot, cấu hình vẫn được đọc lại đúng (key Valkey
  không đổi) — nhưng CMS provision LẠI (ghi đè) MỘT phòng đang có học sinh chơi thật sẽ KHÔNG áp
  dụng cho phòng đang chạy, chỉ áp dụng nếu phòng đó bị dựng lại từ đầu.
- **Chỉ bắn được câu hỏi 1 tự động** (Task 28) — câu 2 trở đi vẫn cần cơ chế khác chưa xây
  (`TeacherCommand.NEXT_STEP` chưa nối dây) — xem plan.md Task 28's "câu hỏi PO còn treo".
- **`introNarrative`/`progressDisplayMode` được lưu và validate, nhưng CHƯA có đường broadcast ra
  client** — cần thêm field protobuf mới (nằm ngoài phạm vi việc này).
- **Không có TTL "session kết thúc, xoá luôn"** — provisioned definition tự hết hạn sau 7 ngày
  (Valkey TTL, xem `DistributedGameSessionDefinitionStore` javadoc) nếu không ai join, không phải
  dọn ngay khi trận đấu kết thúc. Chấp nhận được cho Phase 1, cần CMS định nghĩa lifecycle thật nếu
  muốn chặt chẽ hơn.
- **Chưa có xác thực trên endpoint** — chỉ an toàn vì giới hạn ở NetworkPolicy nội bộ K8s.

## 6. Tham chiếu code thật (cho ai cần đọc sâu hơn)

- `modules/uni-game-engine/src/main/java/com/uni/realtime/gameengine/provisioning/GameSessionProvisioningController.java` — endpoint.
- `modules/uni-game-engine/src/main/java/com/uni/realtime/gameengine/definition/GameSessionDefinitionMapper.java` — parse + validate.
- `modules/uni-game-engine/src/main/java/com/uni/realtime/gameengine/room/GameSessionDefinitionStore.java` + `persistence/DistributedGameSessionDefinitionStore.java` — lưu trữ Valkey.
- `modules/uni-game-engine/src/main/java/com/uni/realtime/gameengine/room/RoomSupervisor.java` (`handleJoin`/`onSnapshotLoaded`/`resolveProvisionedDefinition`) — nơi thật sự dùng lại cấu hình lúc dựng phòng.
- Test chứng minh luồng end-to-end (không phải giả định): `RoomSupervisorTest.should_useProvisionedGameDefinition_when_oneWasStoredBeforeFirstJoin`.
