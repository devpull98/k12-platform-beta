# Tech Design — NOJIRA-uni-p1: hợp đồng còn thiếu trước T2 / T4 / T6

<!-- @trace.uc_id: NOJIRA-uni-p1 -->
<!-- @trace.bdd_version: none — chưa có docs/specs/bdd/, xem §1 -->
<!-- @trace.status: draft -->

## 1. Tóm tắt và phạm vi

Tài liệu này **không mô tả lại kiến trúc**. Kiến trúc nằm ở `docs/architect/` và
[Tài liệu kiến trúc](../../architect/system-architecture.md) nói rõ: *"khi lệch nhau, một trong hai sai và phải
sửa — không để tồn tại song song."* Viết lại envelope, luồng dữ liệu hay ADR ở đây là tạo ra
đúng bản sao mà quy ước đó cấm.

Thứ tài liệu này làm: **chốt những hợp đồng còn trống mà T2 / T4 / T6 sắp đâm vào**. Ba nhánh
đó chạy song song ([plan.md](../../work/NOJIRA-uni-p1-realtime-core/plan.md) §Task List) nên mỗi
khoảng trống là một chỗ ba người sẽ tự điền ba kiểu khác nhau, rồi phát hiện lệch ở Task 13.

**Nguồn đầu vào — và một ghi chú về nó:**

| Đầu vào skill `tech-docs` yêu cầu | Trạng thái thật |
|---|---|
| BDD spec đã duyệt (`.feature` có UC-ID) | **Không tồn tại.** `docs/specs/bdd/` chưa có. Tài liệu này lấy hành vi từ `docs/architect/` + `plan.md`, không từ BDD |
| Codebase hiện tại | T1 xong: `game_message.proto` đã chốt + `GameMessageRoundTripTest` 4/4 |
| Stack rules | `rules/spring/` (cài 2026-09-06) |

> [!NOTE]
> Vì không có `.feature`, tài liệu này **không có SC-ID để trace**. `validate-trace.sh` hiện
> skip toàn bộ (không có thư mục BDD → không đòi `@trace` tag nào của code). Đừng đọc gate xanh
> đó là "đã phủ test".

