# Kiến trúc Hệ thống Game Thời gian thực EdTech — v3.0

**High-Concurrency EdTech Realtime Multiplayer Platform**

| | |
|---|---|
| **Phiên bản** | 3.0 — bản hợp nhất, thay thế v2.1 và v2.2 |
| **Trạng thái** | Kiến trúc đã chốt, chưa được xác nhận bằng số đo *(xem §16)* |
| **Mục tiêu tải** | 54.000 CCU · 4.500–5.000 phòng đồng thời · 12 học sinh/phòng |
| **Nền tảng** | Java 21 LTS (mã ví dụ viết theo Java 25) · Apache Pekko Typed + Cluster Sharding · Netty · Protobuf · Kubernetes |

---

## Tài liệu này là gì

v3.0 chỉ chứa **kiến trúc đang có hiệu lực**. Nó không kể lại lịch sử tranh luận, không giữ
phương án đã loại, không đánh dấu "v2.1 viết sai chỗ này".

Hai tài liệu tiền nhiệm vẫn được giữ vì chúng phục vụ mục đích khác:

| Tài liệu | Dùng khi nào |
|---|---|
| `EdTech_Game_Realtime_Architecture_v2.2-revisions.md` | Cần biết **vì sao** một quyết định bị đảo. Ghi rõ từng lỗi: v2.1 viết gì → sai ở đâu → thay bằng gì (R-01…R-25) |
| `EdTech_Game_Realtime_Architecture_v2.2.md` | Hồ sơ rà soát đầy đủ, kèm cả nội dung sai lẫn lý do thay thế |
| **`EdTech_Game_Realtime_Architecture_v3.0.md`** *(tài liệu này)* | **Bản dev đọc để code.** Không cần đọc hai bản trên trước |

> [!IMPORTANT]
> **Mọi con số capacity trong tài liệu này là giả thuyết chưa đo.**
> `5.000 WS/pod`, `300–375 rooms/pod`, `10–12 GW pod`, `12–16 Engine pod` — tất cả đều là
> điểm khởi đầu hợp lý, **không phải kết luận**. Chúng được viết ra để có cái mà bác bỏ.
> Không dùng chúng để lập ngân sách hay cam kết với đối tác trước khi H1/H2 (§16) có kết quả.

---

## Mục lục