**Ngoài phạm vi tài liệu này:** mọi thứ `docs/architect/` đã chốt, và mọi câu hỏi
[README §7.5](../../architect/system-architecture.md#75-quyết-định-còn-treo) giao cho **Product/Business** — xem §9.

---

## 2. API / hợp đồng trên dây

Không có REST endpoint mới. Biên realtime là WebSocket + Protobuf, đã chốt ở
[README §3.3](../../architect/system-architecture.md#33-envelope-thống-nhất-gamemessage) và `modules/uni-protocol/src/main/proto/game_message.proto`.
Phần dưới chỉ đóng ba chỗ còn trống.

### G1 — Hợp đồng ticket (chặn T6)

[README §3.4](../../architect/system-architecture.md#34-xác-thực-one-time-ticket) mô tả *luồng* ticket nhưng chưa ở đâu định nghĩa
**ticket là cái gì**. T6 phải viết `TicketAuthHandler` verify nó.

Ba ràng buộc dưới đây **suy ra được từ thiết kế đã chốt**, không phải lựa chọn mới:

| # | Ràng buộc | Suy ra từ |
|---|---|---|
| R1 | Ticket phải **tự chứa và verify được cục bộ** — không tra cứu, không gọi mạng | §13.2 cấm mọi DB/Redis/HTTP call trong EventLoop. Ticket dạng handle mờ (phải tra ra danh tính) **vi phạm trực tiếp** rule này |
| R2 | Ticket phải mang chữ ký | [README §2.3](../../architect/system-architecture.md#23-bên-trong-gateway): *"Mọi gói sau đó KHÔNG verify lại chữ ký"* — câu đó chỉ có nghĩa nếu có chữ ký để verify một lần |
| R3 | Claim tối thiểu: `student_id`, `room_id`, `session_id`, `roles`, `exp` | [README §3.4](../../architect/system-architecture.md#34-xác-thực-one-time-ticket) bước 3 — đó đúng là tập `ChannelAttributes` gateway phải bind |

**Chưa quyết được ở đây, phải xác nhận với đội dịch vụ nền tảng:** thuật toán ký (HMAC dùng
secret chung hay chữ ký bất đối xứng), cách phân phối/xoay khoá, và encoding cụ thể.
`_context.md` ghi `POST /session/{id}/join` **đã có** ở dịch vụ nền tảng — nên format ticket là
**sự thật cần đi hỏi, không phải thứ tài liệu này được phép thiết kế**. Bịa ra một format ở đây
sẽ được code theo và lệch với thứ đang chạy thật.

> [!NOTE]
> **Cơ chế cưỡng chế "Dùng MỘT lần" với Redis Cluster (ĐÃ CHỐT):**
>
> Cưỡng chế một-lần đòi hỏi trạng thái **dùng chung giữa các Gateway pod** (đã tiêu ticket nào).
> Với hạ tầng **Redis Cluster đã có sẵn**, Gateway cưỡng chế vé 1 lần tại `TicketAuthHandler` (T6) bằng lệnh atomic:
> ```text
> SET ticket:{jti} "1" EX 30 NX
> ```
> - Nếu trả về `OK` → Ticket hợp lệ và chưa ai dùng, cho phép hoàn tất handshake và bind `ChannelAttributes`.
> - Nếu trả về `nil` → Ticket đã bị dùng ở pod khác → Từ chối kết nối ngay lập tức.
>
> Thao tác này diễn ra đúng 1 lần duy nhất lúc mở kết nối WebSocket, **hoàn toàn không nằm trên hot path của trận đấu**, bảo đảm an toàn bảo mật tuyệt đối mà không ảnh hưởng latency.

**Còn phải chốt (kỹ thuật, nhỏ):** dung sai lệch đồng hồ khi so `exp`. Gateway và dịch vụ cấp
ticket là hai process khác nhau; TTL 30s mà lệch đồng hồ 5s là ăn mất 1/6 cửa sổ. Không tự điền
một con số ở đây — cần biết hai bên có cùng nguồn NTP không.

### G2 — Mã hoá delta snapshot (chặn T3, và client PH-3)

[README §3.6](../../architect/system-architecture.md#36-tối-ưu-payload) và [ADR-004](../../architect/system-architecture.md#adr-004)
đều nói broadcast là **delta**, full snapshot chỉ khi JOIN / RESYNC / *"mỗi N lần flush"*. Hai
chỗ trống:

**(a) `N` chưa có giá trị.** T3 không code được "mỗi N lần flush" khi N chưa ai đặt.

**(b) Delta biểu diễn trên dây thế nào — chưa định nghĩa, và schema hiện tại không tự diễn đạt được.**

`RoomStateSnapshot` có `bool full = 1` và `repeated PlayerState players = 3`. Với proto3,
`PlayerState` gửi thiếu trường **không phân biệt được** với trường mang giá trị mặc định:
`score = 0`, `answered_current = false`, `connected = false` đều là default. Nên "gửi kèm một
`PlayerState` chỉ điền trường đã đổi" sẽ được client đọc thành *"điểm về 0, mất kết nối"*.

Hợp đồng đề xuất — **cần review, không phải đã chốt**:

| Quy tắc | Nội dung |
|---|---|
| D1 | `full = false` → `players` chỉ chứa những học sinh **có thay đổi** kể từ lần flush trước |
| D2 | Mỗi `PlayerState` trong delta là **bản đầy đủ của học sinh đó**, không phải phần thay đổi trong đó |
| D3 | Học sinh vắng mặt trong delta = **không đổi**, không phải bị xoá |
| D4 | Định danh trong delta dùng `student_index` ([README §3.6](../../architect/system-architecture.md#36-tối-ưu-payload): 1 byte thay UUID) |

D1–D4 giữ được lợi ích kích thước (phòng 12 người, 1 người trả lời → gửi 1 `PlayerState` thay
vì 12) mà **không phải sửa `.proto`**. Nếu review muốn delta ở mức trường thay vì mức người
chơi thì **phải** sửa schema (thêm optional/field-mask) — và [README §3.6](../../architect/system-architecture.md#36-tối-ưu-payload)
quy định: sửa `.proto` sau khi chốt phải đi PR riêng + codegen lại cả hai service.

### G3 — `UPDATE_DRAFT` mâu thuẫn trong schema (chạm T7)

[README §3.6](../../architect/system-architecture.md#36-tối-ưu-payload) đã ghi nhận: `MessageType` có `UPDATE_DRAFT` nhưng
`oneof` **không có payload `UpdateDraft`**. Bổ sung một hệ quả cụ thể mà mục đó chưa nêu:

**T7 sẽ code một rate-limit bucket (`UPDATE_DRAFT` 10/refill 10s) cho một loại thông điệp client
không thể gửi.** Bucket đó không bao giờ đếm quá 0, và test của nó chỉ chứng minh được bucket
tồn tại chứ không chứng minh được nó chặn đúng.

Cần quyết (kỹ thuật): giữ nguyên và chấp nhận code chết có chú thích, hay bỏ `UPDATE_DRAFT` khỏi
`MessageType` cho tới khi chế độ Team vào phạm vi. Chế độ Team ngoài Giai đoạn 1 nên **không
chặn** T7 — nhưng người code T7 cần biết trước, không phát hiện lúc viết test.

---

## 3. Thay đổi Database

**Không có Database trên hot path.** Redis Cluster được tích hợp ở tầng phụ trợ async:
- Chặn replay ticket: `SET ticket:{jti} "1" EX 30 NX` tại Gateway handshake (ngoài hot path trận đấu).
- Lưu Hot Snapshot phòng: `SET room:snap:{room_id}` định kỳ (< 5 KB, ghi async qua Virtual Thread từ `RoomActor`).
- PostgreSQL: Lưu trữ kết quả phiên thi đấu sau khi kết thúc trận (được đẩy bất đồng bộ từ Kafka event consumer).

Task nào bắt đầu chèn truy vấn database/Redis đồng bộ vào hot path trận đấu là task vi phạm kiến trúc — dừng lại và đọc `_context.md`.

## 4. Events / Messages

**Không có Message Broker trên hot path.** Kafka Cluster được tích hợp ở tầng phụ trợ async:
- Topic: `game.events.v1`, partition key = `session_id`.
- `RoomActor` đẩy event sau khi đã tính điểm và gửi `ANSWER_ACK` cho học sinh (hoàn toàn ngoài hot path).
- Consumer: Teacher Dashboard (`SessionAggregator` theo dõi thời gian thực), PostgreSQL Writer (lưu điểm bền vững).
- Cấm tuyệt đối: Kafka nằm trên đường nộp bài của học sinh hoặc Kafka tham gia vào recovery ([ADR-003](../../architect/system-architecture.md#adr-003)).

## 5. Tích hợp dịch vụ ngoài

Một, và nó nằm **ngoài** hot path: dịch vụ nền tảng cấp ticket qua `POST /session/{id}/join`.

| Thuộc tính | Giá trị |
|---|---|
| Ai gọi | **Client**, trước khi mở WebSocket. Gateway **không** gọi dịch vụ này |
| Timeout / retry / fallback | Không áp dụng cho Gateway — không có lời gọi ra ngoài nào từ Gateway trên đường xác thực (chính là R1 ở §G1) |
| Phụ thuộc thật của Gateway | **Vật liệu verify chữ ký** (khoá/secret), nạp **lúc khởi động**, không phải mỗi handshake |

Đây là lý do R1 quan trọng hơn vẻ ngoài: nó biến một phụ thuộc runtime (gọi mạng trong
EventLoop) thành phụ thuộc lúc boot.

---

## 6. Tương thích ngược

Chưa có client production, chưa có gì để giữ tương thích. `game_message.proto` **đã chốt** (T1)
và cả hai service cùng phụ thuộc một artifact — nên quy tắc duy nhất đang áp dụng:

- Sửa `.proto` → PR riêng + codegen lại cả hai service ([README §3.6](../../architect/system-architecture.md#36-tối-ưu-payload)).
- **Không đánh số lại field.** `epoch` và `GamePhase.RESYNCING` đã chừa sẵn chỗ cho Giai đoạn 2
  chính là để tránh việc đó.
- Đề xuất D1–D4 ở §G2 chọn đường **không** đụng schema, có chủ đích.

## 7. Yêu cầu phi chức năng

Đã chốt ở [README §2.5 & §5](../../architect/system-architecture.md#25-game-definition--guardrails) và
[README §6](../../architect/system-architecture.md#6-triển-khai-vận-hành--khôi-phục-sự-cố); không lặp lại. Ba điểm liên quan trực tiếp tới hợp đồng
trên:

- **Xác thực:** một lần lúc handshake, sau đó danh tính đọc từ `ChannelAttributes`. `room_id`
  **luôn** từ attribute, không bao giờ từ payload — payload lệch là **security event → đóng
  channel** (§10.6).
- **Rate limit:** khoá theo `student_id`, không theo IP làm tầng chính (trường học sau NAT dùng
  chung IP). L1 theo IP = **4.000 handshake/phút** (đã chốt 2026-09-06, xem README §5.6 —
  ước lượng theo quy mô phiên lớn nhất, chưa phải số đo IP thật).
- **Cache:** chỉ có `RouteCache` (`room_id → pod`), in-memory, **không TTL** — entry sai tự sửa
  ở lần dùng kế tiếp (§8.2). Không có Redis nào để đặt TTL lên.

## 8. Bản đồ module

| Hợp đồng | Module / file | Task |
|---|---|---|
| G1 ticket | `modules/uni-gateway/.../auth/TicketAuthHandler.java` | T6 |
| G2 delta | `modules/uni-engine/.../room/CoalescingFlush.java` | T3 |
| G3 `UPDATE_DRAFT` | `modules/uni-protocol/src/main/proto/game_message.proto` · `modules/uni-gateway/.../net/RateLimitHandler.java` | T7 |
| Envelope (đã chốt) | `modules/uni-protocol/src/main/proto/game_message.proto` | T1 ✅ |

Không có tầng Controller / Service / Repository. `rules/spring/architecture.mdc` giải thích vì
sao: `stack: spring` ở repo này chỉ nghĩa là "boot bằng Spring Boot" — Spring không nằm trên
đường đi gói tin.

---

## 9. Câu hỏi còn treo

### 9.1 Kỹ thuật — chặn task, quyết được trong đội

- [ ] **G1a** Thuật toán ký ticket + phân phối khoá — **đi hỏi đội dịch vụ nền tảng**, không tự
      thiết kế (dịch vụ đã tồn tại). *Chặn T6.*
- [x] **G1b** "Một lần" hay "TTL ngắn"? → **ĐÃ CHỐT:** Cưỡng chế vé 1 lần bằng Redis Cluster `SET ticket:{jti} "1" EX 30 NX` tại Gateway handshake. Không còn chặn T6.
- [ ] **G1c** Dung sai lệch đồng hồ khi kiểm `exp`. *Chặn T6.*
- [ ] **G2a** `N` = bao nhiêu lần flush thì gửi full snapshot? *Chặn T3.* (Lưu ý: quyết định
      2026-09-06 về "trần 50KB" là trần chung `HttpObjectAggregator` ở Gateway — **không phải**
      câu trả lời cho `N`, hai việc tách biệt. G2a **vẫn còn treo**.)
- [ ] **G2b** Duyệt D1–D4, hay chọn delta mức trường (→ phải sửa `.proto`). *Chặn T3 và client PH-3.*
- [ ] **G3** Giữ hay bỏ `UPDATE_DRAFT`. *Không chặn — nhưng người code T7 cần biết trước.*

### 9.2 Product / Business — tài liệu này **cố ý không điền**

[README §7.5](../../architect/system-architecture.md#75-quyết-định-còn-treo) đã giao chủ những câu này, và quy ước của bộ
tài liệu nói thẳng: *"không tự điền một giá trị hợp lý. Một mặc định bịa ra trong tài liệu kiến
trúc sẽ được code theo và không ai biết nó chưa từng được duyệt."*

| # | Câu hỏi | Ai quyết | Chặn gì ở đây | Trạng thái |
|---|---|---|---|---|
| 1 | **Công thức điểm cụ thể cho quiz** | Product | `AnswerAck.awarded_points` không tính được → `ScoreCalculator` của **T2** và Game Definition của **T11** | ✅ ĐÃ CHỐT (2026-09-06) — xem ghi chú dưới |
| 2 | `missed_step_policy` mặc định | Product | Schema Game Definition (**T11**) | 🔴 Còn treo |

> [!NOTE]
> **Câu 1 đã chốt (2026-09-06, Product):** trắc nghiệm 1-trong-4 đáp án, nhị phân đúng/sai —
> đúng = **100 điểm**, sai = **0 điểm**, không có bonus tốc độ. Chi tiết đầy đủ:
> [README §2.5](../../architect/system-architecture.md#25-game-definition--guardrails).
>
> T2 đã xong từ trước với `PlaceholderScoreCalculator` (flat, đánh dấu rõ TEMPORARY) đúng theo
> đường đi khuyến nghị lúc đó. Việc còn lại: viết implementation thật của `ScoreCalculator`
> theo công thức trên và thay `PlaceholderScoreCalculator` trong `GatewayBootstrap`/nơi khởi
> tạo `RoomActor` — **chưa làm**, đây là việc kế tiếp khi quay lại T2/T11.
>
> Câu 2 (`missed_step_policy` mặc định) **vẫn còn treo** — T11 đã hiện thực schema với giá trị
> `ZERO` theo đúng chữ AC của plan.md Task 11, nhưng đó là lựa chọn kỹ thuật để test chạy được,
> **không phải** quyết định Product chính thức cho câu này.

---

## 10. Checklist cho người review

- [ ] R1–R3 (§G1) có đúng là **suy ra** từ thiết kế đã chốt, hay có chỗ đã lén thành lựa chọn mới?
- [ ] Ba lựa chọn one-time ticket đã đủ chưa? Khuyến nghị (1) có chấp nhận được về bảo mật không?
- [ ] D1–D4 có giữ được `RoomStateSnapshot` < 5 KB ở phòng 12 người không?
- [ ] D3 ("vắng mặt = không đổi") có tạo lỗ nào khi một học sinh rời phòng không? Rời phòng phải
      biểu diễn bằng `connected = false` **có mặt** trong delta, không phải bằng vắng mặt.
- [ ] §3 và §4 để trống — có task nào đang âm thầm cần DB/queue không?
- [ ] Đề xuất nào ở đây **đáng lẽ** phải là quyết định của Product mà tài liệu này lỡ điền hộ?