| § | Nội dung |
|---|---|
| **[1](#1-bối-cảnh--ràng-buộc)** | Bối cảnh & Ràng buộc |
| **[2](#2-quyết-định-kiến-trúc-adr)** | Quyết định kiến trúc (ADR) |
| **[3](#3-kiến-trúc-tổng-thể)** | Kiến trúc tổng thể |
| **[4](#4-mô-hình-định-danh--vòng-đời-phòng)** | Mô hình định danh & vòng đời phòng |
| **[5](#5-giao-thức--kênh-giao-tiếp)** | Giao thức & kênh giao tiếp |
| **[6](#6-mô-hình-tải--tick-coalescing)** | Mô hình tải & tick coalescing |
| **[7](#7-đường-đi-của-một-gói-tin)** | Đường đi của một gói tin |
| **[8](#8-định-tuyến--cluster-sharding)** | Định tuyến & Cluster Sharding |
| **[9](#9-tính-đúng-đắn-dưới-sự-cố)** | Tính đúng đắn dưới sự cố |
| **[10](#10-bảo-vệ-runtime)** | Bảo vệ runtime |
| **[11](#11-tầng-world--teacher-dashboard)** | Tầng World & Teacher dashboard |
| **[12](#12-vào-phòng-muộn--kết-nối-lại)** | Vào phòng muộn & kết nối lại |
| **[13](#13-mô-hình-thực-thi)** | Mô hình thực thi |
| **[14](#14-triển-khai)** | Triển khai |
| **[15](#15-quan-sát--sla)** | Quan sát & SLA |
| **[16](#16-load-test--benchmark)** | Load test & benchmark |
| **[17](#17-chi-phí-hạ-tầng)** | Chi phí hạ tầng |
| **[18](#18-lộ-trình)** | Lộ trình |
| **[19](#19-việc-còn-mở)** | Việc còn mở |
| **[A](#phụ-lục-a--phương-án-đã-loại)** | Phụ lục A — Phương án đã loại |
| **[B](#phụ-lục-b--thuật-ngữ)** | Phụ lục B — Thuật ngữ |

---

## 1. Bối cảnh & Ràng buộc

### 1.1. Bài toán

Nền tảng học tập cho phép giáo viên mở một phiên, học sinh vào phòng theo nhóm nhỏ và cùng
chơi một trò chơi giáo dục thời gian thực — quiz, đua xe theo tốc độ trả lời, thảo luận nhóm
có gõ nháp chung, đối kháng boss toàn phòng.

Đặc thù tải của EdTech quyết định gần như toàn bộ kiến trúc:

| Đặc thù | Hệ quả kiến trúc |
|---|---|
| **Tải không phẳng** — 09:00 giáo viên bấm Bắt đầu, 54.000 kết nối trong ~15 giây | Thời điểm khó nhất không phải steady state mà là **connection storm** (§10.4) |
| **Cả trường ra Internet qua một IP NAT** | Rate limit **không được** khoá theo IP làm tầng chính (§10.1) |
| **Sự cố rơi thẳng vào giờ học đang diễn ra** | Ưu tiên **blast radius nhỏ** hơn hiệu suất đóng gói (§14.1) |
| **Điểm số phụ thuộc tốc độ trả lời** | Thời điểm trả lời **bắt buộc** do server đóng dấu (§9.4) |
| **Có thể chỉ chạy 4–6 tiếng/ngày** | Chi phí nhàn rỗi có thể là khoản chi phối (§17.3) |

### 1.2. Chỉ tiêu

| Chỉ tiêu | Giá trị |
|---|---|
| Tải hiện tại | 10.000 – 18.000 CCU |
| Tải thiết kế (3×) | **54.000 CCU** |
| Phòng đồng thời | 4.500 – 5.000 |
| Học sinh/phòng | 12 *(phân tích room size 100 nằm ngoài phạm vi bản này)* |
| Thời lượng phiên | < 70 phút |
| Độ trễ submit → ACK | **p99 < 100 ms** |
| Mất dữ liệu đáp án khi sập pod | **0** |

### 1.3. Chế độ chơi

* **Solo** — 12 học sinh tương tác độc lập trong cùng phòng.
* **Team** — chia nhóm nhỏ (4 nhóm × 3), có gõ nháp/thảo luận nội bộ nhóm (co-editing).
* **Hybrid / Broadcast** — thi đua toàn phòng, boss chung, thanh tiến trình chung.

### 1.4. Nguyên tắc thiết kế

1. **Authoritative Server** — server giữ toàn bộ quyền quyết định logic, điểm số, trạng thái.
   Client là tầng hiển thị. *Nguyên tắc này chỉ có giá trị nếu §9.4 được thực hiện đúng.*
2. **1 Room = 1 Actor đơn luồng** — xử lý tuần tự trong phòng, triệt tiêu lock contention và
   race condition ở tầng nghiệp vụ. Đây là lựa chọn cốt lõi, mọi thứ khác xoay quanh nó.
3. **Gateway không chứa business logic** — tuyệt đối. Gateway biết `room_id` để định tuyến,
   không biết luật chơi.
4. **Hot path ngắn nhất có thể** — không broker, không Valkey, không DB nằm giữa client và
   actor trong đường đi của một lượt nộp bài.
5. **Không cam kết con số chưa đo** — mọi ngưỡng capacity đều gắn với một phép thử cụ thể.

### 1.5. Ngoài phạm vi

Matchmaking Service, Player Profile, Leaderboard Service và ứng dụng Flutter được nhắc tới
như **hệ thống lân cận** với hợp đồng giao tiếp rõ ràng, nhưng thiết kế nội bộ của chúng
không thuộc tài liệu này.

---

## 2. Quyết định kiến trúc (ADR)

Bốn quyết định dưới đây chi phối phần còn lại của tài liệu. Mỗi quyết định ghi kèm **cái giá
phải trả** — một ADR không nêu cái giá là một ADR chưa được cân nhắc.

### ADR-1 · Kênh nội bộ Gateway ↔ Engine dùng TCP + length-prefixed Protobuf

```
Client --WS + Protobuf--> [GW pod] --TCP, length-prefixed Protobuf--> [Engine pod]
                          stateless                                    ShardRegion
```

**Không dùng gRPC.** gRPC mang lại codegen, deadline, interceptor, client-side load balancing.
Ở đây codegen đã có sẵn từ Protobuf envelope, deadline vô nghĩa với stream sống suốt phiên,
LB do Cluster Sharding lo. Thứ duy nhất còn lại là **flow-control chồng hai tầng** —
HTTP/2 window *và* app-level window — khiến lúc nghẽn rất khó xác định tầng nào đang chặn.
Đó là một khoản nợ, không phải một lợi ích.

**Cái giá**: tự viết framing và quản lý vòng đời connection. Chấp nhận được vì Netty đã có sẵn
`LengthFieldBasedFrameDecoder` / `LengthFieldPrepender` — không tự viết parser.

### ADR-2 · Pekko Typed + Cluster Sharding, bắt buộc kèm SBR + fencing token

Actor framework chuẩn hoá cho mô hình 1 Room = 1 Actor: mailbox, sharding, rebalancing, FSM
đều có sẵn. Tự dựng bằng goroutine/channel hoặc thread pool là tự viết lại đúng những thứ này
và tự chuốc lấy goroutine leak / deadlock.

**Ràng buộc không thể tách rời**: Cluster Sharding **không được bật nếu thiếu Split Brain
Resolver và fencing token** (§9.1). Sharding trần là đường thẳng tới hai actor cùng `room_id`
chạy song song trên hai pod.

**Cái giá**: cluster luôn-bật, không scale xuống 0 được (phải giữ quorum). Ảnh hưởng trực tiếp
tới chi phí nhàn rỗi — xem §17.3.

### ADR-3 · Mục tiêu recovery là "mất dữ liệu = 0", không phải "khôi phục dưới 50ms"

Thời gian khôi phục thật của một cluster stateful:

```
T_total = T_detect + T_down + T_rebalance + T_load + T_reconnect

  T_detect     failure detector nhận ra node chết               5 – 10 s
  T_down       SBR quyết định + down node (stable-after)        5 – 10 s
  T_rebalance  ShardRegion tái phân bổ shard                    0,5 – 2 s
  T_load       Valkey read + deserialize snapshot                10 – 50 ms
  T_reconnect  client phát hiện đứt + backoff + nối lại         1 – 2 s
  ──────────────────────────────────────────────────────────────────────
  T_total ≈ 12 – 25 s
```

`T_load` — phần duy nhất mà các con số "50ms" từng đo — là phần **rẻ nhất và không chi phối**.
Không thể ép `T_detect` xuống 1 giây: false positive khi GC pause hoặc network blip sẽ down
một node đang khoẻ và làm mất hàng loạt phòng. Đây không phải vấn đề tuning mà là đánh đổi cố
hữu của failure detection.

**Vì vậy mục tiêu được phát biểu lại**: *học sinh không mất dữ liệu và không mất phiên khi hệ
thống gián đoạn 15–25 giây.* Cơ chế thực hiện ở §9.3.

**Cái giá**: phải chấp nhận và thiết kế cho một khoảng gián đoạn nhìn thấy được — overlay
"Đang đồng bộ…", gia hạn deadline câu hỏi. Không được giấu nó bằng một con số SLA đẹp.

### ADR-4 · Tick coalescing (dirty-flag) là mặc định

200ms là **trần tần suất**, không phải nhịp phát. Phòng không có gì thay đổi phát **0 gói**.
Fixed-rate tick chỉ bật cho game chuyển động liên tục, khai báo trong Game Definition
(`tick_mode: COALESCE | FIXED`) — không phải cấu hình toàn cục. Chi tiết và số liệu ở §6.

**Cái giá**: logic flush phức tạp hơn một `scheduleAtFixedRate`. Đổi lại giảm ~10× outbound.

---

## 3. Kiến trúc tổng thể

### 3.1. Sơ đồ thành phần

```text
   ┌──────────────────────────────────────────────────────────────────────────────┐
   │  CLIENT — Flutter Native (WebSocket + Protobuf) · WebView Bridge · Web        │
   └───────────────┬──────────────────────────────────────────┬───────────────────┘
                   │ WSS + Protobuf (nhị phân)                │ REST/HTTPS
                   │ hot path                                 │ auth, hồ sơ, ghép trận
                   ▼                                          ▼
   ┌───────────────────────────────────┐      ┌────────────────────────────────────┐
   │  NETTY GATEWAY  ×10–12 pod        │      │  DỊCH VỤ NỀN TẢNG (stateless)      │
   │  STATELESS                        │      │  • Auth / One-time join token          │
   │  • Terminate TLS + WS handshake   │      │  • Matchmaking & Party             │
   │  • Xác thực join token → ChannelAttr  │      │  • Player Profile & Progression    │
   │  • Rate limit phân tầng (§10.1)   │      │  • Leaderboard (Valkey ZSET)        │
   │  • Lazy-learned routing (§8.2)    │      └────────────────────────────────────┘
   │  • Fan-out zero-copy (§10.3)      │
   │  • Admission control (§10.4)      │
   │  ✗ KHÔNG chứa business logic      │
   └───────────────┬───────────────────┘
                   │ INTERNAL FRAME CHANNEL  (ADR-1)
                   │ TCP dài hạn · length-prefixed Protobuf
                   │ 1 connection / cặp (GW pod, Engine pod) · multiplex bằng room_id
                   │ backpressure MỘT tầng: channel.isWritable() + watermark
                   ▼
   ┌──────────────────────────────────────────────────────────────────────────────┐
   │  GAME ENGINE  ×12–16 pod  ·  Pekko Typed + Cluster Sharding  (ADR-2)         │
   │  ┌────────────────────────────────────────────────────────────────────────┐  │
   │  │  ShardRegion                                                            │  │
   │  │    RoomActor(room_id)  — 1 phòng = 1 actor đơn luồng                    │  │
   │  │      • FSM: LOBBY → PLAYING ⇄ RESYNCING → FINISHED                      │  │
   │  │      • state trong RAM: điểm, step, LastSeenSequenceTable, epoch        │  │
   │  │      • tick coalescing (ADR-4)                                          │  │
   │  │      • runtime guardrails cho Game Definition (§10.5)                   │  │
   │  ├────────────────────────────────────────────────────────────────────────┤  │
   │  │  SessionAggregator(session_id)  — Cluster Singleton per session (§11)   │  │
   │  ├────────────────────────────────────────────────────────────────────────┤  │
   │  │  Split Brain Resolver · fencing epoch · CoordinatedShutdown  (§9.1)     │  │
   │  └────────────────────────────────────────────────────────────────────────┘  │
   └──────┬──────────────────────────────┬─────────────────────────┬──────────────┘
          │ snapshot + lease/epoch       │ kết quả cuối phiên      │ event log
          │ (virtual thread I/O)         │ (async, ngoài hot path) │ (async, cắt được)
          ▼                              ▼                         ▼
   ┌──────────────┐            ┌──────────────────┐      ┌──────────────────────┐
   │ VALKEY       │            │ POSTGRESQL       │      │ KAFKA  (giai đoạn 2) │
   │ • snapshot   │            │ • tài khoản/lớp  │      │ • analytics/audit    │
   │ • epoch/lease│            │ • ngân hàng câu  │      │ • KHÔNG ở hot path   │
   │ • leaderboard│            │ • kết quả        │      │ • KHÔNG dùng cho     │
   │ • registry   │            │ • analytics      │      │   recovery (ADR-3)   │
   │   (vận hành) │            │   (partition/th) │      └──────────┬───────────┘
   └──────────────┘            └──────────────────┘                 │
                                                                    ▼
                                             ┌────────────────────────────────────┐
                                             │ WORKERS: leaderboard · audit ·     │
                                             │ analytics → PostgreSQL / MongoDB   │
                                             └────────────────────────────────────┘
```

### 3.2. Trách nhiệm từng tầng

| Tầng | Có trạng thái? | Trách nhiệm | Tuyệt đối không làm |
|---|---|---|---|
| **Netty Gateway** | Không *(trừ cache định tuyến tự lành)* | TLS, WS handshake, xác thực join token, rate limit, định tuyến, fan-out zero-copy, admission control | Chấm điểm, đọc luật chơi, gọi DB, giữ state phòng |
| **Game Engine** | **Có** — nguồn sự thật lúc chạy | Toàn bộ luật chơi, chấm điểm, FSM, tick, snapshot, guardrails | Chạm socket của client trực tiếp, gọi blocking I/O trên dispatcher của actor |
| **Valkey** | Có | Snapshot nóng, fencing epoch, leaderboard, session registry cho vận hành | **Nằm trên đường đi của gói tin** — registry chỉ phục vụ dashboard/vận hành |
| **PostgreSQL** | Có | Nguồn sự thật lâu dài: tài khoản, câu hỏi, kết quả, analytics partition theo tháng | Bị gọi đồng bộ trong hot path |
| **Kafka** | Có | Event log cho analytics và audit | **Tham gia vào recovery** — ADR-3 cấm |
| **Workers** | Không | Tiêu thụ event → leaderboard, audit, analytics | Ghi ngược vào state phòng |

### 3.3. Ba quyết định về những gì **không** có trong sơ đồ

| Thành phần | Trạng thái | Lý do |
|---|---|---|
| **Valkey Pub/Sub trên hot path** | Không dùng | Bớt một broker hop và xoá cả một lớp bug rò dữ liệu chéo phòng. Gateway nhận broadcast thẳng từ Engine qua kênh nội bộ |
| **ClickHouse** | **Đã cắt** | Analytics ghi vào PostgreSQL partition theo tháng. Cân nhắc lại kho OLAP khi log vượt ~10 GB/ngày — không sớm hơn |
| **Kafka ở giai đoạn 1** | Hoãn được thật | Sau khi recovery chuyển sang client-side replay (§9.3), Kafka không còn nằm trên bất kỳ đường đi quan trọng nào |

---

## 4. Mô hình định danh & vòng đời phòng

### 4.1. Bốn tầng định danh

| Tầng | Khoá | Ý nghĩa | Ánh xạ runtime |
|---|---|---|---|
| **World / Session** | `session_id`, `teacher_id` | Phiên điều phối của giáo viên, gom nhiều phòng | `SessionAggregator` — Cluster Singleton per session (§11) |
| **Room** | `room_id`, `game_id` | **Đơn vị cô lập nghiệp vụ cốt lõi** | `RoomActor` — 1 phòng = 1 actor |
| **Team** | `team_id` (`team_size=3`, `team_count=4`) | Nhóm nhỏ trong phòng | Cấu trúc dữ liệu **trong** state của `RoomActor`, không phải actor riêng |
| **Member** | `student_id`, `student_index` (0–11) | Học sinh | Entry trong state phòng + `Channel` ở Gateway |

`student_index` là chỉ số cục bộ trong phòng, dùng cho payload gọn (1 byte thay vì cả UUID).
Nó **được cấp khi vào phòng và không tái sử dụng trong cùng một phiên** — xem §12.3 để biết
vì sao ràng buộc này quan trọng.

### 4.2. FSM của RoomActor

```text
                    ┌─────────┐
       tạo phiên ──►│  LOBBY  │  actor được pre-spawn khi giáo viên tạo phiên,
                    └────┬────┘  KHÔNG phải khi học sinh đầu tiên vào (§10.4)
                         │ TEACHER_START
                         ▼
                    ┌─────────┐  ◄──── SUBMIT_ANSWER, UPDATE_DRAFT, JOIN muộn
                    │ PLAYING │  ────► broadcast theo tick coalescing (§6)
                    └──┬───┬──┘
        actor được     │   │ hết step cuối / TEACHER_END
        tái tạo ở pod  │   │
        khác           │   ▼
                       │  ┌──────────┐
                       │  │ FINISHED │ ──► ghi kết quả (async) ──► Behaviors.stopped()
                       │  └──────────┘
                       ▼
                 ┌───────────┐
                 │ RESYNCING │  nhận RESYNC, áp pending qua LastSeenSequenceTable,
                 └─────┬─────┘  CHƯA broadcast (§9.3)
                       │ gia hạn deadline đúng khoảng gián đoạn
                       └──────────────► PLAYING
```

`RESYNCING` là trạng thái v3.0 bổ sung so với FSM ba trạng thái thông thường. Nó tồn tại để
actor vừa hồi sinh **không broadcast một state chưa đầy đủ** ra cả phòng trước khi thu thập
xong pending submission từ client.

### 4.3. State của một phòng

Toàn bộ state nằm trong RAM của actor, và **toàn bộ phải nằm trong snapshot**:

| Trường | Vai trò | Ghi chú |
|---|---|---|
| `epoch` | Fencing token chống split-brain | §9.1 — không có nó thì snapshot có thể bị zombie ghi đè |
| `step_index`, `step_deadline_at` | Vị trí trong kịch bản | `deadline` là **thời điểm server**, không phải thời lượng |
| `server_question_started_at` | Mốc tính điểm theo tốc độ | §9.4 |
| `students[]` | `student_id`, `student_index`, điểm, trạng thái kết nối | |
| `teams[]` | Thành viên, nội dung nháp chung | |
| **`LastSeenSequenceTable`** | Chống trùng theo `(student_id → last_seq)` | **Bắt buộc nằm trong snapshot.** Thiếu nó thì sau recovery actor mất trí nhớ về sequence và chấp nhận lại bản trùng |
| `dirty`, `last_flush_at` | Trạng thái tick coalescing | §6.2 |

**Ràng buộc kích thước**: snapshot serialize + nén **< 5 KB** (§15.2). Đây là lý do chính sách
`ALLOW_LATE` ở §12.2 phải được cân nhắc — nó buộc actor giữ lại toàn bộ step đã qua.

---

## 5. Giao thức & kênh giao tiếp

### 5.1. Ba kênh

| Kênh | Giao thức | Giữa | Mục đích |
|---|---|---|---|
| **Biên realtime** | WSS + Protobuf nhị phân | Client ↔ Gateway | Toàn bộ hot path của trận đấu |
| **Internal Frame Channel** | TCP dài hạn + length-prefixed Protobuf | Gateway ↔ Engine | Định tuyến tới đúng `RoomActor`, backpressure một tầng (ADR-1) |
| **Điều khiển** | REST/HTTPS *(gRPC được phép cho dịch vụ stateless)* | Client / BFF ↔ dịch vụ nền tảng | Auth, ghép trận, hồ sơ, dashboard tĩnh |

### 5.2. Vì sao Native WebSocket + Protobuf, không Socket.io

| Tiêu chí | Native WS + Protobuf | Socket.io |
|---|---|---|
| Kích thước payload | Nhị phân, nhỏ hơn ~60–70% so với JSON | JSON (engine.io framing) |
| Client mobile/Unity | Chuẩn W3C, có thư viện ở mọi nền tảng | Cần client Socket.io riêng, chất lượng không đồng đều ngoài JS |
| Kiểm soát ở Netty | Trực tiếp `ByteBuf`, zero-copy fan-out | Bị lớp framing riêng che |
| Reconnect/room | Tự làm — và ở đây **cần** tự làm vì gắn với FSM phòng | Có sẵn nhưng ngữ nghĩa không khớp với room stateful của ta |

Cái giá: tự viết heartbeat, reconnect backoff và ánh xạ room. Chấp nhận vì cả ba đều phải gắn
chặt với FSM phòng (§4.2) — dùng cơ chế có sẵn của Socket.io vẫn phải viết lại lớp thích ứng.

### 5.3. Envelope thống nhất

**Một schema dùng cho cả biên và kênh nội bộ.** Không codegen hai lần, không dịch qua lại.

```protobuf
syntax = "proto3";
package uni.realtime.v1;

message GameMessage {
  MessageType type          = 1;
  string      room_id       = 2;   // khoá multiplex trên Internal Frame Channel
  string      student_id    = 3;
  uint32      student_index = 4;   // 0..11, chỉ số cục bộ trong phòng

  // Chống trùng hai tầng (§9.5). Client tăng đơn điệu cho mỗi lệnh có tác dụng phụ.
  uint64      sequence      = 5;

  // ⚠ CHỈ dùng để đo latency / telemetry.
  // TUYỆT ĐỐI KHÔNG dùng để tính điểm — điểm tính từ server_received_at (§9.4).
  // Client kiểm soát được trường này; tin nó là mở cửa cho gian lận.
  int64       client_timestamp_ms = 6;

  oneof payload {
    JoinRoom            join            = 10;
    SubmitAnswer        submit          = 11;
    UpdateDraft         draft           = 12;
    Resync              resync          = 13;
    RoomStateSnapshot   snapshot        = 20;
    AnswerAck           ack             = 21;
    QuestionStarted     question        = 22;
    StudentJoined       student_joined  = 23;
    GameOver            game_over       = 24;
    ConnectionDegraded  degraded        = 25;
    TeacherCommand      teacher_command = 30;
  }
}

// Trường routing chỉ có nghĩa trên kênh nội bộ, bỏ qua ở biên.
message InternalHeader {
  string owner_pod_id = 1;   // Engine đóng dấu → Gateway học route (§8.2)
  uint64 epoch        = 2;   // fencing token của actor đang phục vụ (§9.1)
}
```

**Framing kênh nội bộ**: 4-byte big-endian length prefix, dùng
`LengthFieldBasedFrameDecoder` / `LengthFieldPrepender` của Netty. Không tự viết parser.

### 5.4. Phân loại thông điệp — Critical vs Best-effort

Phân loại này quyết định thông điệp nào được đi qua tick coalescing và thông điệp nào bị bỏ
khi backpressure bật:

| Lớp | Thông điệp | Hành vi |
|---|---|---|
| **Critical** | `ANSWER_ACK`, `GAME_OVER`, `TEACHER_COMMAND`, `QUESTION_STARTED`, `CONNECTION_DEGRADED` | **Bypass hoàn toàn** tick coalescing. **Không bao giờ bị drop** khi backpressure — nếu channel không ghi được thì đóng channel, không im lặng bỏ gói |
| **Best-effort** | `ROOM_STATE_SNAPSHOT` (delta), `UPDATE_DRAFT` lan truyền, cập nhật thanh tiến trình | Gom qua tick coalescing. **Được phép drop** khi `!channel.isWritable()` — client sẽ nhận state đúng ở snapshot kế tiếp |

Ranh giới này càng quan trọng sau ADR-4: khi phòng im lặng phát 0 gói, thứ duy nhất đảm bảo
học sinh biết bài đã được nhận là `ANSWER_ACK` — nó phải là Critical.

---

## 6. Mô hình tải & tick coalescing

### 6.1. Tải thực tế

```
THAM SỐ
  R = 4.500 phòng đồng thời
  S = 12 học sinh/phòng
  Q = 25 s   thời gian trung bình cho một câu hỏi
  U = 54.000 CCU = R × S

INBOUND  (client → server)
  submit     = U / Q  = 54.000 / 25   ≈  2.160 msg/s
  heartbeat  = U / 30s                ≈  1.800 msg/s
  ─────────────────────────────────────────────────────
  TỔNG INBOUND                        ≈  4.000 msg/s

OUTBOUND (server → client), với tick coalescing
  mỗi submit sinh 1 broadcast tới S client
  outbound   = 2.160 × 12             ≈ 26.000 packet/s
  (coalescing gom nhiều submit gần nhau → thực tế còn thấp hơn)
```

**Hai con số cần dùng khi thiết kế capacity: ~4.000 msg/s inbound, ~26.000 packet/s outbound.**

Đây là hệ thống **nhẹ hơn nhiều** so với cảm giác mà "54.000 CCU" gợi ra. Phần lớn CCU đang
*ngồi im đọc câu hỏi*. Điều này định hình lại toàn bộ bài toán: nút thắt không nằm ở throughput
steady state mà ở **connection storm** (§10.4) và **blast radius khi sập pod** (§14.1).

### 6.2. Tick coalescing

Với quiz, giữa hai lần nộp bài **không có gì để nội suy**. Một fixed-rate tick chỉ gửi lại
state không đổi:

| Mô hình | Outbound @ 54k CCU |
|---|---|
| Fixed-rate 200ms | 4.500 × 12 × 5 = **270.000 packet/s** — liên tục, kể cả khi cả phòng im lặng |
| **Coalescing (ADR-4)** | **≈ 26.000 packet/s** |

Fixed-rate nặng hơn **~10 lần** cho đúng loại game mà nền tảng nhắm tới. Nó là pattern đúng cho
game *chuyển động liên tục*, nơi client cần luồng snapshot đều để lerp — không phải cho quiz.

```java
// RoomActor — 200ms là TRẦN TẦN SUẤT, không phải nhịp phát
private boolean dirty = false;
private boolean flushScheduled = false;
private long lastFlushAt = 0;
private static final long MIN_INTERVAL_MS = 200;

void onSubmit(SubmitAnswer cmd) {
    applyToState(cmd);
    dirty = true;
    long now = clock.millis();
    if (now - lastFlushAt >= MIN_INTERVAL_MS) {
        flush(now);                       // đủ giãn cách → bắn ngay
    } else if (!flushScheduled) {
        timers.startSingleTimer(FLUSH_KEY, Flush.INSTANCE,
            Duration.ofMillis(MIN_INTERVAL_MS - (now - lastFlushAt)));
        flushScheduled = true;
    }
}

void onFlush() {
    flushScheduled = false;
    if (!dirty) return;                   // phòng im lặng → 0 packet
    flush(clock.millis());
}

void flush(long now) {
    broadcast(buildDeltaSnapshot());      // delta, không phải full state
    dirty = false;
    lastFlushAt = now;
}
```

Trải nghiệm client **không đổi** — độ trễ tối đa vẫn là 200ms — nhưng tải nền biến mất.

### 6.3. Khi nào bật fixed-rate

Chỉ cho game chuyển động liên tục (Boss Battle, đua theo vị trí thật). Khai báo trong
**Game Definition**, không phải cấu hình toàn cục:

```yaml
tick_mode: COALESCE      # mặc định
# tick_mode: FIXED
# tick_interval_ms: 100  # chỉ có nghĩa khi tick_mode = FIXED
```

Mỗi Game Definition bật `FIXED` phải được tính lại capacity riêng — nó không dùng chung ngân
sách outbound với các game COALESCE.

### 6.4. Tối ưu payload

| Kỹ thuật | Áp dụng | Ghi chú |
|---|---|---|
| **Delta snapshot** | Mặc định | Chỉ gửi trường đổi kể từ lần flush trước. Full snapshot chỉ gửi khi JOIN, RESYNC, hoặc mỗi N lần flush để tự sửa lệch |
| **`student_index` thay `student_id`** | Mặc định | 1 byte thay vì UUID trong mọi payload trong phòng |
| **Nén LZ4** | Chỉ khi payload > 150 B | Dưới ngưỡng đó chi phí CPU lớn hơn phần tiết kiệm. Bật ở giai đoạn 3 |
| **Zero-copy fan-out** | Mặc định | §10.3 |

---

## 7. Đường đi của một gói tin

### 7.1. Vào phòng

```
1. Client  →  POST /session/{id}/join           (REST, ngoài hot path)
              ← one-time join token (TTL 30s, dùng một lần)
              ← connect_after_ms: jitter 0–5.000ms      ← staggered join (§10.4)

2. Client đợi connect_after_ms, rồi mở WSS tới Gateway.

3. Gateway: TLS → WS handshake → đổi join token lấy danh tính
            → ghi vào ChannelAttributes: student_id, room_id, session_id, roles
            → từ thời điểm này Gateway KHÔNG parse lại token cho mỗi gói

4. Gateway  →  Internal Frame Channel  →  ShardRegion  →  RoomActor(room_id)
            (chưa biết pod nào giữ phòng → gửi round-robin, ShardRegion tự forward)

5. RoomActor: cấp student_index, thêm vào state, trả ROOM_STATE_SNAPSHOT đầy đủ.
              Response mang InternalHeader.owner_pod_id → Gateway HỌC route (§8.2)

6. RoomActor broadcast STUDENT_JOINED cho cả phòng (best-effort).
```

### 7.2. Nộp một đáp án

```
1. Client:  SubmitAnswer { sequence: N, answer: "B" }
            → đưa vào ring buffer chờ ACK (§9.3), hiển thị Optimistic UI ngay

2. Gateway: kiểm rate limit theo student_id (§10.1)
            → đọc room_id TỪ ChannelAttributes, KHÔNG tin room_id trong payload (§10.6)
            → tra cache route → gửi thẳng tới Engine pod đang giữ phòng

3. RoomActor (đơn luồng, tuần tự):
     a. server_received_at = clock.millis()        ← NGAY khi lấy khỏi mailbox (§9.4)
     b. LastSeenSequenceTable: sequence ≤ last_seen → bỏ, gửi lại ACK cũ. Kết thúc.
     c. server_received_at > deadline + GRACE(500ms) → từ chối, ACK kèm lý do
     d. chấm điểm:  response_time_ms = server_received_at − server_question_started_at
     e. cập nhật state, dirty = true

4. ANSWER_ACK  → Critical, bypass coalescing, gửi thẳng về đúng client đó
                 → client xoá submission khỏi ring buffer

5. Delta snapshot → best-effort, gom theo tick coalescing (§6.2)
                    → Gateway fan-out zero-copy tới các client cục bộ (§10.3)
```

**Không có Valkey, Kafka hay DB nào trong luồng trên.** Snapshot Valkey được ghi bất đồng bộ
(mỗi 1–3 giây) trên virtual thread, ngoài đường đi của gói tin.

### 7.3. Qua nhiều pod

Một phòng có 12 học sinh có thể phân bố trên nhiều Gateway pod, nhưng **luôn chỉ có một
RoomActor**:

```
   HS 1-5      HS 6-9        HS 10-12
      │           │              │
      ▼           ▼              ▼
   [GW pod A]  [GW pod B]   [GW pod C]
      └───────────┼──────────────┘
                  │  Internal Frame Channel (mỗi GW một connection tới pod E2)
                  ▼
            [Engine pod E2]
              RoomActor(101)      ← điểm tuần tự hoá duy nhất của phòng
                  │
      ┌───────────┼──────────────┐   broadcast: Engine gửi MỘT gói tới MỖI GW pod
      ▼           ▼              ▼   có client của phòng, GW mới fan-out cục bộ
   [GW A]      [GW B]        [GW C]
   5 client    4 client      3 client
```

Engine gửi **một gói cho mỗi Gateway pod**, không phải một gói cho mỗi client. Với phòng 12
người trải trên 3 pod: 3 gói qua kênh nội bộ thay vì 12. Gateway chịu trách nhiệm nhân bản
zero-copy ở tầng cuối (§10.3).

---

## 8. Định tuyến & Cluster Sharding

### 8.1. Vì sao không dùng consistent hashing ở Gateway

Với Cluster Sharding, **Pekko quyết định vị trí actor, không phải hàm hash của Gateway**. Một
vòng hash ở Gateway sẽ trỏ sai sau mỗi lần rebalance (thêm/bớt pod, node down) và **không có
cách nào tự biết**. Đó là hai nguồn sự thật cho cùng một câu hỏi "phòng này đang ở đâu".

### 8.2. Lazy-learned routing

```
1. Gateway giữ connection tới TẤT CẢ Engine pod. Số pod nhỏ (12–16) nên chi phí không đáng kể.

2. Chưa biết phòng → gửi tới pod bất kỳ (round-robin).
   ShardRegion tự forward nội bộ tới đúng node. Đúng một hop thừa, chỉ lần đầu.

3. Response mang InternalHeader.owner_pod_id do Engine đóng dấu.
   Gateway cache  room_id → pod.  Từ đó gửi thẳng, không còn hop nội bộ.

4. Rebalance làm cache sai → pod nhận được hoặc trả NOT_OWNER kèm owner hiện tại,
   hoặc đơn giản forward tiếp rồi đóng dấu lại → Gateway tự cập nhật.

5. Connection tới một Engine pod đứt → xoá mọi entry cache trỏ tới pod đó.
```

Ba tính chất quan trọng của cơ chế này:

| Tính chất | Vì sao có |
|---|---|
| **Không cần Valkey trên hot path** | Cache nằm trong RAM của Gateway |
| **Không cần TTL** | Entry sai tự sửa ở lần dùng kế tiếp, không cần hết hạn theo thời gian |
| **Không thể lệch khỏi sự thật** | Sự thật do chính Engine đóng dấu vào response — không có bản sao thứ hai để lệch |

Valkey vẫn giữ `room:routing:{room_id}` cho **dashboard giáo viên và vận hành**, nhưng nó
không nằm trên đường đi của gói tin.

### 8.3. Cấu hình Cluster Sharding

```hocon
pekko.cluster.sharding {
  number-of-shards = 1000            # ~3-4× số node tối đa dự kiến; KHÔNG đổi được sau khi chạy
  remember-entities = off            # actor được tái tạo khi có message, không tự động
  passivate-idle-entity-after = off  # phòng FINISHED tự stop, không cần passivation theo thời gian
}
```

> [!WARNING]
> `number-of-shards` **không thể đổi sau khi cluster đã chạy** mà không mất toàn bộ phân bổ.
> Chọn một lần, chọn rộng tay.

---

## 9. Tính đúng đắn dưới sự cố

Đây là phần quan trọng nhất tài liệu. Bốn hazard dưới đây đều có thể gây **sai kết quả học
tập** — nghiêm trọng hơn nhiều so với chậm hay gián đoạn.

### 9.1. Split-brain — hai actor cùng một phòng

Khi network partition xảy ra, hai phía cluster đều tin phía kia đã chết → **hai
`RoomActor(101)` cùng chạy trên hai pod**. Hậu quả: chấm điểm hai lần, hai nguồn broadcast mâu
thuẫn, hai luồng ghi snapshot đè lên nhau.

**Hai lớp bảo vệ, cả hai đều bắt buộc.**

#### Lớp 1 — Split Brain Resolver

```hocon
pekko.cluster {
  downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"

  split-brain-resolver {
    active-strategy        = keep-majority
    stable-after           = 10s      # KHÔNG hạ xuống 1s
    down-all-when-unstable = on
  }

  failure-detector {
    acceptable-heartbeat-pause = 5s   # nhạy hơn nữa = false positive khi GC pause
    threshold                  = 10.0
  }
}
```

`keep-majority` — phía thiểu số tự down. Yêu cầu số node lẻ hoặc `min-number-of-members` rõ
ràng, và **không được scale cluster xuống dưới quorum**. Ràng buộc này ăn thẳng vào bài toán
chi phí ở §17.3.

#### Lớp 2 — Fencing token

SBR đảm bảo *cuối cùng* chỉ còn một node, nhưng tồn tại cửa sổ `stable-after` (10 giây) mà cả
hai actor cùng sống và cùng ghi Valkey. Vì vậy **mọi write phải mang epoch**:

```
Khi RoomActor khởi động:
  epoch = INCR room:epoch:{room_id}     -- đơn điệu tăng, không bao giờ lặp
  actor giữ epoch trong RAM suốt vòng đời
```

```lua
-- Mọi ghi snapshot đi qua Lua script CAS này
if redis.call('EXISTS', KEYS[1]) == 0
   or tonumber(redis.call('HGET', KEYS[1], 'epoch')) <= tonumber(ARGV[1]) then
     redis.call('HSET', KEYS[1], 'epoch', ARGV[1], 'data', ARGV[2], 'crc32', ARGV[3])
     return 1
else
     return 0     -- epoch cũ → từ chối
end
```

**Actor nhận về `0` → tự biết mình là bản zombie → dừng ngay, không broadcast thêm gói nào.**

> [!NOTE]
> Snapshot versioning + CRC32 (§9.6) bảo vệ chống **hỏng dữ liệu**. Fencing epoch bảo vệ chống
> **hai người cùng ghi**. Hai vấn đề khác nhau, cần hai cơ chế khác nhau.

#### Lớp 3 — Vận hành

```yaml
# Engine Deployment
terminationGracePeriodSeconds: 45     # đủ để CoordinatedShutdown leave cluster sạch
```
```hocon
pekko.coordinated-shutdown.exit-jvm = on
pekko.cluster.shutdown-after-unsuccessful-join-seed-nodes = 40s
```

Không có graceful leave thì **mỗi lần rolling update là một lần nghi ngờ split-brain**.

### 9.2. Vì sao recovery không dùng Kafka

Snapshot ghi async (mỗi 1–3 giây), Kafka produce cũng async. Hai thao tác này **không nằm
trong một transaction**, nên `last_kafka_offset` lưu trong snapshot có thể không tương ứng với
state trong chính snapshot đó:

* offset ghi **trước** khi produce xong → replay bỏ sót sự kiện → **mất đáp án**;
* offset ghi **sau** → replay lặp sự kiện → cần idempotent replay.

Cộng thêm việc Kafka bị hoãn ở giai đoạn 1, recovery dựa vào Kafka nghĩa là **giai đoạn 1
không có recovery path** và mất tới 3 giây đáp án. Với bài kiểm tra có điểm, đáp án học sinh
biến mất là lỗi nghiệp vụ.

### 9.3. Recovery: client-side replay

**Nguồn đúng đắn chính là client, không phải snapshot.**

```
1. Client giữ ring buffer N=10 submission gần nhất, kèm sequence_number.
2. Submission bị xoá khỏi buffer khi nhận ANSWER_ACK.
3. Khi reconnect, client gửi RESYNC { last_acked_seq, pending[] }.
4. Actor áp pending[] qua LastSeenSequenceTable → bản trùng bị loại O(1).
5. Actor trả state hiện tại → client vẽ lại UI.
```

Luồng khôi phục đầy đủ khi một Engine pod chết:

```
1. Cluster Sharding tạo lại actor trên pod còn sống.
2. Giành lease + epoch:  INCR room:epoch:{room_id}          (§9.1)
3. Nạp snapshot Valkey, kiểm CRC32.
   Checksum sai → bắt đầu từ state rỗng, dựa hoàn toàn vào bước 5.
4. Vào trạng thái RESYNCING: nhận RESYNC, CHƯA broadcast.
5. Client gửi RESYNC → áp qua LastSeenSequenceTable.
6. Gia hạn deadline câu hỏi hiện tại đúng bằng khoảng gián đoạn.
7. Về PLAYING, broadcast state đầy đủ một lần cho toàn phòng.
```

**Kafka không xuất hiện ở bất kỳ bước nào.** Snapshot Valkey trở thành *tối ưu tốc độ* (đỡ phải
replay nhiều từ client), không phải *nguồn đúng đắn*.

Trải nghiệm phía học sinh trong 15–25 giây gián đoạn:

| Cơ chế | Trách nhiệm |
|---|---|
| Client buffer | Giữ submission chưa ACK, tự gửi lại khi nối lại |
| Server dedupe | `LastSeenSequenceTable` loại bản trùng — O(1), không cần Valkey |
| UI | Overlay "Đang đồng bộ…". **Không văng lỗi, không mất đáp án đã chọn** |
| Gia hạn deadline | Deadline câu hỏi được cộng bù đúng khoảng gián đoạn |

### 9.4. Server-authoritative timestamp

Điểm số phụ thuộc tốc độ trả lời. Nếu thời điểm trả lời do client cung cấp thì học sinh sửa
được điểm bằng cách sửa timestamp — một lỗ hổng gian lận trực tiếp trong hệ thống có nguyên
tắc "Authoritative Server".

```
1. Actor ghi server_question_started_at khi phát QUESTION_STARTED.
2. Khi SubmitAnswer tới, actor đóng dấu server_received_at = clock.millis()
   NGAY tại thời điểm lấy khỏi mailbox — KHÔNG dùng client_timestamp_ms.
3. response_time_ms = server_received_at − server_question_started_at
4. Từ chối nếu server_received_at > deadline + GRACE   (GRACE = 500ms, bù RTT).
5. client_timestamp_ms CHỈ dùng cho telemetry — không bao giờ dùng để tính điểm.
```

Điều 5 phải được ghi thành comment ngay trong `.proto` (§5.3), nếu không sẽ có người dùng nhầm.

> [!NOTE]
> **Về công bằng**: cách tính này khiến học sinh mạng chậm chịu thiệt — RTT tính vào thời gian
> trả lời. Đây là đánh đổi bắt buộc của authoritative server, và `GRACE` hấp thụ phần lớn
> chênh lệch. **Không được "sửa" bằng cách tin timestamp của client.**

### 9.5. Chống trùng hai tầng

| Tầng | Cơ chế | Chi phí |
|---|---|---|
| **Client sequence** | Mỗi lệnh có tác dụng phụ mang `sequence` tăng đơn điệu | 8 byte/gói |
| **Server state table** | `LastSeenSequenceTable`: `student_id → last_seq` trong RAM actor | O(1), 12 entry/phòng |

`sequence ≤ last_seen` → bỏ qua và **gửi lại ACK cũ** (không im lặng — client đang chờ ACK để
xoá buffer). Bảng này **bắt buộc nằm trong snapshot** (§4.3).

Đây cũng là nền tảng của toàn bộ §9.3: có nó thì client replay thoải mái mà không sợ tính điểm
hai lần.

### 9.6. Snapshot versioning & checksum

```
snapshot = { schema_version, epoch, crc32, payload }
```

| Trường | Bảo vệ chống |
|---|---|
| `schema_version` | Đọc snapshot của phiên bản code cũ sau khi deploy → từ chối, bắt đầu state rỗng + RESYNC |
| `epoch` | Zombie actor ghi đè (§9.1) |
| `crc32` | Dữ liệu hỏng / ghi dở → bắt đầu state rỗng + RESYNC |

Cả ba trường hợp lỗi đều dẫn về cùng một hành vi an toàn: **state rỗng + dựa vào client
replay**. Không có nhánh nào cố "sửa" một snapshot đáng ngờ.

### 9.7. Khi Engine pod không phản hồi

Circuit breaker chỉ có nghĩa khi **có nơi khác để đi**. Room state là stateful và chỉ tồn tại
ở đúng một nơi — không có Engine pod thay thế để chuyển sang. Vì vậy Gateway **không** mở
circuit breaker cho room traffic:

```
1. GW KHÔNG mở circuit breaker cho room traffic — không có fallback.
2. GW gửi CONNECTION_DEGRADED (Critical) tới client thuộc phòng trên pod đó.
3. Client hiện "Đang kết nối lại…", GIỮ NGUYÊN đáp án đã chọn trong buffer.
4. GW GIỮ NGUYÊN WebSocket của client — KHÔNG ngắt.
5. Khi Cluster Sharding tái tạo actor ở pod khác, GW học lại route (§8.2).
6. Client gửi RESYNC → không mất đáp án nào.
```

> [!CAUTION]
> **Bước 4 là bước dễ làm sai nhất.** Ngắt socket khi Engine pod chết sẽ tạo ra hàng chục
> nghìn lượt reconnect + TLS handshake đồng loạt, biến **sự cố một pod thành sự cố toàn hệ
> thống** (§10.4). Socket của client không liên quan gì tới sức khoẻ của Engine pod.

Circuit breaker **vẫn dùng** cho các lời gọi *stateless* thật sự: Auth service, Profile
service. Không dùng cho kênh Gateway ↔ Engine.

---

## 10. Bảo vệ runtime

### 10.1. Rate limiting phân tầng

**Khoá theo danh tính, không khoá theo IP làm tầng chính.** Một trường 500 học sinh đi ra
Internet qua một IP public NAT duy nhất — giới hạn theo IP sẽ chặn đứng chính khách hàng mục
tiêu.

| Tầng | Khoá | Ngưỡng | Mục đích |
|---|---|---|---|
| **L1** — chống DDoS thô | IP | **300 handshake/phút** | Chỉ chặn flood thật. Đặt theo **số học sinh tối đa của một trường**, không theo trực giác về IP |
| **L2** — chống lạm dụng tài khoản | `student_id` (từ join token) | **10 handshake/phút** | Chống reconnect loop, script |
| **L3** — bảo vệ dung lượng | Toàn cục/pod | Admission control | §10.4 |

Nếu có trường lớn hơn 300 học sinh đồng thời, **nâng L1 hoặc bỏ hẳn L1** và chỉ dựa vào L2+L3.
L1 là tầng ít giá trị nhất trong ba tầng.

**Rate limit theo thông điệp** (trong phiên, khoá theo `student_id`):

| Loại | Capacity | Refill | Ghi chú |
|---|---|---|---|
| `SUBMIT_ANSWER` | 3 | 1/s | Hành động hiếm, siết chặt được |
| `UPDATE_DRAFT` | 10 | 10/s | Client **bắt buộc debounce 150ms** và gửi **delta** |
| `HEARTBEAT` | 2 | 1/30s | |
| Tổng mọi loại | 15 | 15/s | Hàng rào cuối |

> [!IMPORTANT]
> Ràng buộc client ở dòng `UPDATE_DRAFT` **là một phần của thiết kế server**, không phải gợi ý
> cho frontend. Không có debounce 150ms + delta, gõ nháp chung sinh sự kiện theo nhịp gõ phím
> và **không ngưỡng server nào đúng cả**.

### 10.2. Backpressure một tầng

```
RoomActor mailbox đầy  →  Engine ngừng đọc từ Internal Frame Channel
                       →  socket buffer đầy  →  TCP window đóng
                       →  Gateway thấy !channel.isWritable()
                       →  Gateway bật autoRead(false) trên WebSocket của client
```

Toàn bộ chuỗi chỉ có **một cơ chế backpressure duy nhất**: watermark trên socket. Đây là lợi
ích chính của ADR-1 — với gRPC sẽ có HTTP/2 flow-control window *và* app-level window chồng
lên nhau, và lúc nghẽn rất khó biết tầng nào đang chặn.

Ngưỡng: `WRITE_BUFFER_WATER_MARK = (32 KB low, 64 KB high)` cho mỗi channel, đo lại ở H1.

### 10.3. Fan-out zero-copy

```java
ByteBuf frame = buildFrame(payload);
try {
    for (Channel ch : roomChannels) {
        if (!ch.isWritable()) {          // backpressure: chỉ bỏ được Best-effort
            dropNonCritical(ch, payload);
            continue;
        }
        ch.writeAndFlush(new BinaryWebSocketFrame(frame.retainedDuplicate()));
    }
} finally {
    frame.release();     // nhả bản gốc — thiếu dòng này là rò rỉ direct memory
}
```

> [!CAUTION]
> **Phải dùng `retainedDuplicate()`, không dùng `retain()`.**
> `retain()` chỉ tăng reference count — nó **không tạo reader index riêng**. Nhiều
> `writeAndFlush` trên *cùng một* `ByteBuf`: client đầu tiên đọc hết buffer đẩy `readerIndex`
> tới cuối, **các client sau ghi 0 byte**. Lỗi này biểu hiện dưới dạng *"một số học sinh không
> nhận được update"* — cực khó truy vì nó phụ thuộc thứ tự và thời điểm.
>
> `retainedDuplicate()` vẫn chia sẻ vùng nhớ (vẫn zero-copy) nhưng mỗi bản có reader/writer
> index độc lập.

**Bắt buộc**: bật `-Dio.netty.leakDetection.level=paranoid` trên staging. Không có nó, rò rỉ
direct memory chỉ lộ ra sau vài giờ chạy tải.

### 10.4. Connection storm đầu giờ

Tải EdTech không phẳng. 09:00 giáo viên bấm Bắt đầu → **54.000 kết nối trong ~15 giây**:

```
54.000 TLS handshake        ← đắt nhất, ~1–3ms CPU mỗi lượt
54.000 xác thực join token
 4.500 RoomActor spawn
 4.500 Valkey write (lease + epoch)
```

3.600 handshake/s chia cho 10 GW pod = **360 handshake/s/pod trên 2 vCPU**. Riêng TLS đã có
thể chiếm hết CPU — trong khi giả thuyết H1 chỉ đo steady state. **Đây là thời điểm khó nhất
của hệ thống, không phải giờ cao điểm.**

> [!NOTE]
> **Giai đoạn 1 chốt terminate TLS ở LB/ingress**, Gateway pod nhận WS plaintext. Phần CPU
> TLS ở trên **chuyển sang lớp ingress**, không biến mất — connection storm vẫn là thời điểm
> khó nhất, chỉ đổi chỗ nút thắt. Ba hệ quả:
> 1. Ngưỡng `max_handshake_per_sec` của admission control (cơ chế 3) phải đo lại **ở ingress**,
>    không phải ở pod. PH-1 vẫn chặn con số này.
> 2. Cơ chế 4 (TLS session resumption) là **cấu hình của ingress**, không phải của Gateway.
> 3. Ingress phải passthrough WebSocket upgrade và có idle-timeout **lớn hơn** chu kỳ heartbeat,
>    nếu không LB sẽ cắt connection đang khoẻ.
>
> Quyết định này chỉ ràng buộc Giai đoạn 1 — xem `docs/work/NOJIRA-uni-p1-realtime-core/_context.md`.

| # | Cơ chế | Tác dụng |
|---|---|---|
| **1** | **Pre-spawn actor lúc tạo phiên** | Giáo viên tạo phiên → actor spawn ngay, ở `LOBBY`. Tới 09:00 chỉ còn chi phí kết nối, không còn 4.500 lượt spawn dồn cục |
| **2** | **Staggered join do server điều phối** | `POST /session/{id}/join` trả kèm `connect_after_ms` (jitter 0–5.000ms). Trải 54.000 kết nối ra 5 giây. Học sinh không cảm nhận được vì màn hình chờ vẫn hiển thị |
| **3** | **Admission control ở Gateway** | Vượt `max_handshake_per_sec` (đo ở H1) → trả **`503 + Retry-After`**, **không phải 429**. 429 khiến client hiểu nhầm là bị phạt. Thà chậm 3 giây còn hơn pod chết |
| **4** | **TLS session resumption** | Bật session join token / session ID. Reconnect sau đứt mạng bỏ qua full handshake — giảm phần lớn CPU của kịch bản hàng nghìn client nối lại cùng lúc sau khi Engine pod phục hồi (§9.3) |

### 10.5. Runtime guardrails cho Game Definition

Game Definition là dữ liệu do người vận hành upload, không phải code đã review. Bốn rào chắn:

| # | Rào chắn | Thời điểm |
|---|---|---|
| 1 | **Validate DAG các step** — phát hiện chu trình bằng duyệt đồ thị | **Lúc upload** |
| 2 | **Cấm biểu thức tuỳ ý** — công thức điểm dùng DSL giới hạn, không Turing-complete, **không script engine** | **Lúc upload** |
| 3 | **`MAX_TRANSITIONS`** cho mỗi phiên — hàng rào cuối chống vòng lặp state | Lúc chạy |
| 4 | **Dispatcher riêng** cho logic từ Definition | Lúc chạy |

```hocon
game-logic-dispatcher {
  type     = Dispatcher
  executor = "fork-join-executor"
  fork-join-executor { parallelism-min = 4, parallelism-max = 16 }
  throughput = 1
}
```

> [!WARNING]
> **Watchdog đo và cảnh báo — nó không ngắt được logic đang chạy.**
> Không có cách an toàn nào để ngắt code Java đang chạy trên thread khác: `Thread.stop()` đã
> bị gỡ bỏ, `Thread.interrupt()` chỉ tác dụng tại điểm blocking và một vòng lặp CPU sẽ phớt lờ
> nó hoàn toàn. Nếu Game Definition có vòng lặp vô tận, actor **sẽ treo** bất chấp watchdog.

```java
long t0 = System.nanoTime();
state.handle(msg);
long durUs = (System.nanoTime() - t0) / 1000;
actorProcessingTimer.record(durUs, MICROSECONDS);
if (durUs > 10_000) {
    log.warn("room={} msg={} over budget {}us", roomId, msg.type(), durUs);
}
```

Việc **phòng ngừa** nằm ở rào chắn 1 và 2 (lúc upload). Việc **cách ly** nằm ở rào chắn 4 +
liveness probe → K8s restart pod. Chấp nhận mất một pod, không mất cluster.

### 10.6. Chống đẩy nhầm phòng

Rò dữ liệu chéo phòng trong EdTech nghĩa là **học sinh phòng A nhìn thấy đáp án phòng B**.
Ba lớp, mỗi lớp đủ để chặn một mình:

| Lớp | Quy tắc |
|---|---|
| **1 — Nguồn `room_id`** | Gateway đọc `room_id` **từ `ChannelAttributes`** (ghi lúc handshake), **không bao giờ tin `room_id` trong payload**. Payload có `room_id` khác attribute → đóng channel và log cảnh báo bảo mật |
| **2 — Sổ đăng ký cục bộ** | Gateway giữ `Map<room_id, Set<Channel>>` **cục bộ trong pod**. Fan-out chỉ lặp trên set của đúng phòng, không lọc động từ danh sách toàn cục |
| **3 — Dọn dẹp** | Channel đóng → gỡ khỏi mọi set ngay trong `channelInactive`. `student_index` không tái sử dụng trong cùng phiên (§4.1) |

Bỏ Valkey Pub/Sub khỏi hot path (§3.3) xoá luôn nhóm bug nguy hiểm nhất ở đây: một pattern
subscribe sai (`room:*`) sẽ khiến mọi pod nhận mọi phòng.

---

## 11. Tầng World & Teacher dashboard

Tài liệu đã dành nhiều phần cho **fan-out** (1 actor → 12 client). Dashboard giáo viên là bài
toán ngược — **fan-in**: gom trạng thái từ hàng nghìn actor rải trên 12–16 pod về một nơi. Nó
khó hơn vì đích đến là *một* điểm và mọi nguồn đều muốn ghi vào đó cùng lúc.

Làm ngây thơ — dashboard poll từng phòng, hoặc mỗi actor push theo nhịp tick 200ms:

```
5.000 phòng × 5 lần/giây = 25.000 update/s đổ vào MỘT service
```

Đó là một broadcast storm chạy ngược chiều — đúng thứ mà §10 được viết ra để ngăn.

### 11.1. Tổng hợp phân tầng

```text
                    4.500 RoomActor  (rải trên 12–16 Engine pod)
                            │
                            │  RoomSummary — CHỈ khi có thay đổi, trần 1 gói / 2 giây
                            │  ~120 bytes: room_id, state, step_index, avg_score,
                            │              students_connected, students_answered
                            ▼
               ┌────────────────────────────────┐
               │      SessionAggregator          │  gom theo session_id
               │  Cluster Singleton PER session  │  throttle xuống 1 Hz
               └───────────────┬────────────────┘  giữ bản đồ room → summary trong RAM
                               │  SessionSnapshot 1 Hz
                               ▼
               ┌────────────────────────────────┐
               │  Teacher WebSocket (qua GW)     │  scope = SESSION, không phải ROOM
               └────────────────────────────────┘
```

### 11.2. Bốn quy tắc bắt buộc

| # | Quy tắc | Vì sao |
|---|---|---|
| 1 | **Không gói tin nào của học sinh đi tới dashboard** | Dashboard chỉ nhận `RoomSummary`. Một `SUBMIT_ANSWER` lọt lên tầng session là bug kiến trúc |
| 2 | **Nhịp tóm tắt (2s) độc lập hoàn toàn với tick trong phòng (200ms)** | Giáo viên không dùng được độ phân giải 200ms trên 5.000 phòng. Ràng buộc hai nhịp vào nhau là cách chắc chắn nhất để tick của phòng kéo sập dashboard |
| 3 | **Dashboard mở > 200 phòng → chuyển sang chỉ số tổng hợp + danh sách ngoại lệ** | Lưới 5.000 ô không ai đọc được. Thứ giáo viên cần là *phòng nào đang kẹt*, *phòng nào có học sinh rớt mạng* |
| 4 | **Singleton theo `session_id`**, không phải singleton toàn cục | Singleton toàn cục là một điểm nghẽn và một điểm chết cho toàn hệ thống. Theo session thì bán kính ảnh hưởng bằng đúng một phiên học |

### 11.3. Lệnh từ giáo viên (chiều ngược lại)

```
Teacher WS → SessionAggregator → phát tán tới ShardRegion THEO LÔ, CÓ JITTER
                                  (lô 200 phòng, jitter 0–500ms giữa các lô)
```

**Không phát đồng loạt.** Một lệnh `TEACHER_PAUSE` toàn phiên chạm 4.500 actor cùng thời điểm
tạo ra đúng một *thundering herd* nhân tạo — cùng loại sự cố mà stagger recovery (§14.3) được
thiết kế để tránh.

### 11.4. SLA riêng của tầng này

| Chỉ số | Cam kết | Ghi chú |
|---|---|---|
| Độ trễ `RoomSummary` → dashboard (p99) | **< 3 s** | Dưới ngưỡng này giáo viên không cảm nhận được khác biệt |
| Băng thông dashboard / phiên 500 phòng | **< 60 KB/s** | 500 × 120 B × 1 Hz |
| Ảnh hưởng của dashboard lên p99 trong phòng | **0** | Đo được ảnh hưởng nghĩa là quy tắc 1 hoặc 2 đã bị vi phạm |

---

## 12. Vào phòng muộn & kết nối lại

### 12.1. Late join khi phòng đang PLAYING

```text
1. Actor cấp student_index còn trống.
   Hết chỗ (đủ 12) → từ chối bằng ROOM_FULL, KHÔNG âm thầm bỏ qua.

2. Trả ROOM_STATE_SNAPSHOT đầy đủ:
     • step hiện tại + deadline CÒN LẠI (không phải deadline gốc)
     • điểm của mọi thành viên
     • KHÔNG kèm đáp án của các câu đã qua      ← ràng buộc BẢO MẬT

3. Áp missed_step_policy cho các câu đã bỏ lỡ  (§12.2)

4. Broadcast STUDENT_JOINED cho cả phòng.
```

Dòng cuối của bước 2 là ràng buộc bảo mật, không phải tối ưu băng thông: gửi kèm đáp án các
câu đã qua biến "vào muộn" thành một cách xem đáp án.

### 12.2. `missed_step_policy`

Thuộc tính của **Game Definition**, không phải hằng số trong code:

| Giá trị | Hành vi | Phù hợp với |
|---|---|---|
| **`ZERO`** *(mặc định)* | Tính 0 điểm cho các câu đã qua | Thi có điểm — công bằng nhất với người vào đúng giờ |
| **`SKIP`** | Không tính các câu đã qua vào mẫu số khi xếp hạng | Luyện tập, nơi thứ hạng tương đối quan trọng hơn điểm tuyệt đối |
| **`ALLOW_LATE`** | Cho làm bù các câu đã qua | Bài tự học. **Không hợp lý khi thi** — người vào muộn có thêm thời gian suy nghĩ |

> [!WARNING]
> **Đây là quyết định của Product, phải chốt trước khi code.** `ALLOW_LATE` buộc actor giữ
> toàn bộ step đã qua trong state, ảnh hưởng trực tiếp tới ràng buộc snapshot < 5 KB (§4.3).

### 12.3. Late join ≠ reconnect

Hai luồng khác nhau và **không được gộp**:

| | Reconnect (§9.3) | Late join (§12.1) |
|---|---|---|
| `student_id` đã có trong state phòng | ✅ Có | ❌ Không |
| Gửi `RESYNC { last_acked_seq, pending[] }` | ✅ Có | ❌ Không có gì để replay |
| Giữ điểm & lịch sử trả lời | ✅ Có | Theo `missed_step_policy` |
| Cấp `student_index` | ❌ Giữ index cũ | ✅ Cấp mới |

Gộp hai luồng làm một là cách tạo ra lỗi *"học sinh vào muộn được cộng điểm của người khác"* —
qua đúng `student_index` bị tái sử dụng.

---

## 13. Mô hình thực thi

Hệ thống dùng **ba mô hình concurrency khác nhau ở ba tầng khác nhau**. Nhầm lẫn giữa chúng là
nguồn sai lầm thiết kế phổ biến nhất trong loại hệ thống này.

| Thành phần | Mô hình | Vì sao |
|---|---|---|
| **RoomActor** | **Pekko dispatcher** (platform thread) | Actor **không sở hữu thread** — dispatcher lập lịch chúng lên một pool nhỏ (~= số core). 1.125 actor chạy tốt trên ~8 platform thread |
| **Netty EventLoop** (biên WS + kênh nội bộ) | Platform thread, cố định = cores × 2 | Non-blocking hoàn toàn. Virtual thread ở đây phản tác dụng |
| **Valkey snapshot I/O, PostgreSQL ghi kết quả** | **Virtual thread** ✅ | Blocking I/O, nhiều tác vụ đồng thời — đúng chỗ dùng |
| **Kafka producer** (giai đoạn 2) | Virtual thread hoặc async client | Blocking I/O |

> [!IMPORTANT]
> **Không đặt mỗi actor mailbox lên một virtual thread.** Con số "1 MB → 2 KB mỗi thread" đúng
> về virtual thread nói chung nhưng **không áp dụng ở đây**: mô hình Pekko chưa bao giờ tốn
> một thread cho mỗi actor, nên không có thread nào đang bị lãng phí để tiết kiệm. Ép mailbox
> lên virtual thread không được gì và đánh nhau với dispatcher.

### 13.1. Chống thread pinning — chỉ ở tầng I/O

Khi gọi I/O blocking trong khối `synchronized`, virtual thread bị "pin" vào carrier thread và
làm nghẽn pool. Quy tắc:

* **Phạm vi áp dụng**: chỉ tầng chạy trên virtual thread — Valkey snapshot I/O, PostgreSQL,
  Kafka producer. Dùng `ReentrantLock`, **không** dùng `synchronized` bao quanh lời gọi blocking.
* **`RoomActor` không dính pinning** (chạy trên platform thread) và **không cần khoá gì cả** —
  mô hình actor đã tuần tự hoá sẵn. Netty EventLoop cũng vậy.

### 13.2. Netty EventLoop: cấm tuyệt đối

```
┌──────────────────────────────────────────────────────────────────────┐
│ NETTY EVENT LOOP (IO-only, non-blocking, Epoll)                      │
│   Chỉ làm: đọc bytes từ socket, đóng/mở gói frame nhị phân.          │
│   NGHIÊM CẤM: DB query, Valkey call, HTTP call, JSON parse,           │
│               CPU-heavy logic, mọi lời gọi blocking.                 │
└────────────────────────────┬─────────────────────────────────────────┘
                             │ Internal Frame Channel (zero-copy)
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│ PEKKO DISPATCHER — ACTOR EXECUTION                                   │
│   1 Room = 1 Actor, chạy trên pool platform thread của dispatcher.   │
│   Không lock giữa các phòng → nguy cơ deadlock = 0.                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 14. Triển khai

### 14.1. Topology @ 54.000 CCU

| Tầng | Số pod | Cấu hình | Ghi chú |
|---|---|---|---|
| **Gateway** | 10 – 12 | 2 vCPU / 4 GB | Stateless — HPA theo CPU + số kết nối |
| **Engine** | **12 – 16** | 2 vCPU / 4 GB | ~300–375 phòng/pod. **Không** HPA theo CPU (xem cảnh báo dưới) |
| Valkey | 3 shard + replica | | Giai đoạn 1 có thể chỉ cần 1 node + replica |
| PostgreSQL | Multi-AZ | | |
| MongoDB | Replica set 3 | | Match history |
| Kafka | 3 broker | | Giai đoạn 2 trở đi |

**Vì sao 12–16 pod nhỏ thay vì 4 pod lớn** — đây là đánh đổi có chủ đích:

| | 4 pod lớn (4 vCPU/8 GB) | **12–16 pod nhỏ (2 vCPU/4 GB)** |
|---|---|---|
| Phòng/pod | 1.125 | ~300–375 |
| **Học sinh ảnh hưởng khi mất 1 pod** | **13.500** | **~4.000** |
| Recovery đồng thời khi mất 1 pod | 1.125 lượt nạp snapshot dồn cục | ~350 lượt |
| Tổng tài nguyên | ≈ như nhau | ≈ như nhau |

Với hệ thống mà một sự cố rơi thẳng vào giờ học đang diễn ra, đổi hiệu suất đóng gói lấy blast
radius nhỏ là đánh đổi đúng.

> [!CAUTION]
> **Không đặt HPA theo CPU cho Engine pod.** Scale xuống dưới quorum sẽ kích hoạt `keep-majority`
> và down chính cluster đang khoẻ (§9.1). Engine scale bằng thay đổi có chủ đích, không bằng
> autoscaler phản ứng theo metric.

### 14.2. Cấu hình Kubernetes

```yaml
# Engine Deployment — các trường có ý nghĩa với tính đúng đắn, không chỉ vận hành
spec:
  terminationGracePeriodSeconds: 45      # đủ cho CoordinatedShutdown leave sạch (§9.1)
  template:
    spec:
      containers:
        - name: engine
          resources:
            requests: { cpu: "2",   memory: "4Gi" }
            limits:   { cpu: "2",   memory: "4Gi" }   # requests = limits → QoS Guaranteed
          livenessProbe:                              # actor treo → restart pod (§10.5)
            httpGet:  { path: /actuator/health/liveness,  port: 8080 }
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:                             # chưa join cluster → chưa nhận traffic
            httpGet:  { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 5
      topologySpreadConstraints:                      # không dồn Engine pod lên một node
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: DoNotSchedule
```

| Thiết lập | Vì sao quan trọng ở đây |
|---|---|
| `requests = limits` (QoS Guaranteed) | Pod bị throttle CPU giữa trận → GC pause tăng → failure detector nghi ngờ node khoẻ |
| `terminationGracePeriodSeconds: 45` | Thiếu graceful leave thì mỗi rolling update là một lần nghi ngờ split-brain |
| `topologySpreadConstraints` | Mất một node không được kéo theo nhiều Engine pod cùng lúc |
| JVM đọc `limits.memory` | Từ JDK 10+ JVM tự đọc cgroup limit — không cần `-Xmx` cứng, nhưng **phải** đặt `-XX:MaxRAMPercentage` (khuyến nghị 70) |

**Lựa chọn GC**: **G1 là mặc định.** Với heap 4–8 GB và snapshot < 5 KB/phòng, G1 đủ và dễ
tune hơn; ZGC đánh đổi ~10–15% throughput để lấy pause time mà bài toán này chưa cần. Cả hai
được đo ở H2 (§16.3) và chọn theo số liệu, với ngưỡng nghiệm thu `GC pause p99 < 10 ms`.

### 14.3. Khi một Engine pod chết

```
t=0s      Pod E2 chết (12–16 pod → ~350 phòng, ~4.000 học sinh bị ảnh hưởng)
t=0s      GW thấy Internal Frame Channel tới E2 đứt
          → xoá cache route trỏ tới E2 (§8.2)
          → gửi CONNECTION_DEGRADED (Critical) tới client thuộc các phòng đó
          → GIỮ NGUYÊN WebSocket của client (§9.7)
t=5–10s   Failure detector nhận ra node chết
t=10–20s  SBR quyết định (stable-after=10s), down node thiểu số
t≈20s     ShardRegion tái phân bổ shard sang node còn sống
          → actor được tạo lại, INCR epoch, nạp snapshot, vào RESYNCING
          → STAGGER: nạp snapshot theo lô với jitter 0–500ms
            (không có bước này, ~350 phòng đập vào Valkey cùng lúc)
t≈21s     Client gửi RESYNC { last_acked_seq, pending[] }
          → LastSeenSequenceTable loại bản trùng
          → deadline câu hỏi được gia hạn đúng khoảng gián đoạn
t≈22s     Phòng về PLAYING, broadcast state đầy đủ một lần.
          MẤT DỮ LIỆU = 0. Học sinh không phải thao tác lại gì.
```

---

## 15. Quan sát & SLA

### 15.1. Chỉ số bắt buộc

| Nhóm | Chỉ số | Vì sao cần |
|---|---|---|
| **Actor** | `actor_processing_latency` (p99), `actor_mailbox_depth` (p99) | Mailbox depth là chỉ số cảnh báo sớm tốt nhất — nó tăng trước khi latency tăng |
| **Gateway** | `fanout_latency` (p99), `handshake_rate`, `channel_not_writable_total` | `channel_not_writable_total` tăng nghĩa là backpressure đang hoạt động |
| **Recovery** | `room_recovery_duration`, `resync_applied_total`, `resync_duplicate_dropped_total` | `duplicate_dropped > 0` chứng minh dedupe đang chạy đúng |
| **Fencing** | `zombie_actor_stopped_total` | **Bất kỳ giá trị nào > 0 đều đáng điều tra** — nó nghĩa là split-brain đã xảy ra thật |
| **JVM** | GC pause (p99), RSS, direct memory | Direct memory rò rỉ = lỗi `retainedDuplicate` (§10.3) |
| **Chi phí** | Chi phí / CCU / giờ | §17 |

### 15.2. Cam kết SLA nội bộ

| Chỉ số | Cam kết | Xác nhận bằng |
|---|---|---|
| **Mất dữ liệu đáp án khi sập pod** | **0** | Chaos test §16.5 bước 5 |
| **End-to-end submit → ACK (p99)** | **< 100 ms** | Load test @54k |
| **Phòng hoạt động trở lại sau sự cố (p99)** | **< 30 s** | Chaos test |
| Nạp lại state sau khi actor được tạo (p99) | < 100 ms | H2 |
| Actor processing time (p99) | < 15 ms | H2 |
| Gateway fan-out latency (p99) | < 5 ms | H1 |
| Snapshot serialize + nén (p99) | < 5 ms | H2 |
| Snapshot payload | < 5 KB | H2 |
| Handshake rate chịu được / GW pod | **đo ở H1** | H1 |
| `RoomSummary` → dashboard (p99) | < 3 s | §11.4 |
| Học sinh phải thao tác lại thủ công | **Không** | Chaos test |

Ba dòng đầu là những gì khách hàng thực sự quan tâm. Các dòng còn lại là chỉ số kỹ thuật hỗ
trợ — chúng không thay thế được ba dòng đầu.

### 15.3. Distributed tracing

Trace ID sinh tại Gateway lúc handshake, truyền qua `InternalHeader` xuống actor và theo suốt
các lời gọi async. Không có nó, câu hỏi *"tại sao học sinh này thấy chậm"* không trả lời được
khi đường đi trải qua 2 pod + 1 actor + 1 lượt fan-out.

---

## 16. Load test & benchmark

Sinh hàng nghìn client WebSocket nói Protobuf, có state phiên đầy đủ (join → nhận câu hỏi →
nộp đáp án đúng sequence → nhận ACK) **tự nó là một dự án con** — không phải một dòng trong
lịch trình.

### 16.1. Harness

| Hạng mục | Quyết định |
|---|---|
| **Công cụ** | **Gatling** (Scala/Java — tái dùng trực tiếp Protobuf class đã codegen) hoặc **k6** + extension WebSocket nhị phân |
| **Kịch bản bắt buộc** | Phải gồm **connection storm** (§10.4): toàn bộ CCU nối trong 15 giây |
| **Thời lượng** | **≥ 30 phút** — dưới ngưỡng này không lộ được rò rỉ direct memory và GC drift |
| **Chỉ số nghiệm thu** | p99 end-to-end, `actor_mailbox_depth`, RSS, GC pause, packet/s **thực đo** |
| **Công sức** | **3–5 ngày cho riêng harness**, tách khỏi ngày chạy test |

Không dùng Locust/JMeter: cả hai mạnh với HTTP request-response, nhưng kịch bản ở đây là **kết
nối dài có trạng thái, payload nhị phân, thứ tự sequence quan trọng**. Dựng nó trên hai công
cụ đó tốn công hơn và khó tái dùng `.proto` đã có.

### 16.2. Giả thuyết H1 — Gateway

```text
H1:  5.000 WS/pod @ 2 vCPU / 4 GB

Kiểm chứng:  5.000 client giữ kết nối, 1 msg / 25 s / client, TLS BẬT.

Đạt nếu (duy trì 30 phút):
   CPU            < 60%
   RSS            < 3 GB
   p99 fan-out    < 5 ms

Rủi ro chính: CPU của TLS handshake lúc đông (§10.4) — KHÔNG phải steady state.
→ H1 BẮT BUỘC đo thêm handshake rate riêng: bao nhiêu handshake/s/pod trước khi
  p99 fan-out vượt ngưỡng? Con số đó chính là ngưỡng admission control.
```

### 16.3. Giả thuyết H2 — Engine

```text
H2:  đo TRẦN thật ở 1.125 rooms/pod @ 4 vCPU / 8 GB,
     rồi mới chọn điểm vận hành (~300–375 rooms/pod @ 2 vCPU / 4 GB, §14.1).

Kiểm chứng:  1.125 RoomActor, tick coalescing, 12 học sinh/phòng, tải theo §6.1.

Đạt nếu:
   p99 actor_processing_latency  < 15 ms
   p99 mailbox_depth             < 10
   p99 GC pause                  < 10 ms     ← đo CẢ G1 VÀ ZGC, chọn theo số liệu
   RSS                           < 6 GB

Rủi ro chính: SỐ LƯỢNG TIMER đồng thời (1.125 scheduled timer/pod), không phải CPU.
```

### 16.4. Quy tắc công bố

> [!IMPORTANT]
> **Không công bố con số pod cho 54k CCU — ra ngoài hoặc để lập ngân sách — trước khi H1 và H2
> có kết quả đo.** Mọi con số capacity trong tài liệu này được viết ra để có cái mà bác bỏ,
> không phải để trích dẫn.

### 16.5. Thứ tự chạy

| Bước | Việc | Đầu ra |
|---|---|---|
| 1 | Dựng harness Gatling + codegen `.proto` | 3–5 ngày, tách khỏi lịch test |
| 2 | **H1** — Gateway steady state + handshake rate | Ngưỡng `max_handshake_per_sec` cho admission control |
| 3 | **H2** — Engine, đo cả G1 và ZGC | Số rooms/pod thật + lựa chọn GC có căn cứ |
| 4 | Full-system @ 54k CCU **kèm connection storm** | Xác nhận SLA §15.2 |
| 5 | **Chaos: sập 20% Engine pod giữa lúc tải đỉnh** | Xác nhận **mất dữ liệu = 0** và **phòng trở lại < 30 s** |

Bốn bước đầu đo hiệu năng. **Bước 5 là bước duy nhất đo tính đúng đắn dưới sự cố** — nó chứng
minh cam kết cốt lõi của ADR-3, và nó là bước không được phép bỏ.

---

## 17. Chi phí hạ tầng

Với EdTech, **chi phí/CCU thường là ràng buộc cứng hơn latency**. Một kiến trúc đạt p99 < 100ms
nhưng lỗ trên mỗi học sinh là kiến trúc sai.

### 17.1. Khung ước tính @ 54.000 CCU

Điền số thật theo region đang dùng — đây là **khung**, không phải báo giá:

| Thành phần | Cấu hình | Ghi chú |
|---|---|---|
| Gateway pods | 10–12 × (2 vCPU / 4 GB) | Xác nhận bằng H1 |
| Engine pods | 12–16 × (2 vCPU / 4 GB) | §14.1 |
| Valkey (managed) | 3 shard + replica | Giai đoạn 1: 1 node + replica là đủ |
| PostgreSQL (managed) | Multi-AZ | |
| MongoDB | Replica set 3 | |
| Kafka (managed) | 3 broker | **Hoãn được** — không nằm trên hot path |
| **Network egress** | ~26.000 pkt/s × ~150 B ≈ **3,9 MB/s** | ⚠ **Khoản thường bị quên hoàn toàn** |
| **Chi phí / CCU / giờ** | — | **Theo dõi liên tục, không tính một lần** |

### 17.2. Egress — vì sao tách riêng một dòng

Egress là khoản duy nhất **tỉ lệ thuận với thành công của sản phẩm** và không giảm được bằng
cách chọn instance rẻ hơn. Nó cũng là lý do khiến ADR-4 có giá trị tài chính, không chỉ giá
trị kỹ thuật:

| Mô hình tick | Outbound | Egress tương đối |
|---|---|---|
| Fixed-rate 200ms | 270.000 pkt/s | **~10×** |
| **Coalescing (ADR-4)** | ~26.000 pkt/s | **1×** |

ADR-4 cắt khoảng 90% hoá đơn băng thông.

### 17.3. Câu hỏi có thể lật ngược thiết kế

**Hệ thống thực sự chạy mấy giờ mỗi ngày?**

Nếu chỉ 4–6 tiếng/ngày trong năm học, thì **chi phí nhàn rỗi 18–20 tiếng còn lại mới là khoản
chi phối** — và điều đó nghiêng mạnh về scale-to-zero, **ngược với mô hình cluster luôn-bật mà
ADR-2 giả định**.

| Kịch bản | Hệ quả kiến trúc |
|---|---|
| Gần 24/7 (nhiều múi giờ, tự học) | Giữ cluster luôn-bật. Thiết kế hiện tại đúng |
| 4–6 tiếng/ngày, có mùa vụ | Cần **KEDA/HPA theo lịch**. Engine chỉ scale xuống mức **tối thiểu giữ quorum** — Cluster Sharding **không** scale xuống 0 được (§9.1) |

Đây là câu hỏi cho Business, không phải cho kỹ thuật, và nó phải được trả lời trước khi chốt
ngân sách hạ tầng.

---

## 18. Lộ trình

Mỗi giai đoạn tối đa 2–3 tuần.

### Giai đoạn 1 — Lean MVP · 3 tuần · mục tiêu 2.000–3.000 CCU

| | |
|---|---|
| **Có** | Netty Gateway (2 pod) · Engine Pekko (2 pod) · Valkey snapshot · PostgreSQL · Protobuf envelope · tick coalescing · client replay + `LastSeenSequenceTable` · server-authoritative timestamp · rate limit phân tầng |
| **Cắt** | Kafka · ClickHouse · nén LZ4 · Cluster Sharding |
| **Ưu tiên tuyệt đối** | Dựng Gateway ↔ Engine ↔ Protobuf thông suốt trong 3–4 ngày đầu. Mọi thứ khác (Auth REST, ghi DB, trang trí UI) chỉ làm sau khi luồng nhị phân đã chạy |

> [!WARNING]
> **Đánh đổi của giai đoạn 1**: định tuyến tĩnh `room_id % 2`, **không rebalance, không SBR**.
> Mất một pod = mất một nửa số phòng cho tới khi pod lên lại. Chấp nhận được ở 2–3k CCU với
> điều kiện **phải bỏ trước giai đoạn 2** — không được mang cấu hình này lên production quy mô
> lớn.

Recovery **vẫn hoạt động** ở giai đoạn 1 vì nó dựa vào client replay chứ không vào Kafka hay
Cluster Sharding. Đây là lợi ích trực tiếp của ADR-3.

### Giai đoạn 2 — Scale-out & async · 3 tuần · mục tiêu 10.000–20.000 CCU

| Tuần | Việc |
|---|---|
| 1 | Kafka (managed), tách hot/cold path. Engine phát `GAME_FINISHED` sang Kafka thay vì gọi DB trực tiếp |
| 2 | Leaderboard Worker (`ZADD` Valkey) + Audit Worker (MongoDB) + Analytics Worker (PostgreSQL partition) |
| 3 | **Cluster Sharding + SBR `keep-majority` + fencing token + stagger recovery**, phân bổ trên **12–16 Engine pod nhỏ**. Chaos test |

> [!CAUTION]
> **Không bật Cluster Sharding mà thiếu SBR + fencing token.** Sharding trần là đường thẳng
> tới hai `RoomActor` cùng `room_id` chạy song song: chấm điểm hai lần, ghi đè snapshot của
> nhau (§9.1). Ba thứ này lên cùng một lúc hoặc không lên.

### Giai đoạn 3 — Enterprise scale · 2 tuần · mục tiêu 54.000+ CCU

| Tuần | Việc |
|---|---|
| 1 | Nén LZ4 adaptive cho snapshot > 150 B · PostgreSQL partitioning theo tháng · `SessionAggregator` + teacher dashboard (§11) |
| 2 | Multi-AZ HA · **chạy đủ 5 bước của §16.5** · đối chiếu H1/H2 để chốt số pod thật · nghiệm thu bảo mật |

---

## 19. Việc còn mở

Năm câu hỏi dưới đây **cần người quyết định, không phải cần thêm phân tích kỹ thuật**. Chúng
chặn những phần cụ thể của thiết kế:

| # | Câu hỏi | Ai quyết | Chặn phần nào |
|---|---|---|---|
| 1 | `missed_step_policy` mặc định cho late join | **Product** | §12.2 — `ALLOW_LATE` ảnh hưởng ràng buộc snapshot < 5 KB |
| 2 | Ngân sách hạ tầng / chi phí trên mỗi CCU | **Business** | §17 |
| 3 | Hệ thống chạy mấy giờ/ngày? | **Business** | §17.3 — **có thể lật ngược ADR-2** |
| 4 | Danh mục game thật của giai đoạn 1 | **Product** | §6.3 — quyết định game nào cần `tick_mode: FIXED` và capacity riêng |
| 5 | Trường lớn nhất có bao nhiêu học sinh đồng thời sau một IP? | **Business** | §10.1 — ngưỡng L1 |

Câu 3 là câu quan trọng nhất: nó là câu duy nhất có thể buộc phải xem lại một ADR.

---

## Phụ lục A — Phương án đã loại

Ghi lại để không phải tranh luận lại. Mỗi dòng là một phương án **đã được cân nhắc nghiêm túc
và bị loại có lý do**, không phải một phương án bị bỏ qua.

| Phương án | Loại vì |
|---|---|
| **gRPC cho kênh nội bộ** | Flow-control chồng hai tầng (HTTP/2 window + app window) khiến lúc nghẽn không biết tầng nào chặn. Các lợi ích khác (codegen, deadline, LB) đều không áp dụng ở đây — ADR-1 |
| **Eclipse Vert.x cho Gateway** | Vert.x là Netty được bọc. Ở tầng này ta **cần** đúng những API mà nó bọc lại: framing tự định nghĩa, watermark, `retainedDuplicate()` fan-out. Lớp bọc thêm khoảng cách chẩn đoán mà không bớt việc |
| **Spring WebFlux cho Gateway** | Cùng lý do với Vert.x, cộng thêm chi phí học Reactor cho một tầng vốn chỉ cần đọc/ghi bytes |
| **Viết lại Gateway bằng Go** | Suy đoán cho mốc CCU > 100.000 — gấp đôi target. Đưa vào phạm vi hiện tại chỉ làm loãng quyết định giai đoạn 1. Xem lại khi có **số đo RAM thật** |
| **Socket.io** | Payload JSON, client ngoài JS chất lượng không đồng đều, và cơ chế room/reconnect có sẵn vẫn phải viết lại lớp thích ứng cho FSM phòng — §5.2 |
| **Consistent hashing ở Gateway** | Hai nguồn sự thật về vị trí phòng; vòng hash trỏ sai sau rebalance và không tự biết — §8.1 |
| **ClickHouse** | Analytics ở quy mô hiện tại vừa với PostgreSQL partition theo tháng. Cân nhắc lại khi log vượt ~10 GB/ngày |
| **Valkey Pub/Sub trên hot path** | Thêm một broker hop và mở ra cả một lớp bug rò dữ liệu chéo phòng — §10.6 |
| **Kafka trong đường recovery** | Snapshot và Kafka offset không atomic → hoặc mất đáp án hoặc replay trùng — §9.2 |
| **Fixed-rate tick cho mọi game** | Nặng hơn ~10× cho quiz, loại game chính của nền tảng — §6.2 |
| **Rate limit theo IP làm tầng chính** | Chặn đứng chính khách hàng mục tiêu (trường học sau NAT) — §10.1 |
| **Watchdog "ngắt" logic quá hạn** | Không thực hiện được trong Java. `Thread.stop()` đã gỡ, `interrupt()` không tác dụng với vòng lặp CPU — §10.5 |
| **Circuit breaker cho kênh GW ↔ Engine** | Circuit breaker cần một nơi khác để đi. Room state chỉ tồn tại ở một nơi — §9.7 |
| **Một virtual thread cho mỗi actor mailbox** | Pekko chưa bao giờ tốn một thread mỗi actor, nên không có gì để tiết kiệm; và nó đánh nhau với dispatcher — §13 |
| **ZGC làm mặc định** | Với heap 4–8 GB, G1 đủ và dễ tune hơn. Chọn theo số đo H2, không theo mặc định — §14.2 |

## Phụ lục B — Thuật ngữ

| Thuật ngữ | Nghĩa trong tài liệu này |
|---|---|
| **CCU** | Concurrent Users — số học sinh đang giữ WebSocket cùng lúc |
| **Room / RoomActor** | Phòng học và actor đơn luồng phục vụ nó. Đơn vị cô lập nghiệp vụ cốt lõi |
| **World / Session** | Phiên điều phối của giáo viên, gom nhiều Room |
| **Internal Frame Channel** | Kênh TCP dài hạn + length-prefixed Protobuf giữa Gateway và Engine (ADR-1) |
| **Tick coalescing** | Chỉ broadcast khi state đổi, với trần tần suất 200ms (ADR-4) |
| **Fencing token / epoch** | Số đơn điệu tăng gắn với mỗi lần actor khởi động, chặn zombie ghi đè (§9.1) |
| **`LastSeenSequenceTable`** | Bảng `student_id → last_seq` trong RAM actor, loại bản trùng O(1) (§9.5) |
| **RESYNC** | Thông điệp client gửi sau reconnect, mang `last_acked_seq` + submission chưa ACK |
| **Blast radius** | Số học sinh bị ảnh hưởng khi mất một pod |
| **Connection storm** | 54.000 kết nối dồn trong ~15 giây đầu giờ học (§10.4) |
| **H1 / H2** | Hai giả thuyết capacity cần benchmark xác nhận (§16) |
| **Critical / Best-effort** | Phân loại thông điệp quyết định bypass coalescing và hành vi khi backpressure (§5.4) |

---

*v3.0 — bản hợp nhất. Lịch sử điều chỉnh: `EdTech_Game_Realtime_Architecture_v2.2-revisions.md` (R-01…R-25).*
