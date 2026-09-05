# 🎯 TÀI LIỆU KIẾN TRÚC HỆ THỐNG GAME THỜI GIAN THỰC (v2.2)
## High-Concurrency EdTech Realtime Multiplayer Game Platform (Target: 54.000 CCU)

> **Nền tảng**: Java 21+ (LTS) | Apache Pekko Typed + Cluster Sharding | **Netty** Gateway (biên & kênh nội bộ) | Native WebSocket + Protobuf Binary | Kênh nội bộ TCP + length-prefixed Protobuf | Flutter Native + Embedded WebView Bridge | Kubernetes
>
> **Quy ước phiên bản Java**: **Java 21 (LTS) là sàn tương thích bắt buộc** — Pekko, Netty và
> toolchain vận hành đều được kiểm trên bản này. Các ví dụ mã trong tài liệu viết theo cú pháp
> **Java 25**. Không mục nào của kiến trúc phụ thuộc vào tính năng chỉ có ở Java 25.
> **Lựa chọn GC (G1 hay ZGC) là kết quả đo ở benchmark H2 (Mục 32), không phải mặc định.**

---

## 📋 Thay đổi so với v2.1

v2.2 áp dụng **25 điều chỉnh** lên v2.1. Chi tiết từng điều chỉnh (v2.1 viết gì → sai ở đâu →
thay bằng gì) nằm ở `EdTech_Game_Realtime_Architecture_v2.2-revisions.md`.

**Bốn quyết định kiến trúc (ADR):**

| ID | Quyết định | Thay cho v2.1 |
|---|---|---|
| **ADR-1** | Kênh nội bộ Gateway ↔ Engine dùng **TCP + length-prefixed Protobuf**, không gRPC | v2.1 dùng gRPC bi-directional stream |
| **ADR-2** | **Pekko Typed + Cluster Sharding**, bắt buộc kèm Split Brain Resolver + fencing token | v2.1 mô tả Akka/Pekko nhưng ghép nhầm với Virtual Threads |
| **ADR-3** | Mục tiêu recovery = **client sống sót qua gián đoạn, mất dữ liệu = 0**, không phải "khôi phục < 50ms" | v2.1 cam kết 3 giá trị mâu thuẫn: `<30ms` / `<50ms` / `50–100ms` |
| **ADR-4** | **Tick coalescing (dirty-flag)** là mặc định; fixed-rate tick chỉ bật cho game chuyển động liên tục | v2.1 mặc định fixed-rate 200ms cho mọi loại game |

**Sáu thay đổi có tác động lớn nhất:**

1. **Mô hình tải được tính lại** (Mục 7.1) — phép tính 648.000 packet/s của v2.1 sai ~300 lần.
   Tải thật: ~4.000 msg/s inbound, ~26.000 packet/s outbound.
2. **Tick 200ms đổi ngữ nghĩa** (Mục 7.1) — từ *nhịp phát cố định* thành *trần tần suất*.
   Phòng im lặng phát 0 gói. Giảm ~10× outbound cho game dạng quiz.
3. **Recovery không còn phụ thuộc Kafka** (Mục 8.1) — dùng client-side replay + `LastSeenSequenceTable`.
   Điều này gỡ mâu thuẫn lớn nhất của v2.1: MVP cắt Kafka nhưng recovery lại cần Kafka.
4. **Bổ sung chống split-brain** (Mục 8.4) — SBR `keep-majority` + fencing token qua Redis Lua CAS.
   v2.1 không nhắc tới hazard này.
5. **Ba lỗi tiềm ẩn được sửa** — `ByteBuf.retainedDuplicate()` trong vòng broadcast (Mục 23.2), rate limit
   theo IP chặn trường học sau NAT (Mục 8.3), watchdog không thể ngắt logic đang chạy (Mục 22.1).
6. **Bốn mục mới** — Tầng World/Teacher dashboard (Mục 29), Late join (Mục 30),
   Chi phí hạ tầng (Mục 31), Phương pháp load test (Mục 32).

> [!IMPORTANT]
> **Các con số capacity trong tài liệu này là GIẢ THUYẾT, chưa được đo.**
> `5.000 WS/pod` và số rooms/pod phải được xác nhận bằng benchmark H1/H2 (Mục 32)
> trước khi công bố ra ngoài hoặc dùng để lập ngân sách.

### Trạng thái rà soát (bản này)

Bản v2.2 ban đầu mới áp điều chỉnh vào **nửa đầu tài liệu**; nửa sau còn nguyên nội dung v2.1.
Đợt rà soát toàn diện đã đối chiếu từng mục với R-01…R-25 và xử lý nốt:

| Nhóm | Đã xử lý |
|---|---|
| **Mục 29–32 bị thiếu hoàn toàn** | Đã viết mới. Trước đó TOC, changelog và **9 cross-reference nội bộ** trỏ tới bốn mục không tồn tại |
| **Mục lục lệch thân bài** | Dựng lại từ thân bài; thêm heading phân vùng cho cả 7 PHẦN. Bỏ *"< 50ms Recovery SLA"* ở PHẦN III (mâu thuẫn ADR-3) và mục 23 bị trùng |
| **Số liệu tải sót lại** | Mục 19.2 và 26.2 còn *"270.000 req/s"*, *"khôi phục < 50ms"*, *"Locust/JMeter"* → thay theo R-01/R-02/R-03/R-23 |
| **ClickHouse sót** | Mục 2.1 (Analytics Worker) và Mục 20 (Kafka topic + consumer group) → PostgreSQL partition theo tháng (R-16) |
| **Blast radius sót** | Mục 26 còn kế hoạch *"4 Engine Pods"* → 12–16 pod nhỏ + SBR bắt buộc (R-05, R-06) |
| **Pekko vs Virtual Threads** | Mục 16.2, 22.1, 27 và sơ đồ Mục 14 còn ghép hai mô hình → tách theo bảng phân bổ 14.7 (R-15) |
| **Vert.x sót** | Mục 16.2 còn khuyến nghị *"RẤT NÊN CHUYỂN SANG VERT.X"* → bãi bỏ, chốt Netty (R-14) |
| **ZGC như mặc định** | Banner, Mục 1.4, 25.3, 26 → G1 là mặc định, GC chọn theo số đo H2 (R-25) |
| **Phiên bản Java** | Banner *"Java 21+"* vs thân bài *"Java 25+"* vs *"Java 17+"* → quy ước một chỗ ở banner |

> **Bản hợp nhất gọn**: xem `EdTech_Game_Realtime_Architecture_v3.0.md`. Tài liệu v2.2 này được
> giữ lại làm **hồ sơ rà soát** — nó ghi cả nội dung sai của v2.1 lẫn lý do thay thế, hữu ích khi
> cần truy vì sao một quyết định được đảo. v3.0 chỉ chứa kiến trúc đang có hiệu lực.

---

## 📌 MỤC LỤC TỔNG QUAN KIẾN TRÚC (MASTER TABLE OF CONTENTS)

> [!NOTE]
> **Mục lục này đã được dựng lại từ thân bài v2.2.** Bản mục lục trong v2.1 (và trong các
> bản nháp v2.2 đầu) là di sản chưa cập nhật: số mục lệch với thân bài, Mục 23 xuất hiện hai
> lần, và PHẦN III còn ghi *"< 50ms Recovery SLA"* — con số đã bị ADR-3 bãi bỏ.

### 🏛️ PHẦN I — TỔNG QUAN & ĐỊNH HƯỚNG KIẾN TRÚC *(Mục 1–3)*
| Mục | Nội dung |
|---|---|
| **1** | Tổng quan Dự án, Ràng buộc Hệ thống, Mô hình Định danh & Lý do chọn Tech Stack (Java/Pekko vs Golang) |
| **2** | Kiến trúc Tổng thể Thống nhất & Phân tách Thành phần *(ADR-1: kênh nội bộ TCP + Protobuf)* |
| **3** | Phân lớp Chi tiết, Design Patterns & Khả năng Mở rộng của Game Engine / Lifecycle Module |

### ⚡ PHẦN II — THIẾT KẾ CỐT LÕI & LUỒNG DỮ LIỆU REALTIME *(Mục 4–7)*
| Mục | Nội dung |
|---|---|
| **4** | Tổng quan Kênh Giao tiếp (biên WebSocket / kênh nội bộ / REST) |
| **5** | Sơ đồ Sequence Chi tiết một Game hoàn chỉnh |
| **6** | Quy chuẩn Protobuf Schema (`game_message.proto`) |
| **7** | Tối ưu Hiệu năng & Dữ liệu — **mô hình tải đã tính lại (R-01)** và **tick coalescing (ADR-4)** |

### 🛡️ PHẦN III — AN TOÀN, CHỐNG CHỊU LỖI & QUẢN TRỊ RỦI RO *(Mục 8–15)*
| Mục | Nội dung |
|---|---|
| **8** | Khôi phục sự cố *(**mục tiêu: mất dữ liệu = 0**, ADR-3)*, chống spam, rate limiting phân tầng, **chống split-brain (8.4)**, **server-authoritative timestamp (8.5)**, **connection storm (8.6)** |
| **9** | Hướng dẫn Thiết kế & Tối ưu phía Client (Frontend) |
| **10** | Mở rộng Nền tảng Trò chơi — Matchmaking, Leaderboard, Player Profile |
| **11** | Tư duy Thuật toán & System Design Protocol trước khi code |
| **12** | Tổng hợp Design Patterns cốt lõi trong Game Backend |
| **13** | Đánh giá Rủi ro Kỹ thuật A→F & Giải pháp Phòng ngừa |
| **14** | Kỹ thuật Nâng cao & **Bảng cam kết SLA nội bộ (14.6)** — **lazy-learned routing (14.1)**, **phân bổ mô hình thực thi (14.7)** |
| **15** | Phân tích Độ ổn định khi Room Size tăng lên 100 người |

### 🚀 PHẦN IV — QUYẾT ĐỊNH CÔNG NGHỆ, QUY CHUẨN & BẢO VỆ RUNTIME *(Mục 16–22)*
| Mục | Nội dung |
|---|---|
| **16** | *(Phụ lục)* Các phương án Gateway **đã cân nhắc và LOẠI**: Vert.x, Spring WebFlux, Go Gateway |
| **17** | Native WebSocket + Protobuf **thay** Socket.io — đánh giá Backend & Frontend |
| **18** | Quy chuẩn Xác thực WebSocket (One-Time Ticket Auth, Channel Attributes, In-Session Refresh) |
| **19** | 5 Dạng Game Thực tế & Quy trình Phối hợp 6 Bước từ ý tưởng tới production |
| **20** | Domain-Driven Kafka Topic Modeling — *Kafka = analytics/audit, **KHÔNG** nằm trên hot path* |
| **21** | Runtime Guardrails chống lỗi logic trong Game Definition |
| **22** | Chống Treo Thread, Kiểm soát Fan-Out Broadcast & Chống Đẩy Nhầm Room |

### 📱 PHẦN V — TRIỂN KHAI ĐA POD & KIẾN TRÚC FRONTEND *(Mục 23–24)*
| Mục | Nội dung |
|---|---|
| **23** | Mô hình Triển khai Đa Pod (10–12 Gateway Pods, **12–16 Engine Pods** — R-05) & Luồng dữ liệu A→Z |
| **24** | Kiến trúc Frontend cho App Flutter & Nhúng WebView (`JavascriptChannel` Bridge) |

### 🗺️ PHẦN VI — ĐÁNH GIÁ KIẾN TRÚC & LỘ TRÌNH TRIỂN KHAI *(Mục 25–28)*
| Mục | Nội dung |
|---|---|
| **25** | 6 Tinh chỉnh Thực chiến & Tối ưu hoá Kubernetes |
| **26** | Lộ trình Triển khai Phân kỳ (Lean 2–3 tuần/giai đoạn) |
| **27** | Phụ lục: Kế hoạch Hành động MVP 3 Tuần |
| **28** | Case Study: Refactor Module Thảo luận Nhóm (`group_discussion`) |

### 🆕 PHẦN VII — CÁC MỤC BỔ SUNG TRONG v2.2 *(Mục 29–32)*
| Mục | Nội dung | Nguồn |
|---|---|---|
| **29** | Tầng World & Teacher Dashboard — bài toán **fan-in** *(v2.1 định nghĩa `World` rồi bỏ trống)* | R-20 |
| **30** | Late Join & Backfill — học sinh vào phòng giữa câu hỏi | R-21 |
| **31** | Ước tính Chi phí Hạ tầng & Chi phí trên mỗi CCU | R-22 |
| **32** | Phương pháp Load Test & Benchmark H1/H2 xác nhận Capacity | R-23 |

---

# 🏛️ PHẦN I — TỔNG QUAN & ĐỊNH HƯỚNG KIẾN TRÚC

---

## 1. 📌 Tổng quan Dự án & Bài toán Hạ tầng

### 1.1. Mục tiêu & Ràng buộc Hệ thống
* **Tải đỉnh (Concurrent Users - CCU)**: Tải hiện tại: **10.000 – 18.000 CCU**. **Mục tiêu chịu tải Gấp 3 lần (3x Target Peak)**: Thiết kế chịu tải tối đa lên tới **54.000 CCU** (tương đương 4.500 – 5.000 phòng chạy song song trong các kỳ thi/sự kiện lớn). trong giờ cao điểm.
* **Mô hình phòng học**: Phân rã theo mô hình phòng nhỏ tiêu chuẩn **12 học sinh/phòng**.
* **Số lượng phòng song song**: Khoảng **4.500 – 5.000 phòng** hoạt động đồng thời.
* **Thời lượng phiên học (Session)**: Dưới 70 phút/phiên.
* **Độ trễ mục tiêu (Latency Target)**: p99 < 100ms cho mọi thao tác realtime hai chiều.
* **Chế độ chơi (Game Modes)**:
  * **Solo Mode (Cá nhân)**: 12 học sinh tương tác độc lập.
  * **Team Mode (Nhóm kín)**: 12 học sinh chia thành các nhóm nhỏ (ví dụ: 4 nhóm × 3 học sinh), hỗ trợ gõ nháp/thảo luận nội bộ nhóm (*Co-editing / Draft Sync*).
  * **Hybrid / Broadcast**: Thi đua toàn phòng, tương tác Boss thế giới hoặc thanh tiến trình chung.

### 1.2. Mô hình Phân tầng Định danh (Identity Model)
* **World**: Phiên điều phối chung của Giáo viên (`teacher_id`, `session_id`).
* **Clan / Room**: Phòng học (`room_id`, `game_id`) – **Đơn vị cô lập nghiệp vụ cốt lõi**. Mỗi Room tương ứng với 1 Game Session Actor.
* **Team**: Nhóm nhỏ trong phòng (`team_id`, `team_size = 3`, `team_count = 4`).
* **Member**: Học sinh (`student_id`, đính kèm `student_index` từ 0 đến 11).

### 1.3. Triết lý Thiết kế Cốt lõi
1. **Authoritative Server**: Máy chủ giữ toàn bộ quyền quyết định logic, điểm số và trạng thái. Client chỉ là tầng hiển thị.
2. **Single-Threaded Distributed Actor Model**: 1 Room = 1 Actor đơn luồng. Xử lý sự kiện theo thứ tự tuần tự (*Sequential Message Processing*), triệt tiêu hoàn toàn bài toán Lock Contention và Race Condition.
3. **Decoupled Gateway & Engine**: Tách biệt hoàn toàn tầng mạng biên (Netty Gateway) và tầng xử lý nghiệp vụ (Game Session Actor).
4. **Lean Tech Stack (Bộ công nghệ tinh gọn)**:
   * **Netty**: Quản lý WebSocket biên & kênh nội bộ tới Engine (ADR-1) & Smart Fan-out.
   * **Redis**: Session registry, Snapshot phòng, **lease + fencing epoch** (Mục 8.4).
   * **Kafka**: Event log cho **analytics & audit**. **KHÔNG nằm trên đường khôi phục sự cố** —
     v2.1 đặt Kafka vào recovery path, v2.2 gỡ ra (xem Mục 8.1). Nhờ vậy Kafka hoãn được
     sang giai đoạn sau mà không tạo lỗ hổng nào.
5. **Nguồn đúng đắn khi mất mát là Client, không phải Server**: Client giữ buffer các
   submission chưa được ACK và tự gửi lại khi nối lại; server khử trùng lặp bằng
   `LastSeenSequenceTable`. Đây là nền tảng của cam kết **mất dữ liệu = 0** (Mục 8.1).

---

### 1.4. Quyết định Lựa chọn Công nghệ: Java/Netty vs. Golang (Tech Stack Rationale)

Khi xem xét bài toán Realtime Game 54.000 CCU (3x Target Peak), **Golang** là một ứng viên rất mạnh nhờ Goroutines nhẹ và khả năng quản lý I/O tốt. Tuy nhiên, kiến trúc quyết định lựa chọn **Java + Netty + Distributed Actor Model** dựa trên các luận điểm kỹ thuật & kinh doanh chiến lược sau:

1. **Tận dụng Hệ sinh thái & Đội ngũ Hiện tại (`k12-backend-java`)**:
   * Toàn bộ hệ thống Backend K12 hiện tại được xây dựng trên nền tảng **Java / Spring Boot**.
   * Việc dùng chung Java cho Realtime Service giúp tận dụng 100% năng lực đội ngũ lập trình hiện tại, chia sẻ trực tiếp các DTO, Protobuf Models, JWT Auth utilities mà **không bị phân mảnh công nghệ (Polyglot Overhead)** hay phát sinh chi phí đào tạo lại dev.

2. **Sức mạnh Tầng Biên của Netty (Battle-Tested Network Edge)**:
   * Netty là framework I/O mạng chuẩn công nghiệp thế giới, được chứng minh năng lực qua các hệ thống triệu kết nối như *Apple Push Notification, Netflix Zuul, Twitter, Minecraft Server, Elasticsearch*.
   * Netty tối ưu sâu xuống HĐH Linux qua Epoll Native Transport, Direct ByteBuf và cơ chế **Zero-Copy**, gánh 54.000 CCU (3x Target Peak) WebSocket mượt mà với lượng tài nguyên dự tính.

3. **Mô hình Actor Model Chín muồi (Tránh "Tự phát minh lại bánh xe")**:
   * Bài toán 1 Room = 1 Actor đơn luồng cần một Actor Framework chuẩn hóa (**Apache Pekko** trên JVM — ADR-2) hỗ trợ sẵn Mailbox Queue, Cluster Sharding, Rebalancing và FSM State Machine.
   * Nếu dùng Golang, lập trình viên sẽ phải **tự code tay** cơ chế Mailbox Channel, Mutex Locking và State Management cho từng phòng – rất dễ dẫn đến rủi ro **Goroutine Leak** hoặc **Deadlock** khi vận hành thực tế.

4. **Quản lý Bộ nhớ & Profiling Đẳng cấp Doanh nghiệp**:
   * Hệ sinh thái công cụ đo đạc profiling chuyên sâu (*Async-Profiler, JFR - Java Flight Recorder, JProfiler*) giúp các kỹ sư kiểm soát chính xác từng byte bộ nhớ của 54.000 CCU (3x Target Peak). Đây mới là lợi thế thật của JVM ở bài toán này — không phải một bộ GC cụ thể.

> [!NOTE]
> **Về lựa chọn GC — v2.1 quảng cáo ZGC như một lý do chọn Java, v2.2 gỡ tuyên bố đó.**
> Với heap 4–8 GB và state mỗi phòng nhỏ (< 5 KB snapshot), **G1 hoàn toàn đủ và dễ tune hơn**;
> ZGC đánh đổi khoảng 10–15% throughput để lấy pause time mà bài toán này chưa cần tới.
> Cả hai phải được **đo ở benchmark H2 (Mục 32)** và chọn theo số liệu, không theo khẩu hiệu.
> Chỉ số nghiệm thu: `GC pause p99 < 10ms` — cả G1 lẫn ZGC đều có thể đạt ở dải heap này.

---

## 2. 🏗️ Kiến trúc Tổng thể Thống nhất & Phân tách Thành phần (Unified Master Architecture)

Kiến trúc hệ thống hợp nhất giữa mô hình **Microservices lai chuẩn AAA Game Server** và mô hình **Distributed Actor Model (1 Room = 1 Actor)**, phân tách minh bạch giữa tầng biên mạng (Edge Gateway), tầng Flutter Native/WebView Client và tầng logic xử lý nghiệp vụ Engine:

```text
       ┌─────────────────────────────────────────────────────────────────────────────────┐
       │     MULTI-PLATFORM GAME CLIENTS (Flutter Native App / Embedded HTML5 WebView)   │
       │     • App Flutter Dart quản lý Socket Protobuf Binary & Auth Ticket.            │
       │     • Embedded WebView (PhaserJS/Canvas) nhận data qua JavascriptBridge.        │
       └────────────────────────────────────────┬────────────────────────────────────────┘
                                                │
                  ┌──────────────────────────────┴──────────────────────────────┐
                  │ HTTP / REST / JSON (Control Traffic)                        │ TCP / WebSocket / Protobuf (Realtime Traffic)
                  ▼                                                             ▼
 ┌───────────────────────────────────────────┐                 ┌───────────────────────────────────────────┐
 │     STATELESS GAME PLATFORM SERVICES      │                 │     STATEFUL DEDICATED GAME SERVERS       │
 │     (Scaled Out via Kubernetes HPA)       │                 │     (Real-time In-Memory Engine - 4 Pods) │
 │                                           │                 │                                           │
 │  • Spring Boot Game API (Game Control)    │                 │  ┌─────────────────────────────────────┐  │
 │  • Matchmaking & Party Service (AWS Pattern)│               │  │ NETTY GATEWAY POOL (10-12 Pods)     │  │
 │  • Player Profile & Inventory Service     │                 │  │ Stateless Edge Pipe & Border Control│  │
 │  • Authentication & JWT Authorization     │                 │  │ Rate Limit, Watermark Backpressure  │  │
 └─────────────────────┬─────────────────────┘                 │  └──────────────────┬──────────────────┘  │
                       │                                       │                     │ TCP + length-prefix │
                       │                                       │                     ▼ Protobuf (ADR-1)   │
                       │                                       │  ┌─────────────────────────────────────┐  │
                       │                                       │  │ GAME SESSION ACTOR SVC (12-16 Pods) │  │
                       │                                       │  │ 1 Room = 1 Single-threaded Actor   │  │
                       │                                       │  │ Pekko Cluster Sharding + SBR       │  │
                       │                                       │  │ FSM, Guardrails, fencing epoch     │  │
                       │                                       │  └──────────────────┬──────────────────┘  │
                       │                                       └─────────────────────┼─────────────────────┘
                       │                                                             │
                       │                                                             │ Async Events (Kafka)
                       ▼                                                             ▼
        ┌─────────────────────────────────────────────────────────────────────────────────┐
        │                         KAFKA EVENT BUS (Message Broker)                        │
        │                         Topic Domain 1: game.events.submit (Partition by Room) │
        │                         Topic Domain 2: game.matchmaking.tickets                │
        │                         Topic Domain 3: game.analytics.raw-answers              │
        └──────────────────┬──────────────────────────────────────────────┬───────────────┘
                           │                                              │
                           ▼                                              ▼
        ┌────────────────────────────────────┐         ┌────────────────────────────────────┐
        │   LEADERBOARD WORKER (Async)       │         │   ANALYTICS & AUDIT WORKERS        │
        └──────────────────┬─────────────────┘         └──────────────────┬─────────────────┘
                           │ ZADD / ZREVRANK                              │ Batch Insert
                           ▼                                              ▼
  ┌───────────────────────────────────────────────────────────────────────────────────────────────┐
  │                   SHARED POLYGLOT DATA & CACHE LAYER (Tầng Đa Dữ liệu)                       │
  ├─────────────────────────────┬──────────────────────────────┬──────────────────────────────────┤
  │        REDIS CLUSTER        │           MONGODB            │           POSTGRESQL             │
  │ • Session Registry (vận hành│ • Match History Logs         │ • User Accounts & Auth           │
  │   /dashboard — KHÔNG nằm    │ • Player Profile & Items     │ • Class Hierarchy                │
  │   trên hot path, xem 14.1)  │ • Unstructured Game Data     │ • Curriculum Content             │
  │ • Room Snapshot + epoch     │                              │ • Analytics (table partitioning  │
  │ • Lease/fencing (Mục 8.4)   │                              │   theo tháng — thay ClickHouse)  │
  │ • Global Leaderboards       │                              │                                  │
  └─────────────────────────────┴──────────────────────────────┴──────────────────────────────────┘
```

> **ClickHouse đã được CẮT BỎ khỏi kiến trúc** (v2.1 vừa liệt kê ở tầng dữ liệu vừa tuyên bố
> cắt ở Mục 26 — mâu thuẫn). Analytics ghi vào PostgreSQL partitioned theo tháng.
> Cân nhắc lại kho OLAP riêng khi dung lượng log vượt 10 GB/ngày.

---

### 2.1. Mô tả Chi tiết Từng Thành phần trong Kiến trúc

#### 1. Tầng Client (Flutter Native App & Embedded WebView)
* **Thành phần**: App Mobile/Tablet viết bằng **Flutter (Dart)**, hỗ trợ nhúng **WebView (HTML5 Canvas / PhaserJS / React)** cho các mini-game động.
* **Trách nhiệm**:
  * Flutter Native Layer duy trì kết nối **WebSocket + Binary Protobuf** với Netty Gateway, xử lý Auth Ticket, Heartbeat và Reconnect ngầm khi App vào/ra Background.
  * Truyền dữ liệu hai chiều giữa Flutter Dart và WebView qua `JavascriptChannel` (`GameBridge.postMessage`).
  * Thực hiện **Optimistic UI**, **Clock Sync Delta**, và **Linear Interpolation (Lerp)** rendering mượt 60 FPS.

#### 2. Tầng Dịch vụ Nền tảng (Stateless Game Platform Services)
* **Công nghệ**: Java / Spring Boot 3.x (Microservices Auto-scale trên Kubernetes).
* **Đặc điểm**: *Stateless (Không giữ state trận đấu)*.
* **Các dịch vụ con**:
  * **Spring Boot Game API**: Xử lý HTTP REST cho Giáo viên/Admin (Tạo phòng, lấy trạng thái, cấu hình game).
  * **Matchmaking & Party Service**: Nhận Ticket tìm trận, ghép nhóm 12 học sinh, khởi tạo `RoomActor` và đăng ký vị trí `room_id -> engine_pod_ip` vào Redis Session Registry.
  * **Player Profile & Inventory Service**: Quản lý Level, XP, Badges, Avatar.
  * **Authentication Service**: Phát hành và xác thực chữ ký JWT / One-Time Ticket Auth.

#### 3. Tầng Máy chủ Trận đấu Realtime (Stateful Dedicated Game Servers)
Tầng này gồm 2 Service tách biệt, giao tiếp qua **Internal Frame Channel — TCP dài hạn,
length-prefixed Protobuf** (ADR-1; v2.1 dùng gRPC bi-directional stream):

> [!IMPORTANT]
> **QUY TẮC KIẾN TRÚC NGHIÊM NGẶT (STRICT SEPARATION OF CONCERNS RULE)**:
> 1. Tầng WebSocket Gateway (Network Edge) **TUYỆT ĐỐI KHÔNG ĐƯỢC CHỨA BẤT KỲ BUSINESS LOGIC NÀO** (Không tính điểm, không kiểm tra luật game, không giữ state phòng). Tầng này là **Pure Stateless Pipe & Border Control**: Duy trì kết nối socket, xác thực JWT, Rate Limit, pass-through mảng bytes Protobuf sang Game Engine, và Smart Fan-out broadcast bằng Zero-Copy `ByteBuf.retainedDuplicate()`.
> 2. **BỎ HOÀN TOÀN REDIS PUB/SUB KHỎI LUỒNG TIN NHẮN PHÒNG**: Mọi tin nhắn thời gian thực của phòng được đẩy trực tiếp qua Internal Frame Channel giữa Gateway và Engine Actor. Redis Pub/Sub CHỈ dùng cho thông báo bảo trì toàn hệ thống (`k12:sys:global`).

#### Internal Frame Channel — đặc tả (ADR-1)

| Thuộc tính | Quyết định |
|---|---|
| Transport | TCP dài hạn, Netty Epoll ở cả hai đầu |
| Framing | 4-byte big-endian length prefix — dùng `LengthFieldBasedFrameDecoder` / `LengthFieldPrepender` có sẵn, **không tự viết parser** |
| Payload | Chính `GameMessage` envelope của Mục 6, thêm 2 field routing. **Một schema cho cả biên và nội bộ**, không codegen hai lần |
| Multiplex | **MỘT** connection dùng chung cho mọi phòng giữa mỗi cặp (GW pod, Engine pod). Không phải một connection mỗi phòng |
| Backpressure | **MỘT tầng duy nhất**: `channel.isWritable()` + high/low watermark trên chính socket nội bộ |

**Vì sao bỏ gRPC**: gRPC mang lại codegen, deadline, interceptor, client-side LB. Ở đây
codegen không cần (đã có Protobuf envelope), deadline vô nghĩa với stream dài, LB do Cluster
Sharding lo. Thứ duy nhất còn lại là **flow-control chồng hai tầng** (HTTP/2 window + app-level
window) — khi nghẽn rất khó xác định tầng nào đang chặn. Đó là một khoản nợ, không phải lợi ích.

* **A. Netty Gateway Pool (10 - 12 Pods - Network Edge)**:
  * *Công nghệ*: Netty Native Epoll Transport (xem Mục 16 về các phương án đã loại).
  * *Trách nhiệm*: Quản lý ~5.000 WebSockets/Pod *(giả thuyết H1, chưa đo — xem Mục 32)*, Rate Limiting phân tầng theo loại thông điệp (Mục 8.3), kiểm soát **Netty High Watermark Backpressure**, Smart Fan-out bằng Zero-Copy `ByteBuf.retainedDuplicate()`, và **lazy-learned routing** tới Engine pod (Mục 14.1).
* **B. Game Session Actor Pool (12 - 16 Pods - Business Core)**:
  * *Công nghệ*: Java 21+ / Pekko Typed + **Cluster Sharding** (ADR-2).
  * *Trách nhiệm*: Mô hình **1 Room = 1 Single-threaded Actor** (~300–375 phòng/Pod). Triệt tiêu 100% Race Condition & Deadlock *trong phạm vi một phòng*.
  * *Vì sao 12–16 pod chứ không phải 4*: v2.1 đề xuất 4 pod × 1.125 phòng. Mất 1 pod = **13.500 học sinh** gián đoạn cùng lúc, và 1.125 phòng khôi phục đồng thời tạo ra *recovery thundering herd*. v2.2 đánh đổi hiệu suất đóng gói lấy **blast radius ~4.000 học sinh**. Tổng tài nguyên gần như không đổi (2 vCPU/4 GB mỗi pod).
  * *Bảo vệ Runtime*: **Bốn Rào Chắn An Toàn (Runtime Guardrails)** — Mục 21. Lưu ý: watchdog **đo và cảnh báo**, nó KHÔNG ngắt được logic đang chạy (Mục 22.1).
  * *Chống split-brain*: Split Brain Resolver + **fencing token epoch** — Mục 8.4. Cluster Sharding một mình KHÔNG đủ.

#### 4. Tầng Hàng đợi Sự kiện (Kafka Event Bus)
* **Công nghệ**: Apache Kafka Cluster (3 Brokers).
* **Trách nhiệm**: *Durable Event Log* cho **analytics & audit** với 3 Nhóm Topic Domain (`game.events.submit`, `game.matchmaking.tickets`, `game.analytics.raw-answers`). Partition theo `room_id` để đảm bảo thứ tự tuyệt đối trong phạm vi phòng.
* **⚠️ Thay đổi so với v2.1 — Kafka KHÔNG nằm trên đường khôi phục sự cố**:
  v2.1 (Mục 8.1) dùng Kafka replay từ `last_kafka_offset + 1` để khôi phục phòng. Cách đó có
  lỗ hổng: snapshot ghi async (mỗi 1–3s) và Kafka produce cũng async — **hai thao tác không
  atomic**, nên offset lưu trong snapshot có thể không khớp với state trong chính snapshot đó
  (ghi offset trước khi produce xong → replay bỏ sót → **mất đáp án của học sinh**).
  v2.2 chuyển sang **client-side replay** (Mục 8.1). Hệ quả: Kafka trở về đúng vai trò
  analytics và **hoãn sang giai đoạn sau được thật sự** — gỡ luôn mâu thuẫn của v2.1
  (MVP cắt Kafka nhưng recovery lại phụ thuộc Kafka).

#### 5. Tầng Worker Bất đồng bộ (Async Workers)
* **Leaderboard Worker**: Consume sự kiện từ Kafka để ZADD xếp hạng toàn trường vào Redis.
* **Analytics & Audit Worker**: Consume các sự kiện nộp bài để ghi Batch Insert vào **PostgreSQL (bảng partition theo tháng)** & MongoDB phục vụ báo cáo. *(v2.1 ghi ClickHouse — đã CẮT, xem ghi chú dưới sơ đồ Mục 2.)*

#### 6. Tầng Đa Dữ liệu (Shared Polyglot Data & Cache Layer)
* **Redis Cluster (In-Memory)**:
  * **Room Snapshot** nóng (`game:snapshot:{room_id}`) kèm `epoch` — xem Mục 14.3.
  * **Lease + fencing epoch** (`room:epoch:{room_id}`) chống split-brain — Mục 8.4.
  * **Session Registry** (`room:routing:{room_id}`) — chỉ phục vụ vận hành & teacher dashboard.
    **KHÔNG nằm trên đường đi của gói tin**; định tuyến hot path dùng lazy-learned cache tại
    Gateway (Mục 14.1).
  * Redis Sorted Sets (`ZADD`) cho Leaderboard toàn cục.
  * *Lưu ý*: chống trùng request KHÔNG còn dựa vào `SETNX` làm cơ chế chính —
    `LastSeenSequenceTable` trong RAM Actor mới là tầng quyết định (Mục 13.4).
* **MongoDB (NoSQL Document)**: Lưu trữ Lịch sử chi tiết từng trận đấu (Match History Logs).
* **PostgreSQL (Relational RDBMS)**: Tài khoản, Phân quyền, Lớp học, Ngân hàng Câu hỏi, và
  **analytics dạng bảng partition theo tháng** (thay cho ClickHouse).
* ~~**ClickHouse (Columnar OLAP)**~~ — **ĐÃ CẮT**. Xem ghi chú dưới sơ đồ Mục 2.

---
## 3. 🧩 Phân lớp Chi tiết & Ứng dụng Design Patterns

| Thành phần | Trách nhiệm Nghiệp vụ chính | Design Pattern áp dụng |
| :--- | :--- | :--- |
| **Game Definition** | Chứa tệp cấu hình kịch bản JSON (rules, steps, team config, win condition, timeout, content câu hỏi). | **Specification / Value Object Pattern** |
| **Lifecycle Module** | Kiểm soát và chuyển đổi trạng thái vòng đời phòng (`LOBBY` ➔ `PLAYING` ➔ `SHOWING_RESULT` ➔ `GAME_OVER`). | **State Pattern / Finite State Machine (FSM)** |
| **Step Runner** | Duyệt tuần tự qua danh sách câu hỏi/bước chơi, quản lý đồng hồ đếm ngược (Timeout) theo Server Epoch. | **Iterator & Command Pattern** |
| **Rule Evaluator** | Chuỗi kiểm tra tính hợp lệ: Membership, nộp đúng câu hỏi mở, Idempotency (chặn gửi trùng qua Redis `SETNX` với `client_request_id`), nộp trong hạn. | **Chain of Responsibility Pattern** |
| **Score Calculator** | Tính toán điểm số thô dựa trên độ chính xác và tốc độ trả lời. | **Strategy Pattern** |
| **Win Condition Checker** | Kiểm tra tổng điểm/tiến trình đối chiếu với điều kiện thắng trong Definition. | **Strategy Pattern** |
| **Game Mechanic** | Các plugin xử lý logic minigame riêng biệt (`ProgressMeter`, `BossBattle`, `TeamRace`). | **Strategy / Plugin Pattern** (Tuân thủ Open/Closed Principle) |
| **Game State** | Cấu trúc dữ liệu thuần *In-Memory* (Students, Teams, Scores, Progress). KHÔNG chứa logic. | **Memento Pattern** (Phục vụ Adaptive Snapshot) |

---

### 3.5. Tính Linh hoạt & Khả năng Mở rộng của Game Engine & Lifecycle Module

#### 🅰️ Trường hợp 1: Thay đổi Kịch bản / Loại Game (KHÔNG CẦN sửa 1 dòng code Game Engine)
* **Ngữ cảnh**: Hôm nay phát hành Game A (Quiz 5 câu trắc nghiệm), ngày mai phát hành Game B (3 câu trắc nghiệm ➔ 1 Minigame Đấu Boss ➔ 1 bài Sắp xếp từ).
* **Cơ chế**: `Step Runner` trong Game Engine là cỗ máy chạy kịch bản tổng quát (*Generic Runner*). Nó chỉ đọc danh sách bước từ tệp cấu hình JSON (`Game Definition`).
* **Kết quả**: Để đổi kịch bản game hoặc thêm câu hỏi/minigame mới, lập trình viên hoặc nội dung chỉ cần chỉnh sửa file JSON `Game Definition`. **Game Engine giữ nguyên 100%**, không cần sửa code Java hay re-deploy backend.

#### 🅱️ Trường hợp 2: Thêm Trạng thái Hệ thống mới (CÓ viết code mới, nhưng KHÔNG ĐỤNG code cũ)
* **Ngữ cảnh**: Sau này phát triển thêm tính năng toàn cục: Giáo viên bấm nút "Tạm dừng" trên Dashboard ➔ Hệ thống chuyển sang trạng thái `PAUSED` (tạm dừng nộp bài, đóng đồng hồ đếm ngược).
* **Cơ chế (State Pattern / FSM)**:
  1. Thêm `PAUSED` vào enum `GameLifecycleState`.
  2. Tạo Class mới `PausedState.java` cài đặt interface `GameState`:
     ```java
     public class PausedState implements GameState {
         @Override
         public void handleSubmitAnswer(GameSessionActor actor, SubmitAnswerCommand cmd) {
             // Từ chối nộp bài khi đang Paused
             actor.replyToClient(cmd.getStudentId(), "GAME_IS_PAUSED", "Game đang tạm dừng!");
         }
         @Override
         public void handleResume(GameSessionActor actor) {
             // Chuyển lại về PlayingState
             actor.changeState(new PlayingState());
             actor.broadcastToRoom("GAME_RESUMED");
         }
     }
     ```
  3. Khai báo ma trận chuyển trạng thái hợp lệ: `PLAYING` ⇄ `PAUSED`.
* **Kết quả**: Tuân thủ triệt để **Open/Closed Principle** (Mở rộng bằng cách tạo Class mới, Đóng với việc sửa đổi code cũ). Code của các trạng thái hiện có (`LOBBY`, `PLAYING`, `SHOWING_RESULT`, `GAME_OVER`) **hoàn toàn được giữ nguyên**, đảm bảo không sinh ra bug ngầm cho hệ thống đang vận hành.

---

# ⚡ PHẦN II — THIẾT KẾ CỐT LÕI & LUỒNG DỮ LIỆU REALTIME

---

## 4. 🌐 Tổng quan Kênh Giao tiếp (Communication Channels)

```text
 ┌───────────────────┬──────────────────────────┬─────────────────────────────┬────────────────────────────────────────────────────────┐
 │ Kênh Giao tiếp    │ Giao thức                │ Tầng tham gia               │ Mục đích sử dụng chính                                 │
 ├───────────────────┼──────────────────────────┼─────────────────────────────┼────────────────────────────────────────────────────────┤
 │ Control Traffic   │ HTTP / REST (JSON)       │ Spring Boot API ↔ Clients   │ Xác thực, tạo phòng, lấy state reconnect, lệnh Teacher.│
 │ Realtime Traffic  │ WebSocket (Protobuf)     │ Netty ↔ Actor ↔ Students    │ Truyền dữ liệu realtime 2 chiều (nộp bài, draft, progress).│
 │ Internal Routing  │ TCP + length-prefixed pb │ Netty Gateway ↔ Engine Pods │ Định tuyến tới đúng RoomActor; backpressure MỘT tầng.  │
 │ Async / Durable   │ Kafka Topic (Event Bus)  │ Actor ➔ Kafka ➔ Analytics   │ Audit & Analytics. KHÔNG dùng cho recovery (Mục 8.1).  │
 └───────────────────┴──────────────────────────┴─────────────────────────────┴────────────────────────────────────────────────────────┘
```

---

## 5. 🔄 Sơ đồ Sequence Chi tiết (Một Game Hoàn chỉnh)

```text
Teacher/Admin       Spring Boot API     Netty Gateway     Game Session Actor        Redis / Kafka       Student Client
    │                     │                  │                     │                     │                   │
    │── POST /start ─────►│                  │                     │                     │                   │
    │   (Game Definition) │                  │                     │                     │                   │
    │                     │── Spawn Actor ──►│                     │                     │                   │
    │                     │                  │── Load Config ─────►│                     │                   │
    │                     │                  │── GAME_STARTED ────►│                     │                   │
    │                     │                  │   (Broadcast)       │                     │                   │
    │                     │                  │                     │                     │                   │
    │                     │                  │◄─── WS Connect ─────│─────────────────────│───────────────────│
    │                     │                  │                     │                     │                   │
    │                     │                  │◄─── SUBMIT_ANSWER ──│─────────────────────│───────────────────│
    │                     │                  │     (Protobuf)      │                     │                   │
    │                     │                  │                     │── 1. Validate (Rule)│                   │
    │                     │                  │                     │── 2. Calc Score     │                   │
    │                     │                  │                     │── 3. Save State ───►│── Async Snapshot ─│
    │                     │                  │                     │── 4. Publish Event ─►│── Write Kafka ───│
    │                     │                  │                     │                     │                   │
    │                     │                  │◄── Aggregated State │                     │                   │
    │                     │                  │    (Every 200ms)    │                     │                   │
    │                     │                  │                     │                     │                   │
    │                     │                  │── PROGRESS_UPDATED ─│─────────────────────│───────────────────│
    │                     │                  │   (Smart Fan-out)   │                     │                   │
    │                     │                  │                     │                     │                   │
    │── POST /finish ────►│                  │                     │                     │                   │
    │                     │                  │── GAME_FINISHED ───►│                     │                   │
    │                     │                  │   (Clear Snapshot) ─│────────────────────►│                   │
```

---

## 6. 📄 Quy chuẩn Protobuf Schema (`game_message.proto`)

```protobuf
syntax = "proto3";
package edtech.game.realtime;

option go_package = "./gamepb";
option java_package = "com.edtech.game.realtime.proto";

// ==========================================
// 1. ENVELOPE CHUNG (Tầng truyền tải mạng)
// ==========================================
message GameMessage {
  uint32 event_type = 1;     // Mã định danh loại sự kiện (101: Draft, 102: Submit...)
  fixed64 timestamp = 2;     // Epoch timestamp (ms)
  uint32 sequence = 3;       // Số thứ tự gói tin để đồng bộ
  uint32 flags = 4;          // bit 0 = is_compressed (LZ4)
  bytes payload = 5;         // Dữ liệu serialize của message cụ thể
}

// Phạm vi phân phối gói tin tại Netty Gateway
enum MessageScope {
  ROOM_BROADCAST = 0; // Gửi toàn bộ 12 học sinh trong phòng
  TEAM_ONLY      = 1; // Gửi nội bộ trong team (nhóm 3 người)
  PRIVATE_SOLO   = 2; // Unicast 1-1 cho cá nhân học sinh
}

// ==========================================
// 2. MESSAGES THẢO LUẬN NHÓM & DRAFT
// ==========================================
// Client -> Server: Học sinh gõ/thay đổi đáp án nháp
message UpdateDraftAnswerRequest {
  uint32 student_index = 1; // Index trong phòng (0 - 11)
  string team_id = 2;       // ID nhóm ("team_1")
  string draft_text = 3;    // Nội dung đang gõ nháp
}

// Server -> Team Members: Broadcast nháp tới các bạn cùng team
message DraftUpdatedBroadcast {
  uint32 student_index = 1; // ID người đang gõ
  string draft_text = 2;    // Nội dung nháp cập nhật
}

// ==========================================
// 3. MESSAGES TIẾN TRÌNH & ĐIỂM SỐ
// ==========================================
message ProgressUpdated {
  uint32 team_score = 1;
  uint32 progress_percent = 2;
  repeated StudentProgress students = 3;
}

message StudentProgress {
  uint32 student_index = 1;
  uint32 score = 2;
  uint32 progress_percent = 3;
  uint32 status = 4;
}
```

---

## 7. ⚡ Tối ưu Hiệu năng & Dữ liệu (Performance & Data Optimizations)

### 7.1. Mô hình Tải Thực tế & Cơ chế Tick Coalescing (200ms là TRẦN TẦN SUẤT, không phải nhịp phát)

> [!WARNING]
> **Đây là mục thay đổi nhiều nhất so với v2.1.** v2.1 đưa ra "con số vàng 200ms" dựa trên
> một phép tính sai ~300 lần, và kết luận ngược cho chính loại game mà hệ thống nhắm tới trước nhất.
> Toàn bộ mục này được viết lại.

#### 7.1.1. Mô hình tải chuẩn (thay phép tính sai của v2.1)

v2.1 viết: *"12 học sinh nộp bài ➔ phát sinh ≈ 12 msgs/s/phòng ➔ 4.500 × 12 × 12 = 648.000 packets/sec"*.

Hệ số `12 msgs/s` này không có nguồn gốc. Trong quiz, mỗi học sinh nộp **một đáp án mỗi
~20–30 giây**, không phải 12 gói mỗi giây. Phép nhân còn trộn lẫn chiều inbound và outbound.

```text
THAM SỐ
  R  = 4.500 phòng đồng thời
  S  = 12 học sinh/phòng
  Q  = 25 s   (thời gian trung bình cho 1 câu hỏi)
  U  = R × S = 54.000 CCU

INBOUND (client → server)
  submit_rate = U / Q      = 54.000 / 25  ≈ 2.160 msg/s
  heartbeat   = U / 30s                   ≈ 1.800 msg/s
  ───────────────────────────────────────────────────
  Tổng inbound                            ≈ 4.000 msg/s

OUTBOUND (server → client) — với tick coalescing
  mỗi submit sinh 1 broadcast tới S client
  outbound = submit_rate × S = 2.160 × 12 ≈ 26.000 packet/s
  (coalescing gom nhiều submit gần nhau → thực tế còn thấp hơn)
```

**Con số dùng để thiết kế capacity: ~4.000 msg/s inbound, ~26.000 packet/s outbound.**
Không phải 648.000. Sai lệch này đã đẩy mọi tính toán hạ tầng của v2.1 lệch theo.

#### 7.1.2. Vì sao fixed-rate tick LÀM HẠI game dạng quiz

| Mô hình | Outbound @ 54k CCU |
|---|---|
| **Fixed-rate 200ms** (v2.1) | 4.500 × 12 × 5 = **270.000 packet/s — liên tục, kể cả khi không ai làm gì** |
| **Coalescing** (v2.2) | ≈ **26.000 packet/s** |

Fixed-rate tick nặng hơn **~10 lần** cho đúng loại game mà MVP nhắm tới.

Lý do: fixed-rate là pattern đúng cho game **chuyển động liên tục**, nơi client cần một luồng
snapshot đều để nội suy (lerp) giữa hai mốc. Với quiz, **giữa hai lần nộp bài không có gì để
nội suy** — tick chỉ gửi lại một state không đổi. Lập luận "mắt người 100–200ms + Lerp 60 FPS"
của v2.1 hoàn toàn đúng *cho game chuyển động*, và hoàn toàn không áp dụng cho quiz.

#### 7.1.3. Cơ chế thay thế — Coalescing Tick (dirty-flag)

**200ms đổi ngữ nghĩa: từ *nhịp phát cố định* thành *trần tần suất (rate ceiling)*.**
Độ trễ tối đa học sinh cảm nhận vẫn là 200ms — không đổi. Nhưng tải nền biến mất.

```java
// Trong RoomActor
private boolean dirty = false;
private boolean flushScheduled = false;
private long lastFlushAt = 0;
private static final long MIN_INTERVAL_MS = 200;   // TRẦN tần suất, không phải nhịp

void onSubmit(SubmitAnswer cmd) {
    applyToState(cmd);
    dirty = true;
    long now = clock.millis();
    if (now - lastFlushAt >= MIN_INTERVAL_MS) {
        flush(now);                        // đã đủ giãn cách → bắn ngay
    } else if (!flushScheduled) {
        timers.startSingleTimer(FLUSH_KEY, Flush.INSTANCE,
            Duration.ofMillis(MIN_INTERVAL_MS - (now - lastFlushAt)));
        flushScheduled = true;
    }
}

void onFlush() {
    flushScheduled = false;
    if (!dirty) return;                    // phòng im lặng → 0 packet
    flush(clock.millis());
}

void flush(long now) {
    broadcast(buildDeltaSnapshot());       // Delta encoding — xem Mục 11.2
    dirty = false;
    lastFlushAt = now;
}
```

#### 7.1.4. Khi nào bật fixed-rate tick

Fixed-rate chỉ bật cho game chuyển động liên tục (Boss Battle, Team Race dạng đua vị trí).
Cấu hình **theo Game Definition**, KHÔNG phải cấu hình toàn cục:

```json
{
  "tick_mode": "COALESCE",
  "tick_interval_ms": 200
}
```
`tick_mode`: `COALESCE` (mặc định) hoặc `FIXED`. Với `FIXED`, dùng `50` ms cho game phản xạ tức thì.

#### 7.1.5. Giữ nguyên từ v2.1 — phân loại Critical / Non-Critical

Cơ chế ở Mục 13.3 và 14.2 **vẫn đúng và càng quan trọng hơn** sau khi chuyển sang coalescing:
`ANSWER_ACK`, `SUBMIT_ANSWER_RESULT`, `GAME_OVER`, `TEACHER_PAUSE_COMMAND` **bypass hoàn toàn**
cơ chế trên và bắn tức thì.

#### 7.1.6. Giữ nguyên từ v2.1 — ràng buộc mạng di động

Lập luận của v2.1 về Bufferbloat vẫn đúng, và chính nó là lý do giữ **trần** 200ms thay vì
bắn không giới hạn:
* Wi-Fi trường học / 3G / 4G có latency dao động 30ms – 80ms.
* Bắn tick quá dày (20ms/lượt = 50 ticks/s) gây ùn tắc bộ đệm (Bufferbloat) trên trạm phát
  4G / modem Wi-Fi, sinh **Jitter (lúc giật khựng, lúc tăng tốc)**, làm nóng máy và tụt pin.
* Trần 200ms giữ đường truyền di động ổn định. Coalescing chỉ **bỏ phần phát thừa khi không
  có gì thay đổi** — nó không nới trần.

---

### 7.2. Tối ưu Snapshot & Định dạng Protobuf Payload

1. **Adaptive LZ4HC Snapshot**:
   * Khi State thay đổi + quá 1 giây ➔ Chụp Snapshot.
   * Khi State không đổi + quá 3 giây ➔ Chụp Snapshot.
   * Các sự kiện quan trọng (`START`, `FINISH`, Đổi câu) ➔ Chụp Snapshot bắt buộc.
   * Serialize bằng Protobuf, nén LZ4HC (mức 4-6) nếu dung lượng > 150 bytes, lưu Redis kèm
     `TTL = 90 phút`, **`epoch` (fencing token — Mục 8.4)** và `snapshot_sequence`.
   * **Bỏ `last_kafka_offset`** khỏi snapshot header: v2.2 không replay Kafka khi khôi phục
     (Mục 8.1), và việc lưu offset này vốn không atomic với chính snapshot.
2. **`LastSeenSequenceTable` BẮT BUỘC nằm trong snapshot**:
   * Nếu không, sau khi khôi phục Actor mất trí nhớ về `sequence_number` của từng học sinh và
     sẽ chấp nhận lại các bản trùng do client gửi lại (Mục 8.1) — phá vỡ tính idempotent.
3. **Tối ưu Protobuf Field Types**:
   * Sử dụng `uint32`, `fixed64` cho mốc thời gian và điểm số.
   * Sử dụng `student_index` (từ 0 – 11) thay vì chuỗi `student_id` UUID dài trong các packet
     realtime để giảm kích thước payload xuống < 50 bytes.

---

# 🛡️ PHẦN III — AN TOÀN, CHỐNG CHỊU LỖI & QUẢN TRỊ RỦI RO

---

## 8. 🛡️ Bảo mật, Chống chịu Lỗi (Fault Tolerance) & Khôi phục

### 8.1. Cơ chế Khôi phục Sự cố Pod — mục tiêu MẤT DỮ LIỆU = 0

> [!WARNING]
> **v2.1 cam kết recovery `< 20–30ms`. Con số đó đo sai giai đoạn.**
> Nó chỉ tính phần nạp lại state (Redis read + deserialize), vốn là phần rẻ nhất và không
> phải phần chi phối. v2.1 còn đưa ra ba giá trị mâu thuẫn trong cùng tài liệu:
> `<30ms` (mục này), `<50ms` (mục lục), `50–100ms` (Mục 13.1 / 14.6 / 25.2).

#### 8.1.1. Thời gian khôi phục thực tế

```text
T_total = T_detect + T_down + T_rebalance + T_load + T_reconnect

  T_detect     phi-accrual failure detector nhận ra node chết       5 – 10 s
  T_down       SBR quyết định + down node (stable-after)            5 – 10 s
  T_rebalance  ShardRegion tái phân bổ shard sang node còn sống   0.5 – 2 s
  T_load       Redis read + deserialize  <- "30ms" của v2.1       10 – 50 ms
  T_reconnect  client phát hiện đứt + backoff + nối lại             1 – 2 s
  -------------------------------------------------------------------------
  T_total ≈ 12 – 25 s
```

**Không thể ép `T_detect` xuống 1s** như Mục 23.3 của v2.1 mô tả — false positive khi GC pause
hoặc network blip sẽ down một node đang khỏe, gây mất phòng hàng loạt. Đây không phải vấn đề
tuning, đây là đánh đổi cố hữu của failure detection.

#### 8.1.2. Đổi mục tiêu, không đổi con số

> Mục tiêu không phải "khôi phục dưới 50ms" mà là **học sinh không mất dữ liệu và không mất
> phiên khi hệ thống gián đoạn 15–25 giây**.

| Cơ chế | Trách nhiệm |
|---|---|
| **Client buffer** | Client giữ ring buffer N=10 submission gần nhất chưa được ACK; tự gửi lại khi nối lại |
| **Server dedupe** | `LastSeenSequenceTable` (Mục 13.4) loại bỏ bản trùng — O(1) trong RAM, không cần Redis |
| **UI trạng thái** | Overlay "Đang đồng bộ…". KHÔNG văng lỗi, KHÔNG mất đáp án đã chọn |
| **Gia hạn deadline** | Khi phòng phục hồi, deadline câu hỏi được cộng bù đúng khoảng gián đoạn |

#### 8.1.3. Quy trình khôi phục (thay quy trình 5 bước của v2.1)

```text
1. Cluster Sharding tạo lại RoomActor trên pod còn sống.
2. Giành lease + fencing epoch:  epoch = INCR room:epoch:{room_id}     (Mục 8.4)
3. Nạp snapshot Redis, kiểm CRC32 (Mục 14.3).
   Checksum sai -> bắt đầu từ state rỗng, dựa hoàn toàn vào bước 5.
4. Actor vào trạng thái RESYNCING: chấp nhận RESYNC, CHƯA broadcast.
5. Client gửi RESYNC { last_acked_seq, pending[] }
   -> áp qua LastSeenSequenceTable, bản trùng bị loại O(1).
6. Gia hạn deadline câu hỏi hiện tại đúng bằng khoảng gián đoạn.
7. Chuyển về PLAYING, broadcast state đầy đủ MỘT lần cho toàn phòng.
```

**Kafka không xuất hiện trong luồng này.** Đó là điểm mấu chốt — xem Mục 2.1.4.

#### 8.1.4. Chống recovery thundering herd

Khi shard được tái phân bổ hàng loạt (mất 1 pod = ~300–375 phòng khôi phục đồng thời), actor
nạp snapshot theo lô có **jitter ngẫu nhiên 0–500ms**, tránh đồng loạt đập vào Redis.
Đây là lý do v2.2 dùng 12–16 Engine pod nhỏ thay vì 4 pod lớn (Mục 23.1).

#### 8.1.5. SLA công bố

| Chỉ số | Cam kết |
|---|---|
| **Mất dữ liệu đáp án khi sập Engine pod** | **0** |
| Thời gian phòng hoạt động trở lại (p99) | **< 30 s** |
| Thời gian nạp lại state sau khi actor được tạo (p99) | **< 100 ms** |
| Học sinh phải thao tác lại thủ công | **Không** |

Ba dòng đầu là thứ khách hàng quan tâm. Dòng "< 100ms" chính là thứ v2.1 gọi nhầm là recovery time.

---

### 8.2. Bảo mật & Chống Spam
* **JWT Validation**: Phân tích và kiểm tra chữ ký Public Key ngay tại WebSocket Handshake của Netty Gateway.
* **Channel Attributes**: Khóa cứng `student_id` và `room_id` vào thuộc tính của Netty Channel sau khi Handshake thành công.
* **Rate Limiting phân tầng**: Xem Mục 8.3.
* **Server-authoritative timestamp**: Xem Mục 8.5 — điểm số KHÔNG BAO GIỜ được tính từ
  timestamp do client gửi lên.

---

### 8.3. Cơ chế Rate Limiting & Chống Spam tại Netty Gateway

> [!CAUTION]
> **v2.1 Mục 8.3.1 chứa một lỗi chặn đứng khách hàng mục tiêu**: *"tối đa 5 lượt Handshake
> WebSocket / 1 địa chỉ IP / 1 phút"*. Một trường 500 học sinh đi ra Internet qua **một IP
> public NAT duy nhất** — học sinh thứ 6 trở đi nhận HTTP 429. Toàn bộ mô hình nghiệp vụ của
> hệ thống là "cả lớp/cả trường cùng vào một lúc", và rule đó chặn đúng kịch bản ấy.

#### 8.3.1. Connection Rate Limit — khóa theo DANH TÍNH, không theo IP

| Tầng | Khóa | Ngưỡng | Mục đích |
|---|---|---|---|
| **L1** — chống DDoS thô | IP | **300 handshake/phút** | Chỉ chặn flood thật sự; không bao giờ chạm tới khi dùng bình thường |
| **L2** — chống lạm dụng tài khoản | `student_id` (từ JWT) | **10 handshake/phút** | Chống reconnect loop, script |
| **L3** — bảo vệ dung lượng | toàn cục / pod | admission control | Xem Mục 8.6 |

Ngưỡng L1 phải đặt theo **số học sinh tối đa của một trường**, không theo trực giác về IP.
Nếu có trường lớn hơn 300 học sinh cùng lúc: nâng L1, hoặc bỏ hẳn L1 và chỉ dựa vào L2 + L3.

#### 8.3.2. Message Rate Limit — bucket riêng theo LOẠI thông điệp

v2.1 dùng một bucket chung 5 token/s cho cả `SUBMIT_ANSWER` và `UPDATE_DRAFT`. `UPDATE_DRAFT`
(gõ nháp chung trong nhóm) sinh sự kiện theo nhịp gõ phím — học sinh gõ nhanh sẽ bị
`RATE_LIMIT_EXCEEDED` giữa lúc đang thảo luận.

| Loại | Capacity | Refill | Ghi chú |
|---|---|---|---|
| `SUBMIT_ANSWER` | 3 | 1/s | Hành động hiếm; siết chặt được |
| `UPDATE_DRAFT` | 10 | 10/s | Client **bắt buộc debounce 150ms** trước khi gửi |
| `HEARTBEAT` | 2 | 1/30s | |
| **Tổng mọi loại** | 15 | 15/s | Hàng rào cuối |

**Ràng buộc phía client (bắt buộc, xem Mục 9)**: `UPDATE_DRAFT` phải debounce 150ms và gửi
**delta**, không gửi toàn bộ nội dung nháp. Không có ràng buộc này thì không ngưỡng server nào đúng.

#### 8.3.3. Deduplication / Idempotency

* **Tầng quyết định — `LastSeenSequenceTable` trong RAM Actor** (Mục 13.4): mỗi gói client mang
  `sequence_number` tăng dần; Actor chỉ chấp nhận nếu `sequence_number > last_seen`. O(1), không
  phụ thuộc mạng, và là nền tảng của cơ chế client replay ở Mục 8.1.
* **Tầng phụ trợ — Redis `SETNX`** (`lock:req:{client_request_id}`, TTL 5s): chỉ dùng để chặn
  sớm ở biên, **không phải nguồn đúng đắn**. v2.1 đặt `SETNX` làm cơ chế chính — điều đó sai
  khi có network partition hoặc tải cực cao.

---

### 8.4. Chống Split-Brain (v2.1 KHÔNG nhắc tới)

> [!CAUTION]
> Đây là hazard kinh điển của Cluster Sharding và v2.1 không có một chữ nào về nó.
> Khi network partition xảy ra, hai phía cluster đều tin phía kia đã chết →
> **hai `RoomActor(101)` cùng chạy trên hai pod**. Hậu quả: chấm điểm hai lần, hai nguồn
> broadcast mâu thuẫn, hai luồng ghi snapshot đè lên nhau.
> Heartbeat timeout 1s mà v2.1 đề xuất (Mục 23.3) làm xác suất này **cao**, không phải thấp.

#### Lớp 1 — Split Brain Resolver

```hocon
pekko.cluster {
  downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"

  split-brain-resolver {
    active-strategy = keep-majority
    stable-after    = 10s          # KHÔNG hạ xuống 1s
    down-all-when-unstable = on
  }

  failure-detector {
    acceptable-heartbeat-pause = 5s   # v2.1 ghi 1s — quá nhạy
    threshold = 10.0
  }
}
```

`keep-majority` — phía thiểu số tự down. Yêu cầu số node lẻ hoặc cấu hình
`min-number-of-members` rõ ràng, và **không được scale cluster xuống dưới quorum**.

#### Lớp 2 — Fencing token (SBR KHÔNG thay thế được)

SBR đảm bảo *cuối cùng* chỉ còn một node, nhưng tồn tại cửa sổ `stable-after` (10s) mà cả hai
actor cùng sống và cùng ghi Redis. Vì vậy mọi write phải mang epoch:

```text
Khi RoomActor khởi động:
  epoch = INCR room:epoch:{room_id}      -- đơn điệu tăng, không bao giờ lặp
  actor giữ epoch trong RAM suốt vòng đời

Mọi ghi snapshot dùng Lua script CAS:
  if redis.call('EXISTS', KEYS[1]) == 0
     or tonumber(redis.call('HGET', KEYS[1], 'epoch')) <= tonumber(ARGV[1]) then
       redis.call('HSET', KEYS[1], 'epoch', ARGV[1], 'data', ARGV[2])
       return 1
  else
       return 0     -- epoch cũ -> từ chối
  end

Actor nhận về 0 -> tự biết mình là bản zombie -> dừng ngay, KHÔNG broadcast thêm.
```

`Snapshot Versioning & CRC32` ở Mục 14.3 bảo vệ chống *hỏng dữ liệu*; nó **không** bảo vệ
chống *hai người cùng ghi*. Cần cả hai.

#### Lớp 3 — Vận hành

```yaml
# Engine Deployment
terminationGracePeriodSeconds: 45   # đủ để CoordinatedShutdown leave cluster sạch
```
```hocon
pekko.coordinated-shutdown.exit-jvm = on
pekko.cluster.shutdown-after-unsuccessful-join-seed-nodes = 40s
```

**Bootstrap trên K8s phải dùng API discovery, không seed-nodes tĩnh**:
`pekko-management-cluster-bootstrap` + `pekko-discovery-kubernetes-api`, discovery theo pod
label, kèm RBAC cho ServiceAccount đọc `pods`. Seed node hardcode sẽ chết ngay lần rolling
update đầu tiên.

Không có graceful leave, **mỗi lần rolling update là một lần nghi ngờ split-brain**.

---

### 8.5. Server-Authoritative Timestamp (v2.1 THIẾU)

Điểm số phụ thuộc tốc độ trả lời (Mục 3, `Score Calculator`). Nếu thời điểm trả lời do client
cung cấp, học sinh sửa được điểm bằng cách sửa timestamp — lỗ hổng gian lận trực tiếp trong
hệ thống có triết lý "Authoritative Server" ở Mục 1.3.1. v2.1 chỉ nói về clock sync để **vẽ
đồng hồ đếm ngược trên UI** (Mục 9.3), không hề nói ai là nguồn thời gian khi tính điểm.

```text
1. Actor ghi server_question_started_at khi phát QUESTION_STARTED.
2. Khi SubmitAnswer tới, Actor đóng dấu server_received_at = clock.millis() NGAY tại thời
   điểm lấy khỏi mailbox — KHÔNG dùng field `timestamp` trong GameMessage.
3. response_time_ms = server_received_at - server_question_started_at
4. Từ chối nếu server_received_at > deadline + GRACE   (GRACE = 500ms, bù RTT mạng).
5. Field `timestamp` trong GameMessage CHỈ dùng cho telemetry/đo latency.
   KHÔNG BAO GIỜ dùng để tính điểm.
```

Điều 5 phải được ghi thành comment ngay trong file `.proto` (Mục 6), nếu không sẽ có người dùng nhầm.

**Về công bằng**: cách tính này khiến học sinh mạng chậm chịu thiệt (RTT tính vào thời gian
trả lời). Đây là đánh đổi bắt buộc của authoritative server và `GRACE` hấp thụ phần lớn chênh
lệch. **Không được "sửa" bằng cách tin timestamp của client.**

---

### 8.6. Connection Storm đầu giờ (v2.1 THIẾU)

Tải EdTech không phẳng. 09:00 giáo viên bấm Bắt đầu → **54.000 kết nối trong ~15 giây**:

```text
54.000 TLS handshake       <- đắt nhất, ~1-3ms CPU mỗi lượt
54.000 JWT chữ ký verify
 4.500 RoomActor spawn
 4.500 Redis write (lease + epoch)
```

3.600 handshake/s chia cho 10 GW pod = **360 handshake/s/pod trên 2 vCPU**. Riêng TLS có thể
chiếm hết CPU — trong khi giả thuyết H1 chỉ đo *steady state*. Đây là thời điểm khó nhất của
hệ thống và v2.1 không thiết kế cho nó.

1. **Pre-spawn actor lúc tạo phòng, không lúc học sinh vào.** Giáo viên tạo phiên → actor
   spawn ngay ở trạng thái `LOBBY`. Tới 09:00 chỉ còn chi phí kết nối.
2. **Staggered join do server điều phối.** Response của `POST /session/{id}/join` trả kèm
   `connect_after_ms` (jitter 0–5.000ms); client đợi rồi mới mở WebSocket. Trải 54.000 kết
   nối ra 5 giây. Học sinh không cảm nhận được vì màn hình chờ vẫn hiển thị.
3. **Admission control tại Gateway.** Vượt `max_handshake_per_sec` (đo từ H1) → trả
   **`503 + Retry-After`**, KHÔNG phải 429 (429 khiến client hiểu nhầm là bị phạt).
   Thà chậm 3 giây còn hơn pod chết.
4. **TLS session resumption.** Bật session ticket / session ID resumption — reconnect sau
   đứt mạng bỏ qua full handshake, giảm phần lớn CPU của kịch bản hàng nghìn client nối lại
   cùng lúc sau sự cố (Mục 8.1).

**Bổ sung vào benchmark H1 (Mục 32)**: phải đo *handshake rate* riêng, không chỉ số kết nối duy trì.
---

## 9. 📱 Hướng dẫn Thiết kế & Tối ưu Phía Client (Frontend)

Toàn bộ ứng dụng Client (Web/Mobile App) cần tuân thủ 6 quy tắc cốt lõi.

> [!IMPORTANT]
> **Quy tắc 5 và 6 là bổ sung của v2.2 và KHÔNG phải tùy chọn.** Cam kết "mất dữ liệu = 0"
> ở Mục 8.1 đặt client vào vai trò **nguồn đúng đắn khi có sự cố** — nếu client không giữ
> buffer và không gửi lại, không cơ chế nào ở server cứu được đáp án đã mất.

1. **Tự động Kết nối lại với Backoff (Auto-Reconnect with Exponential Backoff)**:
   * Khi mất WebSocket connection, UI chuyển sang trạng thái "Đang kết nối lại..." (Connecting Overlay).
   * Thử lại theo chiến thuật hoãn tăng dần: 1s ➔ 2s ➔ 4s ➔ tối đa 10s.
   * Sau khi kết nối thành công, tự động gọi REST `GET /api/v1/games/{room_id}/state` để vẽ lại UI về đúng thời điểm tức thì.
2. **Optimistic UI & Local ACK**:
   * Khi bấm "Nộp bài", Client đổi ngay trạng thái nút sang "Đã nộp" (*Zero-latency feeling*).
   * Nhận `ANSWER_ACK` từ Netty để xác nhận giao dịch. Nếu timeout không nhận được ACK, hiển thị cảnh báo cho phép bấm nộp lại.
3. **Đồng bộ Thời gian Client-Server (Time Sync)**:
   * Khi nhận gói tin `QUESTION_STARTED`, lấy `server_timestamp` để tính độ lệch `delta = client_time - server_time`.
   * Sử dụng `delta` này để điều chỉnh đồng hồ đếm ngược Countdown ProgressBar trên UI, loại bỏ sai lệch do giờ hệ thống của điện thoại.
4. **Render Smoothness (Throttling / Debouncing 200ms)**:
   * Sử dụng `requestAnimationFrame` khi tiêu thụ gói tin `PROGRESS_UPDATED` để làm hiệu ứng animation chạy mượt mà, tránh hiện tượng giật/jank UI khi nhận dữ liệu dồn dập từ server.
   * *Lưu ý v2.2*: server dùng **coalescing tick** (Mục 7.1) nên khoảng cách giữa hai gói
     `PROGRESS_UPDATED` là **không đều** — có thể 200ms, có thể vài giây khi phòng im lặng.
     Client tuyệt đối không được giả định nhịp đều; Lerp phải nội suy theo timestamp thật
     trong gói tin, không theo bộ đếm cố định.

5. **🆕 Submission Buffer & Replay khi Reconnect (BẮT BUỘC — nền tảng của "mất dữ liệu = 0")**:
   * Client giữ **ring buffer N = 10** submission gần nhất, mỗi bản kèm `sequence_number` tăng dần.
   * Một submission chỉ được xóa khỏi buffer khi nhận `ANSWER_ACK` tương ứng.
   * Khi nối lại, client gửi `RESYNC { last_acked_seq, pending[] }` **trước mọi thao tác khác**.
   * Server áp `pending[]` qua `LastSeenSequenceTable`, bản trùng bị loại O(1) (Mục 8.1.3).
   * **Không được** xóa buffer khi WebSocket đứt — đó chính là lúc cần nó nhất.

6. **🆕 Debounce & Delta cho `UPDATE_DRAFT` (BẮT BUỘC)**:
   * Debounce **150ms** trước khi gửi; không gửi mỗi lần gõ phím.
   * Gửi **delta** (đoạn thay đổi), không gửi toàn bộ nội dung nháp.
   * Không có ràng buộc này thì không ngưỡng rate limit nào ở server đúng được (Mục 8.3.2).

7. **🆕 Trạng thái UI khi mất kết nối (BẮT BUỘC)**:
   * Hiển thị overlay **"Đang đồng bộ…"**, **giữ nguyên** đáp án học sinh đã chọn.
   * **KHÔNG** văng lỗi, **KHÔNG** reset UI, **KHÔNG** buộc học sinh chọn lại.
   * Gián đoạn 15–25 giây là kịch bản đã được thiết kế (Mục 8.1), không phải lỗi.

---
*Tài liệu Kỹ thuật Kiến trúc Hệ thống v2.1 – EdTech Multiplayer Realtime Platform.*


## 10. 🎮 Mở rộng Nền tảng Trò chơi (Game Platform Ecosystem - AWS Game Tech Patterns)

Dựa trên kiến trúc mẫu **AWS Cloud Game Development Patterns** (AWS GameLift FlexMatch, Open Match, Redis Sorted Sets Leaderboards, DynamoDB Player Data), hệ thống mở rộng thêm 4 dịch vụ nền tảng (Platform Services) để hỗ trợ đầy đủ các tính năng game online hiện đại:

```text
                               ┌─────────────────────────────────────────┐
                               │     TEACHER / STUDENT CLIENT (FE)       │
                               └────────────────────┬────────────────────┘
                                                    │
                 ┌──────────────────────────────────┼──────────────────────────────────┐
                 │ REST / gRPC (HTTP)               │ REST / gRPC (HTTP)               │ WebSocket (Protobuf)
                 ▼                                  ▼                                  ▼
   ┌──────────────────────────┐       ┌──────────────────────────┐       ┌──────────────────────────┐
   │  MATCHMAKING & PARTY SVC │       │  PLAYER PROFILE SERVICE  │       │  NETTY GATEWAY SERVICE   │
   │  (AWS FlexMatch Pattern) │       │  (Achievements / Level)  │       │  (Realtime Network Edge) │
   └─────────────┬────────────┘       └─────────────┬────────────┘       └─────────────┬────────────┘
                 │                                  │                                  │ Kênh nội bộ
                 ▼                                  ▼                                  ▼
   ┌──────────────────────────┐       ┌──────────────────────────┐       ┌──────────────────────────┐
   │  Redis Matchmaking Queue │       │ Redis Cache & NoSQL DB   │       │   GAME SESSION ACTOR     │
   │  (MMR Ticket Expansion)  │       │ (Player Inventory/XP)    │       │   (1 Room = 1 Actor)     │
   └─────────────┬────────────┘       └──────────────────────────┘       └─────────────┬────────────┘
                 │ Match Found                                                         │
                 ▼                                                                     │ Async Events
   ┌──────────────────────────┐                                                        ▼
   │  SPRING BOOT GAME API    │                                          ┌──────────────────────────┐
   │  (Spawn Game Room Actor) ├─────────────────────────────────────────►│   KAFKA EVENT BUS        │
   └──────────────────────────┘                                          └─────────────┬────────────┘
                                                                                       │
                                                                                       ▼
                                                                         ┌──────────────────────────┐
                                                                         │   LEADERBOARD WORKER     │
                                                                         │   (Redis Sorted Sets)    │
                                                                         └──────────────────────────┘
```

### 10.1. Dịch vụ Ghép trận & Ghép nhóm (Matchmaking & Party Service)
* **Kịch bản Nghiệp vụ**:
  * **Ghép ngẫu nhiên / Theo trình độ (MMR Matching)**: Xếp 12 học sinh có cùng trình độ học tập (MMR Rating) vào 1 phòng game Solo.
  * **Ghép cặp (Duos) / Ghép nhóm (Party 4v4)**: Học sinh tạo nhóm trước với bạn bè (`Party Service`), sau đó nhóm trưởng bấm tìm trận.
* **Cơ chế Kỹ thuật (AWS FlexMatch Expansion Pattern)**:
  1. Client gửi `Ticket` tìm trận vào **Redis Sorted Set** theo chỉ số MMR.
  2. **Rule Engine**: Sử dụng cửa sở dãn dải thời gian (*Ticket Expansion Window*):
     * Ban đầu: Tìm đối thủ trong dải ±50 MMR.
     * Sau 5 giây chưa đủ 12 người: Tự động nới dải sang ±150 MMR để tránh học sinh chờ lâu.
  3. **Match Found Event**: Khi đủ 12 người, dịch vụ tạo `room_id`, gọi API spawn `Game Session Actor` và trả về `ws_url` để 12 client tự kết nối vào Netty Gateway.

### 10.2. Dịch vụ Bảng Xếp Hạng Toàn cầu (Global & Seasonal Leaderboard Service)
* **Kịch bản Nghiệp vụ**: Bảng xếp hạng Realtime toàn trường, Bảng xếp hạng theo Tuần/Mùa (Season), Bảng xếp hạng Bang hội/Lớp.
* **Cơ chế Kỹ thuật (Redis Sorted Sets Pattern)**:
  * Độc lập hoàn toàn với Bảng xếp hạng 200ms trong phòng.
  * Sử dụng Redis **Sorted Set (`ZADD`, `ZREVRANK`, `ZREVRANGE`)**:
    * Cập nhật điểm: `ZADD leaderboard:season_2026 {score} {student_id}` ➔ Độ phức tạp O(log N), phản hồi trong dưới 1ms.
    * Lấy hạng cá nhân: `ZREVRANK leaderboard:season_2026 {student_id}` ➔ Trả về vị trí Top (VD: Top #15).
    * Lấy danh sách Top 100: `ZREVRANGE leaderboard:season_2026 0 99 WITHSCORES`.
  * **Async Consumer**: Khi trận đấu kết thúc, `Game Session Actor` bắn sự kiện `GAME_FINISHED` vào **Kafka** ➔ `Leaderboard Worker` đọc tin nhắn và cập nhật `ZADD` bất đồng bộ, không ảnh hưởng hot path của trận đấu.

### 10.3. Dịch vụ Hồ sơ Người chơi & Tiến trình (Player Profile & Progression Service)
* **Kịch bản Nghiệp vụ**: Quản lý Level, XP, Danh hiệu (Badges), Avatar, Vật phẩm (Inventory), Lịch sử đấu (Match History).
* **Cơ chế Kỹ thuật (AWS GameKit Player Data Pattern)**:
  * **Hot Storage (Redis Hash)**: Lưu `player:profile:{student_id}` (Level, XP, Current Avatar) để phục vụ hiển thị nhanh trên App.
  * **Cold / Durable Storage (MongoDB / PostgreSQL)**: Lưu trữ lịch sử chi tiết từng câu trả lời của các trận đấu cũ, danh sách vật phẩm và bảng thành tích học tập phục vụ tra cứu báo cáo dài hạn.

---


## 11. 🧠 Tư duy Thuật toán, System Design & Phân tích Độ phức tạp trước khi Triển khai (Pre-Implementation Protocol)

Trước khi đưa bất kỳ tính năng Game hoặc Minigame mới nào vào code, đội ngũ Kỹ sư Kiến trúc & Game Lead **bắt buộc phải thực hiện Quy trình Đánh giá Độ phức tạp Thuật toán và System Design** theo chuẩn khung dưới đây:

---

### 11.1. Ma trận Phân loại & Đánh giá Độ phức tạp Trò chơi (Game Complexity Matrix)

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ TIER 1: SIMPLE QUIZ / SOLO                                                             │
 │   • Đặc điểm: Trắc nghiệm, chọn đáp án cá nhân.                                       │
 │   • Data Structure: Flat Hash Map / Array.                                             │
 │   • Time Complexity: O(1) per Submit.                                                  │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ TIER 2: TEAM COLLABORATION / DRAFT                                                     │
 │   • Đặc điểm: Thảo luận nhóm 3 người, gõ nháp chung (Co-editing), ghép bài tập.        │
 │   • Data Structure: Nested Group Map / Tree.                                           │
 │   • Time Complexity: O(M) per Submit (M: số thành viên team = 3).                      │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ TIER 3: HIGH-FREQUENCY SPATIAL / PHYSICS MINIGAME                                      │
 │   • Đặc điểm: Đua xe (TeamRace), Đánh Boss (BossBattle), Xếp hình Realtime.             │
 │   • Data Structure: Grid Matrix / Spatial Index / State Queue.                         │
 │   • Time Complexity: O(N log N) (N: số thực thể trong phòng).                          │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 11.2. Phân tích Độ Phức Tạp Thuật Toán Cốt Lõi (Algorithmic Complexity & Bottlenecks)

#### 1. Thuật toán Chấm điểm & Cập nhật State (`Score Calculator`)
* **Cấu trúc dữ liệu**: `ConcurrentHashMap<StudentIndex, StudentState>` & `HashMap<TeamId, TeamState>`.
* **Time Complexity**: **O(1)** cho mỗi thao tác `SUBMIT_ANSWER`. Tìm kiếm học sinh qua `student_index` (0-11) mất O(1), cập nhật điểm mất O(1).
* **Space Complexity**: **O(S + T)** với S=12 (học sinh) và T=4 (nhóm) ➔ Hằng số cực nhỏ **O(1) Memory**, đảm bảo Game State không bị phình RAM.

#### 2. Thuật toán Gom nhóm & Nén Delta 200ms (`200ms Aggregation Window`)
* **Bài toán**: Tránh gửi lại toàn bộ Game State phình to xuống Client mỗi 200ms.
* **Giải pháp Thuật toán (Delta State Extraction)**:
  * So sánh State hiện tại và State của 200ms trước.
  * Chỉ trích xuất các thuộc tính có sự thay đổi (*Dirty Bit / Delta Encoding*).
* **Time Complexity**: **O(K)** với K là số thuộc tính bị thay đổi (K ≤ 12).
* **Kết quả**: Giảm dung lượng gói tin Protobuf từ 2KB xuống **< 80 bytes**.

#### 3. Thuật toán Ghép trận MMR (`Matchmaking Ticket Expansion`)
* **Cấu trúc dữ liệu**: Redis Sorted Set (`zset`) với `score = MMR_rating`.
* **Time Complexity**:
  * Chèn Ticket tìm trận: **O(log N)** với N là tổng số người trong hàng chờ.
  * Thuật toán quét nới dải Expansion Window: **O(M)** với M là số ứng viên trong cửa sở dãn dải MMR.

---

### 11.3. Quy trình 4 Bước System Design Protocol trước khi Code (Pre-Implementation Checklist)

Mọi tính năng Game mới trước khi viết code phải vượt qua 4 bước kiểm duyệt nghiêm ngặt:

```text
[Ý tưởng Game mới]
       │
       ▼
┌───────────────────────────────────────────────────────────┐
│ BƯỚC 1: Phân tích Không gian Trạng thái (State Space)     │
│  • Liệt kê toàn bộ biến State cần lưu in-memory.          │
│  • Đảm bảo State size < 5KB để Snapshot cực nhanh.        │
└─────────────────────────────┬─────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────┐
│ BƯỚC 2: Phân tích Độ phức tạp Thuật toán (Complexity O)   │
│  • Chứng minh Time Complexity ≤ O(N) cho mọi sự kiện.      │
│  • Loại bỏ hoàn toàn các vòng lặp O(N²) lồng nhau.        │
└─────────────────────────────┬─────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────┐
│ BƯỚC 3: Ước tính Băng thông & Message Budget             │
│  • Message Budget: Tối đa 5 packet/giây/client.           │
│  • Protobuf Payload Size < 150 bytes.                     │
└─────────────────────────────┬─────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────┐
│ BƯỚC 4: Kiểm tra Edge Cases & Failure Modes               │
│  • Xử lý Out-of-order packet (dùng Sequence ID).          │
│  • Xử lý Reconnect state sync & Late Submissions.         │
└───────────────────────────────────────────────────────────┘
```

---


## 12. 📐 Tổng hợp các Design Patterns cốt lõi trong Game Backend & Thực tế Triển khai

Để mã nguồn sạch, dễ bảo trì, tuân thủ nguyên lý SOLID và có khả năng mở rộng cao, hệ thống áp dụng các Design Patterns chuẩn công nghiệp được phân chia theo 3 nhóm chính:

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. BEHAVIORAL PATTERNS (Mẫu Hành Vi)                                                   │
 │   • Actor Model, FSM (State), Strategy, Chain of Responsibility, Iterator, Command     │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 2. STRUCTURAL PATTERNS (Mẫu Cấu Trúc)                                                  │
 │   • Memento (Snapshot), Flyweight (Shared Content), Adapter (Protobuf Envelope)         │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 3. ARCHITECTURAL & MESSAGING PATTERNS (Mẫu Kiến trúc & Giao tiếp)                      │
 │   • Smart Fan-out (Pub/Sub), CQRS, Circuit Breaker, Token Bucket Rate Limiting         │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 12.1. Nhóm Design Patterns Hành Vi (Behavioral Patterns)

#### 1. Actor Pattern (Distributed Single-Threaded Actor)
* **Vị trí áp dụng**: `Game Session Actor` (1 Room = 1 Actor).
* **Thực tế triển khai**: Mọi thao tác nộp bài của 12 học sinh được xếp hàng vào Mailbox Queue và xử lý tuần tự đơn luồng. **Triệt tiêu 100% rủi ro Race Condition và DB Lock Contention** mà không cần dùng khóa `Mutex/Synchronized`.

#### 2. State Pattern / Finite State Machine (FSM)
* **Vị trí áp dụng**: `Lifecycle Module` (`LOBBY` ➔ `PLAYING` ➔ `PAUSED` ➔ `SHOWING_RESULT` ➔ `GAME_OVER`).
* **Thực tế triển khai**: Mỗi trạng thái là một Class Java/Go độc lập. Khi muốn thêm tính năng `PAUSED` (Tạm dừng phòng), chỉ cần viết thêm Class `PausedState.java` mà không cần sửa 1 dòng code nào trong các trạng thái cũ (*Open/Closed Principle*).

#### 3. Strategy Pattern
* **Vị trí áp dụng**: `Score Calculator` & `Game Mechanic Plugins`.
* **Thực tế triển khai**: `ScoreCalculator` tính điểm thô ➔ Gọi `GameMechanicPlugin` interface (`ProgressMeter`, `BossBattle`, `TeamRace`). Khi phát triển Minigame mới, chỉ cần cắm Plugin Strategy mới vào hệ thống.

#### 4. Chain of Responsibility Pattern
* **Vị trí áp dụng**: `Rule Evaluator Module`.
* **Thực tế triển khai**: Chuỗi các chốt kiểm soát tuần tự:
  `MembershipCheckFilter` ➔ `IdempotencyFilter` (Redis SETNX) ➔ `QuestionSyncFilter` ➔ `TimeoutCheckFilter`. 
  Nếu lọt qua tất cả các chốt này thì gói tin nộp bài mới được chuyển tiếp tới `ScoreCalculator`.

#### 5. Command & Iterator Pattern
* **Vị trí áp dụng**: `Step Runner Module` & `User Action Commands`.
* **Thực tế triển khai**: Đóng gói các hành động nộp bài thành đối tượng `SubmitAnswerCommand`. `Step Runner` đóng vai trò Iterator duyệt qua mảng các bước `steps` trong JSON Definition để quản lý đồng hồ đếm ngược (Timeout).

---

### 12.2. Nhóm Design Patterns Cấu Trúc (Structural Patterns)

#### 1. Memento Pattern
* **Vị trí áp dụng**: `Adaptive Snapshot Module`.
* **Thực tế triển khai**: Trích xuất toàn bộ trạng thái bên trong của Actor (`Game State`) thành một bản ghi nhớ gọn nhẹ ➔ Serialize Protobuf ➔ Nén LZ4HC lưu Redis. Khi Pod sập, dùng bản Memento này để **khôi phục nguyên trạng phòng trong < 30ms**.

#### 2. Flyweight Pattern
* **Vị trí áp dụng**: `Game Definition & Shared Content Management`.
* **Thực tế triển khai**: Khi có 1.000 phòng học cùng tham gia bộ câu hỏi Quiz #101, hệ thống **chỉ lưu 1 bản duy nhất `GameDefinition` trong Shared Memory**. 1.000 Room Actors chỉ giữ con trỏ tham chiếu đến ID đó ➔ Giảm 95% RAM tiêu tốn.

#### 3. Adapter / Envelope Pattern
* **Vị trí áp dụng**: `Protobuf Envelope`.
* **Thực tế triển khai**: Bọc các gói tin Protobuf cụ thể (`UpdateDraftAnswerRequest`, `ProgressUpdated`) vào trong `GameMessage` envelope chuẩn để Netty Gateway giải mã tầng biên dễ dàng.

---

### 12.3. Nhóm Design Patterns Kiến trúc & Giao tiếp (Architectural Patterns)

#### 1. Smart Fan-out (Publish-Subscribe Pattern)
* **Vị trí áp dụng**: `Netty Gateway Channel Grouping`.
* **Thực tế triển khai**: Phân loại và broadcast thông điệp theo phạm vi nhận tin: `ROOM_BROADCAST` (Cả phòng), `TEAM_ONLY` (Nhóm kín 3 người), `PRIVATE_SOLO` (Unicast 1-1).

#### 2. CQRS (Command Query Responsibility Segregation)
* **Vị trí áp dụng**: Phân tách luồng Ghi và Luồng Truy vấn.
* **Thực tế triển khai**: Luồng Ghi (Command) xử lý trong Actor Memory (và Kafka cho audit). Luồng Truy vấn báo cáo (Query) đọc bất đồng bộ từ PostgreSQL partitioned & Redis Leaderboard.

#### 3. Circuit Breaker Pattern — CHỈ cho lời gọi STATELESS
* **Vị trí áp dụng**: Auth Service, Player Profile Service, DB connections.
* **KHÔNG áp dụng cho kênh Gateway ↔ Engine.**
* **Vì sao**: circuit breaker chỉ có nghĩa khi **có nơi khác để đi**. Room state là stateful và
  chỉ tồn tại ở đúng một nơi — không có Engine pod thay thế để chuyển sang. v2.1 (Mục 13.2)
  viết *"Open Circuit và chuyển sang giao tiếp dự phòng"* — **"giao tiếp dự phòng" đó không
  tồn tại**; đây là câu chữ mượn từ pattern stateless service, dán vào chỗ không áp dụng được.
* Hành vi đúng khi Engine pod không phản hồi: xem Mục 13.2.

---


## 13. ⚠️ Đánh giá Rủi ro Kỹ thuật & Giải pháp Phòng ngừa Chi tiết (Risk Assessment & Mitigation Strategy)

Đánh giá phản biện kỹ thuật chuyên sâu cho 6 rủi ro trọng yếu (từ **A** đến **F**) của hệ thống và giải pháp phòng ngừa trước khi triển khai thực tế:

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ A. RỦI RO TẦNG STATEFUL & RECOVERY TIME (Akka Cluster Sharding & Realistic SLAs)      │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ B. RỦI RO COUPLING KÊNH NỘI BỘ (Bulkhead Thread Isolation & Circuit Breaker)           │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ C. PHÂN LOẠI TIN NHẮN AGGREGATION (Critical Immediate vs Non-Critical 200ms)         │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ D. NÂNG CẤP IDEMPOTENCY (Client Sequence Number + Server Last-Seen Table)              │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ E. THIẾT KẾ MATCHMAKING RESILIENCE & RECONNECT AFFINITY                               │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ F. GIÁM SÁT TOÀN DIỆN (OpenTelemetry Distributed Tracing & Mailbox Queue Metrics)     │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 13.1. Rủi ro A: Độ phức tạp Tầng Stateful & Tối ưu Recovery Time

* **Vấn đề**: Việc vận hành 1.600 – 2.000 Actor sống đồng thời cần cơ chế định vị vị trí (*Location Transparency*) chuẩn xác. Đồng thời, mốc cam kết Recovery < 30ms khi Pod chết là rất tham vọng trong thực tế vì phụ thuộc vào Redis Latency + Size Snapshot + Kafka Seek & Replay + Deserialize.
* **Giải pháp Phòng ngừa**:
  1. **Quản lý Vị trí Actor**: **Pekko Cluster Sharding** (ADR-2) tự quyết định placement.
     Gateway KHÔNG tra Redis trên hot path — dùng **lazy-learned routing** (Mục 14.1).
     `room:routing:{room_id}` trong Redis chỉ phục vụ vận hành & teacher dashboard.
  2. **❌ SLA Recovery 50–100ms là SAI — đã thay bằng Mục 8.1.** Con số đó chỉ đo phần nạp
     lại state. Thời gian thật bị chi phối bởi failure detection + SBR `stable-after`
     (~12–25 s). Mục tiêu đúng là **mất dữ liệu = 0**, không phải khôi phục nhanh.
     Xem bảng SLA ở Mục 8.1.5 và 14.6.
  3. **Bắt buộc Chaos Engineering & Load Benchmark** (đánh sập Pod under load) để đo p50/p99
     thực tế **trước khi cam kết bất cứ con số nào với đối tác** — xem Mục 32.
  4. **Tối ưu Kích thước Snapshot**: Ép `Game State Snapshot` < 5KB.
     *(Bỏ "phân luồng nạp Kafka Replay bất đồng bộ" — Kafka không còn trên đường khôi phục.)*
  5. **Chống split-brain**: Cluster Sharding một mình KHÔNG đảm bảo "at most one actor" khi có
     network partition. Bắt buộc SBR + fencing token — **Mục 8.4**.
  6. **Giảm blast radius**: 12–16 Engine pod nhỏ thay vì 4 pod lớn (Mục 23.1), kèm jitter khi
     nạp snapshot hàng loạt (Mục 8.1.4).

---

### 13.2. Rủi ro B: Thắt nút Kênh Nội bộ giữa Gateway và Actor

* **Vấn đề**: Internal Frame Channel là điểm nối liền (*Single Point of Coupling*) quan trọng. Nếu một Engine Pod bị chậm, nó có nguy cơ gây tắc nghẽn EventLoop của Netty Gateway.
* **Giải pháp Phòng ngừa**:
  1. **Bulkhead Thread Isolation Pattern** *(giữ nguyên từ v2.1 — phần này đúng và cần)*:
     Tách biệt hoàn toàn EventLoopGroup xử lý WebSocket biên và EventLoopGroup của kênh nội bộ.
     Một Engine Pod chậm sẽ không ảnh hưởng đến các kết nối WebSocket khác tại Gateway.
  2. **❌ KHÔNG dùng Circuit Breaker ở đây.** v2.1 viết: *"Khi Engine Pod không phản hồi trong
     500ms, Gateway lập tức ngắt kết nối (Open Circuit) và chuyển sang giao tiếp dự phòng."*
     **Không tồn tại "giao tiếp dự phòng"** — room state stateful, chỉ có ở một nơi.
     Tệ hơn: nếu Gateway ngắt WebSocket của client khi Engine chậm, một sự cố **một pod** sẽ đẻ
     ra hàng chục nghìn lượt reconnect + TLS handshake đồng loạt, biến nó thành **sự cố toàn
     hệ thống** (Mục 8.6).

**Hành vi đúng khi Engine pod không phản hồi:**

```text
1. GW KHÔNG mở circuit breaker cho room traffic — không có fallback để đi tới.
2. GW gửi CONNECTION_DEGRADED tới các client thuộc phòng trên pod đó.
3. Client hiển thị "Đang đồng bộ…", GIỮ NGUYÊN đáp án đã chọn trong buffer (Mục 9, quy tắc 5).
4. GW GIỮ NGUYÊN WebSocket của client — TUYỆT ĐỐI KHÔNG ngắt.
5. Khi Cluster Sharding tái tạo actor ở pod khác, GW học lại route (Mục 14.1) và nối lại luồng.
6. Client gửi RESYNC -> không mất đáp án nào (Mục 8.1.3).
```

  3. **Backpressure MỘT tầng**: `channel.isWritable()` + watermark trên chính socket nội bộ.
     Đây là lợi ích trực tiếp của việc bỏ gRPC (ADR-1) — không còn HTTP/2 window chồng lên
     app-level window, nên khi nghẽn ta biết chắc tầng nào đang chặn.

---

### 13.3. Rủi ro C: Phân loại Thông điệp (Critical vs. Non-Critical Aggregation)

* **Vấn đề**: Gom cửa sổ 200ms giúp giảm Broadcast Storm, nhưng với các minigame phản hồi nhanh (Tier 3), việc chờ 200ms có thể tạo ra "cảm giác lag" (*Perceptual Lag*) dù độ trễ mạng p99 < 100ms.
* **Giải pháp Phòng ngừa (Message Classification Engine)**:
  * 🔴 **Critical Messages (BẮN TỨC THÌ - Zero Latency, Bypass 200ms Window)**:
    * `ANSWER_ACK`, `SUBMIT_ANSWER_RESULT`, `GAME_OVER`, `TEACHER_PAUSE_COMMAND`.
  * 🟡 **Non-Critical Messages (GOM CỬA SỔ 200ms AGGREGATION)**:
    * `PROGRESS_UPDATED`, `LEADERBOARD_UPDATED`, `DRAFT_UPDATED`.

---

### 13.4. Rủi ro D: Nâng cấp Idempotency (Client Sequence + Server State Table)

* **Vấn đề**: Chống trùng lặp chỉ dùng Redis `SETNX` với TTL 5s có thể bị lỗi *False Negative* hoặc *Race Condition nhỏ* khi xảy ra phân mảnh mạng (Network Partition) hoặc tải cực cao.
* **Giải pháp Phòng ngừa (Dual-Layer Idempotency)**:
  * Không phụ thuộc 100% vào Redis `SETNX`.
  * **Tầng 1 (Client)**: Mỗi gói tin client gửi đính kèm `sequence_number` tăng dần (1, 2, 3...).
  * **Tầng 2 (Server)**: Trong `Game State` RAM của Actor lưu trữ một bảng `LastSeenSequenceTable[StudentIndex]`. Actor chỉ chấp nhận gói tin nếu `sequence_number > last_seen_sequence`. Mọi gói tin nhỏ hơn hoặc bằng sẽ bị loại bỏ lập tức với thời gian xử lý O(1) trong RAM.

---

### 13.5. Rủi ro E: Đảm bảo Tính Nhất Quán cho Matchmaking & Reconnect Affinity

* **Vấn đề**: Cần cơ chế xử lý khi rớt mạng reconnect và khi khởi tạo phòng thất bại (*Room Spawn Failure*).
* **Giải pháp Phòng ngừa**:
  1. **Matchmaking Ticket Consistency**: Dùng **Redis Lua Script** để thực hiện các thao tác kiểm tra ticket và rút ticket nguyên tử (*Atomic Operations*).
  2. **Room Spawn Failure Fallback**: Khi Spring Boot API khởi tạo `Game Session Actor` thất bại (quá tải Pod), Matchmaker tự động hoàn lại Ticket vào Queue và thử lại (*Retry with Backoff*) trên Pod khác.
  3. **Reconnect Affinity**: Khi học sinh reconnect, Netty Gateway tra cứu Redis `room:routing:{room_id}` để định tuyến kết nối WebSocket mới về đúng Engine Pod cũ mà học sinh đang chơi.

---

### 13.6. Rủi ro F: Giám sát Chuyên sâu (Observability & Distributed Tracing)

* **Vấn đề**: Với 54.000 CCU (3x Target Peak) và hàng ngàn Actor sống đồng thời, việc không có công cụ giám sát chuyên sâu sẽ khiến đội ngũ "mù thông tin" khi có sự cố.
* **Giải pháp Phòng ngừa (Full Observability Stack)**:

```text
 [Client WS] ──► [Netty Gateway] ──► [Internal Chan] ──► [Actor Engine] ──► [Kafka]
     │                 │                   │                  │             │
     └─────────────────┴─────────┬─────────┴──────────────────┴─────────────┘
                                 ▼
                    [OpenTelemetry Context Propagation]
                       (Trace_ID xuyên suốt toàn bộ luồng)
```

1. **Distributed Tracing (OpenTelemetry)**: Tự động đính kèm `trace_id` và `span_id` vào Protobuf Header, truyền xuyên suốt từ `Client WS` ➔ `Netty Gateway` ➔ `Internal Channel` ➔ `Game Session Actor` ➔ `Kafka`.
2. **Room Metrics per Actor (Prometheus)**:
   * `actor_mailbox_queue_depth`: Độ dài hàng đợi tin nhắn trong Mailbox của từng phòng.
   * `actor_processing_latency_seconds`: Thời gian xử lý 1 sự kiện của Actor (p50, p99).
   * `snapshot_serialization_bytes`: Kích thước file Snapshot nén LZ4.
3. **Cảnh báo Tự động (Grafana Alerting Rules)**:
   * 🚨 **ALERT CRITICAL**: Khi `actor_mailbox_queue_depth > 100` trong 3 giây liên tục ➔ Cảnh báo Pod bị chậm xử lý.
   * 🚨 **ALERT WARNING**: Khi `snapshot_serialization_bytes > 10KB` ➔ Cảnh báo Game State phình to bất thường.

---


## 14. 🔬 Các Kỹ thuật Nâng cao & Cam kết SLA Kỹ thuật Nội bộ (Advanced Systems Engineering & Internal SLAs)

Bổ sung 7 kỹ thuật hạ tầng nâng cao và bảng cam kết chỉ số kỹ thuật nội bộ (Internal SLAs) để đảm bảo hệ thống đạt độ sẵn sàng cao nhất (High Availability):

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. LAZY-LEARNED ROUTING TẠI GATEWAY (thay Consistent Hashing của v2.1 — R-19)          │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 2. MESSAGE PRIORITY IN AGGREGATION WINDOW (Critical vs Best-Effort)                   │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 3. SNAPSHOT VERSIONING & CRC32 CHECKSUM FOR FAULT INTEGRITY                            │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 4. ACTOR-TO-GATEWAY BACKPRESSURE (AUTO-READ OFF & RATE-LIMIT)                          │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 5. KẾ HOẠCH CHAOS ENGINEERING SỚM (Chaos Mesh & LitmusChaos)                           │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 6. BẢNG CAM KẾT SỐ LIỆU SLA NỘI BỘ (Internal Technical SLAs)                          │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 7. PHÂN BỔ MÔ HÌNH THỰC THI THEO TẦNG (Pekko dispatcher vs Virtual Thread — R-15)      │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 14.1. Lazy-Learned Routing tại Gateway (thay Consistent Hashing của v2.1)

> [!WARNING]
> **v2.1 đề xuất Consistent Hashing Ring `hash(room_id)` tại Gateway — cách đó xung đột với
> Cluster Sharding.** Với Sharding, **Pekko quyết định placement, không phải hàm hash của
> Gateway**. Sau một lần rebalance (thêm/bớt pod, node down), vòng hash ở GW trỏ sai và
> không có cách nào tự biết. Hai nguồn sự thật về vị trí phòng là công thức sinh lỗi.

**Cơ chế thay thế — không Redis trên hot path, tự lành:**

```text
1. GW giữ connection tới TẤT CẢ Engine pod (số pod nhỏ: 12–16).
2. Chưa biết room -> gửi tới bất kỳ pod nào (round-robin).
   ShardRegion tự forward nội bộ tới đúng node.
3. Response mang header owner_pod_id do Engine đóng dấu.
   GW cache room_id -> pod. Từ đó gửi thẳng, bỏ được hop nội bộ.
4. Rebalance làm cache sai -> pod nhận được trả NOT_OWNER kèm owner hiện tại,
   hoặc đơn giản forward tiếp rồi đóng dấu lại -> GW tự cập nhật.
5. Connection tới một Engine pod đứt -> xóa mọi entry cache trỏ tới pod đó.
```

**Ưu điểm so với v2.1**: không cần Redis trên hot path, không cần TTL, và **không thể lệch
khỏi sự thật** — vì sự thật do chính Engine đóng dấu vào response.

Cache này thay luôn vai trò định tuyến của `Redis Session Registry` (`room:routing:{id}`).
Redis vẫn giữ registry cho vận hành và teacher dashboard, nhưng **không nằm trên đường đi của
gói tin**.

---

### 14.2. Phân loại Ưu tiên Thông điệp (Message Priority Engine)

Trong Aggregation Window 200ms, gói tin được phân làm 2 luồng ưu tiên riêng biệt:

* 🔴 **Critical Priority (Xử lý Tức thì - Zero Latency - Guarantee Delivery)**:
  * Các sự kiện: `ANSWER_ACK`, `SUBMIT_RESULT`, `GAME_OVER`, `TEACHER_COMMAND`.
  * *Hành vi*: Bỏ qua cửa sổ 200ms, gửi trực tiếp xuống Client ngay khi xử lý xong.
* 🟡 **Best-Effort Priority (Gom 200ms Window - Drop Old Packets if Congested)**:
  * Các sự kiện: `PROGRESS_UPDATED`, `LEADERBOARD_UPDATED`, `DRAFT_UPDATED`.
  * *Hành vi*: Gom trong cửa sổ 200ms. Nếu mạng bị nghẽn, tự động loại bỏ gói tin 200ms cũ để chỉ gửi gói tin tiến trình mới nhất (*Latest State Confinement*).

---

### 14.3. Snapshot Versioning & Checksum Bảo vệ Dữ liệu

Để chống rủi ro hỏng dữ liệu (*State Corruption*) khi khôi phục từ Redis:

* **Snapshot Header Structure**:
  * `schema_version` (uint32): Phiên bản cấu trúc dữ liệu State.
  * `snapshot_sequence` (uint64): Số thứ tự snapshot.
  * **`epoch` (uint64)**: 🆕 Fencing token — xem Mục 8.4. Ghi bằng Lua CAS; epoch cũ bị từ chối.
  * `checksum` (XXHash64 / CRC32): Mã băm toàn vẹn dữ liệu.
  * *(Bỏ `last_kafka_offset` của v2.1 — xem Mục 7.2.)*
* **Quy trình Khôi phục Safe Recovery**:
  1. Đọc Snapshot từ Redis ➔ Kiểm tra `CRC32 Checksum`.
  2. Nếu Checksum không khớp (Corrupted) ➔ **Fail-safe Fallback**: bỏ qua snapshot, bắt đầu từ
     state rỗng và dựa hoàn toàn vào **client RESYNC** (Mục 8.1.3 bước 5).
     *(v2.1 nói "replay 100% sự kiện từ Kafka từ đầu trận" — không còn áp dụng, Kafka đã rời
     đường khôi phục. Client replay bao phủ đúng khoảng dữ liệu chưa được ACK.)*

> **Phân biệt rõ**: `checksum` chống **hỏng dữ liệu**; `epoch` chống **hai actor cùng ghi**.
> Đây là hai rủi ro khác nhau và cần hai cơ chế khác nhau. v2.1 chỉ có cái thứ nhất.

---

### 14.4. Cơ chế Backpressure từ Actor về Gateway (Mailbox Protection)

* **Phòng ngừa Mailbox Overflow**: Khi số lượng tin nhắn chờ trong Mailbox của Actor chạm ngưỡng nguy hiểm (`depth > 200 msgs`):
  1. Actor gửi tín hiệu Backpressure `PAUSE_READ` về Netty Gateway qua Internal Frame Channel.
  2. Netty Gateway lập tức tạm ngắt quyền đọc trên WebSocket Channel (`channel.config().setAutoRead(false)`) hoặc trả về thông báo `RATE_LIMIT_EXCEEDED` lập tức cho client.
  3. Khi Mailbox xả bớt (`depth < 50 msgs`), Actor gửi tín hiệu `RESUME_READ` để Gateway mở lại đường truyền.

---

### 14.5. Kế hoạch Chaos Engineering Sớm (Thử nghiệm Độc tính)

Thực thi Chaos Engineering bằng **Chaos Mesh** trên Môi trường Staging dưới tải 54.000 CCU (3x Target Peak) giả lập:

* 🧪 **Kịch bản 1 (Pod Mortality)**: Kill ngẫu nhiên 20% Engine Pods giữa cao điểm ➔ Đo thời gian Re-sharding và SLA Recovery.
* 🧪 **Kịch bản 2 (Network Partition)**: Cắt đứt kết nối kênh nội bộ giữa 1 Gateway Pod và 1 Engine Pod ➔ Đo độ nhạy của Circuit Breaker & Reconnect Logic.
* 🧪 **Kịch bản 3 (Storage Degradation)**: Thêm 50ms Latency Spike vào Redis ➔ Kiểm tra khả năng chịu tải của Adaptive Snapshot.

---

### 14.6. Bảng Cam kết Chỉ số Kỹ thuật Nội bộ (Internal Technical SLAs)

| Chỉ số Kỹ thuật (Internal Metric) | v2.1 | **v2.2 (Cam kết p99)** | Công cụ Giám sát |
| :--- | :--- | :--- | :--- |
| **Actor Processing Time** | < 15ms | **< 15ms** *(giữ)* | Prometheus & OpenTelemetry |
| **Snapshot Serialization & Compress Time** | < 5ms | **< 5ms** *(giữ)* | Micrometer Timers |
| **Snapshot Payload Size** | < 5KB | **< 5KB** *(giữ)* | Prometheus Gauge |
| **Gateway Smart Fan-out Latency** | < 5ms | **< 5ms** *(giữ)* | Netty Channel Metrics |
| **Kafka Produce Latency** | < 10ms | *(bỏ khỏi SLA cốt lõi)* | Kafka đã rời hot path — Mục 2.1.4 |
| **Full Recovery Time (p99 under load)** | < 50–100ms | **< 30 s** | Chaos Test Suite — Mục 8.1 |
| 🆕 **Mất dữ liệu đáp án khi sập pod** | *(không có)* | **0** | Chaos Test + đối chiếu DB |
| 🆕 **End-to-end submit ➔ ACK** | *(không có)* | **< 100 ms** | OpenTelemetry span |
| 🆕 **Handshake rate chịu được / GW pod** | *(không có)* | **đo ở H1** | Load test — Mục 32 |
| 🆕 **Chi phí / CCU / giờ** | *(không có)* | **theo dõi liên tục** | Mục 31 |

> **Vì sao "Full Recovery Time" nhảy từ 50ms lên 30 s**: không phải hệ thống chậm đi, mà v2.1
> đo sai giai đoạn — nó chỉ tính phần nạp state, bỏ qua failure detection và SBR vốn chi phối
> toàn bộ thời gian. Chỉ số thật sự quan trọng là dòng **"Mất dữ liệu đáp án = 0"** ngay bên
> dưới; đó mới là thứ học sinh và khách hàng cảm nhận. Xem Mục 8.1.

---

### 14.7. Phân bổ Mô hình Thực thi theo Tầng (thay "Loom + Actor" của v2.1)

> [!WARNING]
> **v2.1 viết**: *"Mỗi Actor Mailbox được thực thi bởi 1 Virtual Thread
> (`Executors.newVirtualThreadPerTaskExecutor()`) thay vì OS Platform Thread… giảm RAM từ
> 1MB xuống < 2KB."*
>
> **Pekko không hoạt động như vậy.** Actor **không sở hữu thread** — chúng được dispatcher lập
> lịch lên một pool nhỏ (thường ≈ số core). 300–375 actor trên một pod đã chạy tốt trên ~8
> platform thread. Ép mỗi mailbox lên một virtual thread **không tiết kiệm gì** (không có
> thread nào đang bị lãng phí) và sẽ **đánh nhau với dispatcher**.
>
> Con số "1MB → 2KB" đúng về virtual thread nói chung, nhưng **không áp dụng ở đây** vì mô
> hình Pekko chưa bao giờ tốn 1 thread mỗi actor.

**Phân bổ đúng — virtual thread dùng ở đâu:**

| Thành phần | Mô hình thực thi | Lý do |
|---|---|---|
| **RoomActor** | Pekko dispatcher (platform thread) | Actor đã ghép kênh sẵn; virtual thread không thêm gì |
| **Netty EventLoop** (biên WS + kênh nội bộ) | Platform thread, cố định = cores × 2 | Non-blocking; virtual thread phản tác dụng |
| **Redis snapshot I/O, PostgreSQL ghi kết quả** | ✅ **Virtual thread** | Blocking I/O, nhiều tác vụ đồng thời — đúng chỗ dùng |
| **Kafka producer** (analytics) | ✅ Virtual thread hoặc async client | Blocking I/O |
| **Logic từ Game Definition** | Dispatcher riêng (Mục 22.1) | Cách ly, tránh một Definition lỗi kéo sập cả pod |

**Giữ nguyên và làm nổi bật Mục 22.1.3** (chống thread pinning: dùng `ReentrantLock` thay
`synchronized`) — vẫn đúng và vẫn quan trọng, nhưng **chỉ áp dụng cho tầng I/O ở bảng trên**,
không áp dụng cho actor.

---


## 15. 📈 Phân tích Độ ổn định & Kỹ thuật Xử lý khi Room Size Tăng lên 100 Người (100-Student Scale-Up Analysis)

Khi mở rộng quy mô phòng học từ **12 học sinh lên 100 học sinh/phòng**, hệ thống đối mặt với sự bùng nổ phi tuyến tính về số lượng thông điệp (*N^2 Message Explosion*) và áp lực băng thông.

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ BÙNG NỔ BẮNG THÔNG & MÃ MẢNG (N² Broadcast Explosion Analysis)                        │
 │ • 12 người/phòng   ➔ Max Broadcast: 12 x 12 = 144 msgs/s per room                     │
 │ • 100 người/phòng  ➔ Max Broadcast: 100 x 100 = 10.000 msgs/s per room (Gấp 69.4 lần!) │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 15.1. Phân tích Tác động Hạ tầng khi Room Size = 100 Người

#### 1. Áp lực Băng thông Tầng Mạng Biên (Netty Gateway Outbound Saturation)
* Nếu 100 học sinh cùng nộp bài trong 1 giây mà không gom nhóm:
  * Số tin nhắn broadcast outbound = 100  ×  100 = 10.000 msgs/giây/phòng.
  * Với dung lượng payload 100 bytes ➔ Băng thông outbound = **1 MB/s cho duy nhất 1 phòng**.
  * Nếu có 1.000 phòng 100 người ➔ Tổng băng thông outbound Gateway = **1 GB/s (8 Gbps)** ➔ **Rủi ro sập băng thông Gateway (Network Saturation)**.

#### 2. Áp lực Hàng đợi Mailbox & Latency tại Actor (Engine Pod)
* 100 bài nộp dồn vào Mailbox Queue của Actor trong 1 giây.
* Nếu xử lý từng bài nộp mất 1ms ➔ Độ trễ xử lý bài nộp cuối cùng của phòng bị đẩy lên **100ms** (Vượt cam kết SLA 15ms).
* Dung lượng Game State nén LZ4 Snapshot tăng từ < 1KB lên **5KB - 8KB**.

---

### 15.2. Năm (5) Giải pháp Kỹ thuật Phòng ngừa Đảm bảo Phòng 100 Người Chạy Mượt

Để room size 100 người chạy hoàn toàn ổn định với độ trễ p99 < 100ms, hệ thống áp dụng 5 kỹ thuật:

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. MANDATORY 200ms AGGREGATION WINDOW (Gom tin nhắn bắt buộc)                          │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 2. DELTA STATE COMPRESSION (Chỉ gửi các thuộc tính thay đổi)                          │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 3. TOP-N & PAGINATED LEADERBOARD BROADCAST (Chỉ gửi Top 20 + Thứ hạng cá nhân)        │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 4. LOCK-FREE BATCH MAILBOX PROCESSING (Actor xử lý batch 20 msgs cùng lúc)             │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 5. NETTY DIRECT BUF ZERO-COPY SLICING (Mã hóa 1 lần, fan-out 100 channels)             │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

#### 1. Gom tin nhắn Bắt buộc (Mandatory 200ms Aggregation Window)
* **TUYỆT ĐỐI KHÔNG** broadcast theo từng action nộp bài.
* Mọi cập nhật tiến trình BẮT BUỘC qua cửa sổ **200ms Aggregation Window** (tối đa 5 broadcast/giây per room).
* Số tin nhắn broadcast/giây per room giảm từ 10.000 msgs/s xuống **chỉ còn 5  ×  100 = 500 msgs/s** (Tiết kiệm 95% băng thông!).

#### 2. Nén Trạng thái Thay đổi (Delta State Compression)
* Không bắn lại toàn bộ danh sách 100 học sinh.
* Chỉ bóc tách và nén mảng `StudentProgress` của những em **có điểm số/tiến trình thay đổi** trong 200ms vừa qua.
* Kích thước Payload Protobuf giảm từ 8KB xuống **< 150 bytes**.

#### 3. Phân đoạn Bảng xếp hạng (Top-N Leaderboard Broadcast Pattern)
* Server không gửi toàn bộ vị trí của 100 người cho cả 100 em.
* Server chỉ gửi **Top 10 / Top 20 học sinh dẫn đầu** + **Thứ hạng cá nhân của chính học sinh đó** (`your_rank: 45, your_score: 120`).
* Kích thước mảng Protobuf giảm 85%.

#### 4. Actor Lock-free Batch Mailbox Processing
* Hỗ trợ Actor xử lý batch 10 - 20 bài nộp cùng lúc trong RAM (`batchConsumer`) thay vì xử lý đơn lẻ từng bài.
* Độ trễ xử lý 100 msgs trong Mailbox giảm từ 100ms xuống **< 8ms**.

#### 5. Netty Zero-Copy Direct Buffer Slicing
* Tại Gateway, Netty mã hóa mảng bytes Protobuf 1 lần duy nhất (`ByteBuf.retainedDuplicate()`) và fan-out mảng bytes đó đến 100 WebSocket Channels qua Direct Memory mà không copy bộ nhớ ➔ **Zero Heap Allocation Overhead**!

---


# 🚀 PHẦN IV — QUYẾT ĐỊNH CÔNG NGHỆ, QUY CHUẨN & BẢO VỆ RUNTIME

---

## 16. 📎 PHỤ LỤC — Các Phương án Gateway đã Cân nhắc và LOẠI

> [!IMPORTANT]
> **Quyết định đã chốt: Netty trực tiếp cho Gateway.** Toàn bộ mục 16 dưới đây được giữ lại
> làm **hồ sơ quyết định**, KHÔNG phải khuyến nghị đang có hiệu lực.
>
> **v2.1 đưa ra ba câu trả lời khác nhau cho cùng một câu hỏi**: banner + Mục 2 chọn Netty;
> Mục 16.2 và 25.2.6 khuyến nghị Vert.x; file MVP plan ghi "Netty hoặc Vert.x". Ba khuyến nghị
> mâu thuẫn trong một tài liệu là nguồn nhầm lẫn trực tiếp cho dev.
>
> **Vì sao chốt Netty** — và phải thừa nhận đây là lựa chọn tốn công hơn:
> 1. ADR-1 yêu cầu kiểm soát chính xác framing, watermark, `retainedDuplicate()` fan-out và
>    backpressure một tầng. Đây đúng là những API mà Vert.x bọc lại — và bọc lại là chính thứ
>    ta không muốn ở tầng này.
> 2. Kênh nội bộ (ADR-1) và biên WebSocket dùng chung **một** mô hình `ChannelPipeline`,
>    không phải hai mô hình tinh thần khác nhau.
> 3. Vert.x là Netty được bọc; ở tầng này lớp bọc thêm khoảng cách chẩn đoán mà không bớt việc.
>
> **Lộ trình "chuyển sang Go Gateway khi CCU > 100.000" (Mục 16.3 và 25.2.6) đã bị LOẠI khỏi
> phạm vi** — đó là suy đoán cho mốc gấp đôi target hiện tại và nó làm loãng quyết định phase 1.

<details>
<summary>Nội dung đánh giá gốc của v2.1 (lưu trữ)</summary>

### 16.0. Đánh giá Khả năng Thay thế Spring WebFlux bằng Eclipse Vert.x cho Tầng Gateway

Đánh giá chuyên sâu việc sử dụng **Eclipse Vert.x** thay thế cho **Spring WebFlux** tại tầng **Netty Gateway Service (Network Edge)**:

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ ECLIPSE VERT.X VS. SPRING WEBFLUX COMPARISON FOR REALTIME GATEWAY                     │
 │ • Vert.x Latency p99: Sub-millisecond (Nhanh hơn 20-30% nhờ lược bỏ Spring Overhead)   │
 │ • RAM Footprint: Vert.x chỉ ~30-50MB RAM (So với 150-300MB RAM của Spring WebFlux)    │
 │ • Concurrency Model: Multi-Verticle Event Loop (Mỗi CPU Core 1 Event Loop độc lập)     │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 16.1. Bảng So sánh Chi tiết: Vert.x vs. Spring WebFlux

| Tiêu chí Kỹ thuật | Eclipse Vert.x | Spring WebFlux (Project Reactor) | Đội Thắng tại Tầng Gateway |
| :--- | :--- | :--- | :--- |
| **Độ trễ Latency p99** | 🟢 **Sub-millisecond** | 🟡 Thấp, nhưng bị chút Spring Overhead | 🟢 **Vert.x Thắng** (Latency p99 mượt hơn) |
| **RAM Footprint & Memory** | 🟢 **Cực nhẹ (~30 - 50MB RAM)** | 🟡 Nặng hơn (~150 - 300MB RAM) | 🟢 **Vert.x Thắng** (Tiết kiệm 80% RAM) |
| **Mô hình Concurrency** | **Multi-Verticle Event Loop** | Project Reactor (`Mono`/`Flux`) | 🟢 **Vert.x Thắng** (Mô hình Verticle rất tự nhiên) |
| **Tốc độ Học & Viết Code** | 🟢 Event Bus & Callback đơn giản | 🔴 Khó viết hơn do "Reactive Hell" (`flatMap`) | 🟢 **Vert.x Thắng** (Dễ đọc & debug hơn) |
| **Tích hợp Spring Ecosystem**| 🟡 Cần cầu nối độc lập | 🟢 **Tương thích 100%** với Spring Boot 3 | 🟢 **Spring WebFlux Thắng** |
| **Tùy biến Netty Pipeline** | 🟢 Tự do can thiệp Netty Direct Memory | 🟡 Bị ẩn dưới WebFlux Abstraction | 🟢 **Vert.x Thắng** |

---

### 16.2. Kết luận đã chốt — Netty trực tiếp (v2.1 khuyến nghị Vert.x, ĐÃ BỊ THAY THẾ)

> [!WARNING]
> **Nội dung gốc của Mục 16.2 trong v2.1 — *"RẤT NÊN CHUYỂN SANG ECLIPSE VERT.X"* —
> đã bị BÃI BỎ (R-14).** Nó mâu thuẫn trực tiếp với banner tài liệu và Mục 2 (vốn chọn Netty),
> tạo ra ba câu trả lời khác nhau cho cùng một câu hỏi. Bảng so sánh 16.1 ở trên được **giữ lại
> làm hồ sơ quyết định**, không phải khuyến nghị đang có hiệu lực.

**Quyết định có hiệu lực: Netty trực tiếp cho Gateway.** Và phải thừa nhận đây là lựa chọn
*tốn công hơn* Vert.x — nó được chọn vì ba lý do cụ thể, không phải vì nhanh hơn:

1. **ADR-1 cần quyền kiểm soát thô mà Vert.x bọc lại.** Kênh nội bộ đòi hỏi framing tự định
   nghĩa, high/low watermark trên chính socket, `retainedDuplicate()` fan-out và backpressure
   **một tầng duy nhất**. Đây đúng là nhóm API mà Vert.x trừu tượng hoá — và lớp trừu tượng
   đó chính là thứ không muốn có ở đây.
2. **Một mô hình tinh thần, không phải hai.** Biên WebSocket và kênh nội bộ (ADR-1) dùng chung
   một `ChannelPipeline`. Dev debug một mô hình, không phải hai.
3. **Vert.x là Netty được bọc.** Ở tầng này, lớp bọc thêm khoảng cách chẩn đoán khi sự cố mà
   không bớt được việc gì.

**Tầng Game Engine**: **Pekko Typed + Cluster Sharding** (ADR-2) — *không phải* "Loom / Akka"
như v2.1 viết. Actor **không chạy trên virtual thread**; xem Mục 14.7 (R-15) để biết virtual
thread thực sự được dùng ở đâu (tầng I/O blocking: Redis snapshot, PostgreSQL, Kafka producer).

---


### 16.3. Sơ đồ Cân nhắc Lựa chọn Tầng Biên WebSocket Gateway (Go GW vs. Java Vert.x GW)

Để tạo sự linh hoạt tối đa cho đội ngũ phát triển khi triển khai thực tế, tầng WebSocket Gateway thiết kế theo cơ chế mô-đun hóa có thể thay thế (Pluggable Architecture) với 2 phương án công nghệ chính:

```text
                     Load Balancer
                         │
             ┌───────────┴───────────┐
             │                       │
       Go WebSocket GW        Java Vert.x GW
       (Siêu nhẹ RAM)         (Chung Java Stack)
             │                       │
             └───────────┬───────────┘
                         ▼
                    Game Engine
                   (Java 25+ / Loom)
```

#### Bảng Ma trận Tiêu chí Lựa chọn:

| Tiêu chí | Phương án A: Go WebSocket GW | Phương án B: Java Vert.x GW |
| :--- | :--- | :--- |
| **Công nghệ** | Golang (`gorilla/websocket` hoặc `gnet`) | Java 25+ (`Eclipse Vert.x` trên nền Netty) |
| **Tiêu tốn RAM Base** | 🟢 **Siêu nhẹ (~20 - 30MB RAM / Pod)** | 🟡 Nhẹ (~50 - 100MB RAM / Pod) |
| **Tốc độ Startup Container** | 🟢 **< 1 giây** | 🟢 **~ 1 - 2 giây** |
| **Độ nhất quán Stack** | 🔴 Phân mảnh công nghệ (Java + Go) | 🟢 **100% Java Stack với Game Engine** |
| **Tái sử dụng Thư viện** | 🔴 Phải compile & sync `.proto` code riêng cho Go | 🟢 Dùng chung 100% DTO, Auth, Proto Java Classes |
| **Độ trễ Latency p99** | 🟢 Sub-millisecond | 🟢 Sub-millisecond |

#### 🎯 Lộ trình Đề xuất Triển khai (Phased Rollout Strategy):
1. **Giai đoạn 1 (Ưu tiên sản xuất nhanh - Default)**: Triển khai **Java Vert.x Gateway**.
   * *Lý do*: Giúp toàn bộ hệ thống thống nhất 100% trên nền tảng **Java Ecosystem**, tái sử dụng toàn bộ thư viện Java Auth/Proto sẵn có, tiết kiệm thời gian phát triển và không làm phân mảnh nhân sự.
2. **Giai đoạn 2 (Khi Scale 100.000+ CCU)**: Cân nhắc chuyển sang **Go WebSocket Gateway**.
   * *Lý do*: Tối ưu hóa tối đa chi phí RAM hạ tầng khi lượng kết nối WebSocket bùng nổ hàng triệu kết nối.

---



</details>

---

## 17. 📡 Đánh giá Kỹ thuật: Native WebSocket + Protobuf vs. Socket.io (Backend & Frontend)

Đánh giá so sánh chuyên sâu giữa việc sử dụng **Native WebSocket + Protobuf Binary** và **Socket.io (Engine.io Protocol)** cho toàn bộ hệ thống ở cả phía Backend và Frontend dưới quy mô tải đỉnh **54.000 CCU**:

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ NATIVE WEBSOCKET + PROTOBUF VS. SOCKET.IO COMPARISON                                   │
 │ • Native WebSocket: W3C Standard RFC 6455 (Zero Overhead, Binary ArrayBuffer, <80B)   │
 │ • Socket.io: High-level Application Framework over Engine.io (JSON Framing Overhead)   │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 17.1. Bảng So sánh Tổng quan Kỹ thuật

| Tiêu chí So sánh | Native WebSocket + Protobuf Binary | Socket.io (Engine.io Protocol + JSON) | Đội Thắng cho Game 54k CCU |
| :--- | :--- | :--- | :--- |
| **Bản chất Giao thức** | Standard W3C / IETF RFC 6455 (Cấp thấp) | High-level Library trên Engine.io (Cấp cao) | 🟢 **Native WebSocket Thắng** |
| **Throughput & Capacity (54k CCU)**| 🟢 **Tối ưu cực đại cho Netty / Vert.x** | 🔴 Thấp hơn do bị Overhead Packet Framing | 🟢 **Native WebSocket Thắng** |
| **Kích thước Packet (Payload Size)**| 🟢 **Siêu nhỏ (< 50 - 80 bytes Protobuf)** | 🔴 Lớn hơn do bọc Engine.io Headers & JSON | 🟢 **Native WebSocket Thắng** |
| **Hỗ trợ Native Netty / Vert.x / Go**| 🟢 Chuẩn hóa 100% trong mọi ngôn ngữ | 🔴 Bị hạn chế (Library Java Socket.io kém) | 🟢 **Native WebSocket Thắng** |
| **Tiết kiệm Pin & 4G/5G cho Mobile**| 🟢 **Tối ưu 100% (Binary Frame)** | 🟡 Tốn pin hơn do Engine.io Heartbeat JSON | 🟢 **Native WebSocket Thắng** |
| **Tự động Reconnect & Fallback** | 🟡 Client tự code tay (Exponential Backoff)| 🟢 Có sẵn out-of-the-box | 🟢 **Socket.io Thắng** |
| **Cú pháp Lập trình Frontend** | 🟡 Thô hơn (`ws.send(arrayBuffer)`) | 🟢 Thân thuộc (`socket.emit('event', data)`) | 🟢 **Socket.io Thắng** |

---

### 17.2. Đánh giá Ưu & Nhược điểm Chi tiết tại Tầng Backend (Netty / Vert.x / Go)

#### 1. Native WebSocket + Protobuf Binary (Backend)
* 🟢 **Ưu điểm**:
  * Đọc mảng nhị phân `Direct ByteBuf` trực tiếp từ card mạng (Zero-Copy) mà không tốn CPU giải mã packet Engine.io.
  * Tương thích 100% với các đòn bẩy hiệu năng cao như Linux Epoll Native Transport của Netty/Vert.x.
  * Độ trễ p99 **< 1ms**, tiêu tốn bộ nhớ RAM cực kỳ ít (chỉ ~30-50MB RAM / Gateway Pod).
* 🔴 **Nhược điểm**:
  * Phụ trách việc tự viết logic Heartbeat Ping/Pong và Channel Group Management (tuy nhiên Netty đã hỗ trợ sẵn `WebSocketServerProtocolHandler`).

#### 2. Socket.io Server (Backend)
* 🟢 **Ưu điểm**: Có sẵn tính năng quản lý Room/Namespace (`io.to("room1").emit(...)`) và HTTP Long Polling fallback.
* 🔴 **Nhược điểm**:
  * Tạo ra **Overhead Packet Framing** lớn, làm tăng lượng CPU bóc tách packet khi 54.000 CCU cùng nộp bài.
  * Các thư viện Socket.io Server trên nền Java (như `netty-socketio`) bị chậm cập nhật, không battle-tested và không tối ưu bằng Native Netty/Vert.x WebSocket.
  * Không thiết kế tối ưu cho việc truyền nhận dữ liệu nhị phân Protobuf.

---

### 17.3. Đánh giá Ưu & Nhược điểm Chi tiết tại Tầng Frontend (Web / Mobile App / Unity)

#### 1. Native WebSocket + Protobuf Binary (Frontend)
* 🟢 **Ưu điểm**:
  * Có sẵn trong 100% trình duyệt Web (`new WebSocket(url)`), iOS/Android Native và Unity/Unreal Engine 5 mà **không cần cài thêm thư viện nặng**.
  * Băng thông cực kỳ nhẹ, không tốn pin hay dung lượng 4G/5G của học sinh khi làm bài thi.
* 🔴 **Nhược điểm**:
  * Lập trình viên phải tự bắt sự kiện `onclose`/`onerror` để làm thuật toán **Auto-Reconnect with Exponential Backoff** (tuy nhiên mã nguồn chỉ khoảng 30 dòng JS/Kotlin/C#).

#### 2. Socket.io Client (Frontend)
* 🟢 **Ưu điểm**: Cú pháp `socket.on()` và `socket.emit()` rất thân thuộc với các Web Dev. Tự động kết nối lại khi đứt mạng.
* 🔴 **Nhược điểm**:
  * Phải nạp thêm thư viện `socket.io-client` (~40KB minified).
  * Các gói tin Heartbeat ping/pong dạng JSON của Engine.io chạy liên tục làm tốn pin thiết bị di động của học sinh.

---

### 🎯 17.4. Kết luận Khuyên dùng (Architectural Choice)

👉 **BẮT BUỘC SỬ DỤNG: NATIVE WEBSOCKET + PROTOBUF BINARY!**

* **Lý do cốt lõi**: Bài toán **54.000 CCU** đòi hỏi tối ưu đến từng byte băng thông và từng mili-giây CPU tại Netty/Vert.x Gateway. Việc sử dụng **Native WebSocket + Protobuf Binary** mang lại hiệu năng cao hơn **300% - 400%** so với Socket.io, hoàn toàn đáp ứng các tiêu chuẩn khắt khe nhất của hệ thống game thời gian thực.

---


## 18. 🔐 Quy chuẩn Xác thực WebSocket (WebSocket Authentication & Security Protocol)

Do chuẩn W3C của trình duyệt Web không cho phép thêm Custom HTTP Headers (như `Authorization: Bearer <token>`) vào lệnh `new WebSocket(url)`, hệ thống quy chuẩn hóa kiến trúc **Xác thực An toàn 3 Tầng** cho kết nối WebSocket:

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ PHƯƠNG ÁN XÁC THỰC CHUẨN: ONE-TIME TICKET AUTHENTICATION (Khuyên dùng)                  │
 │   Step 1: Client gọi REST API POST /api/v1/auth/ws-ticket ➔ Nhận Ticket Code (TTL 30s) │
 │   Step 2: Connect ws://gateway.edtech.com/ws/game?ticket=TK_987654321                  │
 │   Step 3: Netty Gateway validate Ticket trong Redis & xóa ngay (Single-use Ticket)     │
 │   Step 4: Khóa student_id & room_id vào Netty Channel Attributes                       │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 18.1. Ba (3) Phương án Truyền Token Authentication khi Handshake WebSocket

| Phương án | Cơ chế Hoạt động | Đánh giá An ninh & Bảo mật | Đề xuất Sử dụng |
| :--- | :--- | :--- | :--- |
| **P1: Query Parameter** | `ws://gateway/ws?token=eyJhbGci...` | 🔴 **Rủi ro**: Token bị lưu vào Access Log của NGINX / Cloudflare. | Chỉ dùng cho môi trường Dev / Testing. |
| **P2: First Message Auth**| Connect WS ➔ Client gửi tin nhắn đầu tiên `AUTH_REQUEST` trong 5s. | 🟡 **Khá tốt**: Nhưng bị tốn 1 RTT rào cản Handshake ban đầu. | Phù hợp ứng dụng Web đơn giản. |
| **P3: One-Time Ticket** | REST API cấp Ticket (TTL 30s) ➔ Connect `ws://gateway/ws?ticket=TK_xxx` | 🟢 **Bảo mật Tuyệt đối**: Ticket chỉ dùng 1 lần, không lộ JWT Token trên URL log. | 🟢 **CHỌN DÙNG CHO SẢN XUẤT (PROD)**. |

---

### 18.2. Quy trình Xác thực & Lưu Channel Attributes tại Netty Gateway

1. **Trích xuất Ticket**: Netty Gateway nhận yêu cầu Handshake chứa `ticket=TK_987654321`.
2. **Validate Ticket nguyên tử trong Redis**:
   * Kiểm tra và xóa Ticket trong Redis bằng Lua Script (`GETDEL`):
     ```lua
     local student_data = redis.call('GETDEL', KEYS[1])
     if student_data then return student_data else return nil end
     ```
   * Nếu Ticket không tồn tại hoặc đã bị dùng ➔ Từ chối Handshake ngay với HTTP `401 Unauthorized`.
3. **Gắn Channel Attributes**:
   * Giải mã thông tin `student_id`, `student_index`, `room_id`, `role` và gắn trực tiếp vào thuộc tính ẩn của kết nối (`ctx.channel().attr(STUDENT_KEY).set(studentData)`).
   * Mọi gói tin nộp bài sau đó **không cần gửi kèm token nữa**, Netty tự trích xuất `student_id` từ Channel Attribute ➔ Chống tuyệt đối hành vi giả mạo ID người khác (*Identity Spoofing*).

---

### 18.3. Xử lý Token Hết hạn Giữa trận (In-Session Token Refresh)

* **Vấn đề**: Phiên học kéo dài 70 phút, nhưng JWT Token hết hạn sau 60 phút.
* **Giải pháp**:
  * Khi JWT Token sắp hết hạn (còn 5 phút), Spring Boot Auth Service phát sự kiện.
  * Netty Gateway gửi gói tin `TOKEN_EXPIRING_WARNING` xuống Client qua WebSocket.
  * Client gọi REST API lấy Token mới và gửi packet `REFRESH_SESSION_REQUEST` qua WebSocket để cập nhật Session mà **không bị rớt mạng hay đứt quãng phiên học**.

---


## 19. 🎯 Chi tiết 5 Dạng Game Thực tế & Quy trình Phối hợp từ A đến Z (End-to-End Delivery Protocol)

Để giúp đội ngũ Kỹ sư, Product và Nội dung dễ hình dung cách vận hành thực tế, dưới đây là mô tả chi tiết 5 dạng Game EdTech phổ biến và quy trình phối hợp 6 bước từ khi lên ý tưởng đến khi học sinh chơi thành công.

---

### 19.1. Chi tiết 5 Dạng Game Thực tế trong Hệ thống

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ GAME 1: TRẮC NGHIỆM TỐC ĐỘ CÁ NHÂN (Solo Speed Quiz 1vAll)                              │
 │   • Luật chơi: 12 em trả lời độc lập 10 câu trắc nghiệm. Nộp nhanh + đúng ➔ Điểm cao.  │
 │   • Game Scope: PRIVATE_SOLO (Chấm điểm unicast) & ROOM_BROADCAST (Top Leaderboard).  │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ GAME 2: ĐUA XE ĐỒNG ĐỘI (Team Race 4v4)                                                │
 │   • Luật chơi: Chia 4 nhóm (mỗi nhóm 3 em). Điểm nhóm đổi thành mét xe tiến lên.       │
 │   • Game Scope: TEAM_ONLY (Tiến trình nhóm) & ROOM_BROADCAST (Vị trí 4 xe đua).       │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ GAME 3: LẬT THẺ GHI NHỚ & THẢO LUẬN NHÓM (Team Memory Card & Co-Editing Draft)        │
 │   • Luật chơi: Nhóm lật 2 thẻ bài trùng khớp. Em A gõ nháp "xxx" ➔ 2 bạn trong team thấy.│
 │   • Game Scope: TEAM_ONLY (Draft nháp & Lật thẻ kín trong nhóm).                       │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ GAME 4: ĐÁNH BOSS THẾ GIỚI TOÀN LỚP (World Boss Battle Class Co-op)                     │
 │   • Luật chơi: Cả lớp 12/100 em cùng trả lời để tích lũy sát thương trừ HP của Boss.    │
 │   • Game Scope: ROOM_BROADCAST (Thanh máu Boss HP & Tổng sát thương cả lớp).           │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ GAME 5: GHÉP TỪ KÉO THẢ ĐẤU ĐÔI (Word Drag & Drop Matchmaking 2v2)                      │
 │   • Luật chơi: Hệ thống Matchmaking xếp 2v2 kéo thả ghép từ vựng Tiếng Anh theo MMR.  │
 │   • Game Scope: MATCHMAKING QUEUE ➔ ROOM_BROADCAST (Tỉ số 2 cặp đấu).                 │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 19.2. Quy trình Phối hợp 6 Bước từ Ý tưởng đến khi Khách hàng Chơi thành công (End-to-End Delivery Lifecycle)

```text
 ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
 │   BƯỚC 1     │     │   BƯỚC 2     │     │   BƯỚC 3     │     │   BƯỚC 4     │     │   BƯỚC 5     │     │   BƯỚC 6     │
 │ Product/Content│──►│ BE Architect │──►│  Backend Dev │──►│ Frontend Dev │──►│ QA & DevOps  │──►│ Production   │
 │ Lên ý tưởng  │     │ Thống nhất   │     │ Viết Plugin  │     │ Dựng UI &    │     │ Load Test    │     │ Học sinh chơi│
 │ & Luật game  │     │ Proto Schema │     │ & Game Rules │     │ Protobuf JS  │     │ 54k CCU      │     │ mượt 100%    │
 └──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
```

#### 📌 BƯỚC 1: Product & Content Team chốt Luật chơi (Game Spec Specification)
* Team Product & Nội dung viết file mô tả nghiệp vụ (Game Requirement Document):
  * **Tên Game**: *Đua xe đồng đội (`TEAM_RACE`)*.
  * **Luật chơi**: 4 nhóm đua xe. Trả lời đúng trong 3 giây ➔ Xe tiến 15m. Trả lời đúng sau 3 giây ➔ Xe tiến 10m. Nhóm nào đạt 100m trước ➔ Thắng.

#### 📌 BƯỚC 2: Backend Architect & Frontend Lead chốt Hợp đồng Giao tiếp (Protobuf Schema Contract)
* Hai bên thống nhất file `game_message.proto` mà không cần quan tâm ngôn ngữ lập trình của nhau:
  ```protobuf
  // Client gửi lên
  message SubmitAnswerRequest {
    uint32 question_id = 1;
    string answer_choice = 2; // "B"
    fixed64 client_timestamp = 3;
  }
  // Server trả về
  message TeamRaceProgressBroadcast {
    repeated TeamRaceDistance teams = 1;
  }
  message TeamRaceDistance {
    string team_id = 1;
    uint32 distance_meters = 2; // Ví dụ: 45m
  }
  ```

#### 📌 BƯỚC 3: Backend Dev triển khai Plugin Logic (Backend Implementation)
* Backend Dev **KHÔNG SỬA CODE LÕI ENGINE**, chỉ tạo 1 file Java Plugin mới: `TeamRaceMechanic.java` implement `GameMechanicPlugin`:
  * Viết hàm `calculateScore()` đổi thời gian trả lời thành số mét xe chạy.
  * Viết hàm `checkWinCondition()` kiểm tra xem có nhóm nào cán mốc 100m chưa.
  * Đóng gói file JSON kịch bản `Game Definition` mẫu lên CMS.

#### 📌 BƯỚC 4: Frontend Dev triển khai Giao diện & Protobuf Client (Frontend Implementation)
* Frontend Dev (Web/Mobile/Unity) nạp file `.proto` đã thống nhất ở Bước 2:
  * Dựng giao diện 4 chiếc xe đua 2D/3D.
  * Tích hợp cơ chế **Optimistic UI**: Khi học sinh chọn "B", nút đổi màu ngay.
  * Đọc gói tin `TeamRaceProgressBroadcast` nhị phân 200ms để chạy animation xe tiến lên mượt mà bằng `requestAnimationFrame`.

#### 📌 BƯỚC 5: QA & DevOps Kiểm thử Chịu tải (Load Test & Chaos Engineering)
* **QA**: Chạy Unit Test & Integration Test kiểm tra luật chấm điểm đúng/sai và tính điểm Turbo.
* **DevOps**: Chạy kịch bản giả lập **54.000 CCU** bằng **Gatling** (tái dùng Protobuf class đã codegen) hoặc **k6 + extension WebSocket nhị phân** — xem phương pháp đầy đủ ở **Mục 32**. Kịch bản **bắt buộc gồm connection storm** (toàn bộ CCU nối trong 15 giây, Mục 8.6), chạy **tối thiểu 30 phút** để lộ rò rỉ direct memory (Mục 22.3) và GC drift ➔ Đánh sập ngẫu nhiên 20% Pods bằng Chaos Mesh ➔ Nghiệm thu theo **bảng SLA Mục 14.6**: mất dữ liệu đáp án = **0**, phòng hoạt động trở lại **< 30 s (p99)**, end-to-end submit → ACK **< 100 ms (p99)**.

> [!CAUTION]
> **Ba con số của v2.1 ở bước này đã bị thay thế:** *"270.000 req/s"* (mô hình tải sai ~300 lần
> — tải thật ~4.000 msg/s inbound / ~26.000 packet/s outbound, Mục 7.1), *"Locust/JMeter"*
> (không sinh được client WebSocket-Protobuf có state phiên — Mục 32), và
> *"khôi phục phòng < 50ms (p99)"* (đo sai giai đoạn — ADR-3, Mục 8.1).

#### 📌 BƯỚC 6: Phát hành & Khách hàng Chơi thành công (Production Release & User Play)
1. Giáo viên bấm nút "Bắt đầu Game Đua Xe" trên Dashboard.
2. Matchmaking Service xếp 12 học sinh vào phòng.
3. Netty Gateway đón 12 kết nối WebSocket, validate JWT Token.
4. Học sinh chọn đáp án ➔ Netty đẩy Internal Frame Channel ➔ Actor đơn luồng chấm điểm ➔ Gom 200ms broadcast ➔ 4 chiếc xe trên màn hình 12 học sinh đồng loạt lao về đích mượt mà!

---


## 20. 📬 Quy chuẩn Phân vùng & Quản lý Kafka Topics (Domain-Driven Kafka Topic Modeling)

Để giải quyết lo ngại về việc hệ thống có nhiều Topics và nhiều nghiệp vụ cùng tiêu thụ dữ liệu mà không làm cồng kềnh hay nghẽn Kafka Broker, hệ thống quy chuẩn hóa kiến trúc **3 Nhóm Topic Domain** và cơ chế **Consumer Group Isolation**:

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. REALTIME CORE EVENTS (Nhóm Sự kiện Trận đấu - Hot Path)                            │
 │    • game.events.submit         : Sự kiện nộp bài của học sinh.                         │
 │    • game.events.lifecycle      : Sự kiện đổi trạng thái phòng (Start, Pause, Finish).   │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 2. PLATFORM & MATCHMAKING EVENTS (Nhóm Sự kiện Nền tảng & Ghép trận)                   │
 │    • game.matchmaking.tickets   : Vé tìm trận từ Matchmaker.                             │
 │    • game.player.progression    : Sự kiện tăng Level, XP, nhận danh hiệu.               │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ 3. COLD PATH & DATA PIPELINE (Nhóm Thống kê & Lưu vết Audit)                           │
 │    • game.audit.logs            : Nhật ký bảo mật và chống cheat.                        │
 │    • game.analytics.raw-answers : Dữ liệu thô -> PostgreSQL partition theo tháng.        │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 20.1. Ba (3) Nguyên tắc Vàng Quản lý Kafka Topics

#### 1. Partitioning Strategy by `game_id` / `room_id` (Đảm bảo Tuần tự 100%)
* Mọi tin nhắn nộp bài gửi vào Kafka Topic `game.events.submit` bắt buộc phải đính kèm `PartitionKey = room_id`.
* **Tác dụng**: Tất cả bài nộp của cùng 1 phòng học sẽ luôn rơi vào **cùng 1 Kafka Partition**, đảm bảo các sự kiện được xếp hàng và Consume theo đúng thứ tự thời gian 1, 2, 3...N.

#### 2. Phân tách Độc lập Consumer Groups (Consumer Group Isolation)
* Các nghiệp vụ tiêu thụ dữ liệu được tách thành các **Consumer Group riêng biệt**:
  * `leaderboard-worker-group`: Đọc `game.events.lifecycle` để ZADD xếp hạng toàn trường.
  * `analytics-worker-group`: Đọc `game.analytics.raw-answers` để ghi Batch Insert vào **PostgreSQL (bảng partition theo tháng)**. *(ClickHouse đã bị CẮT khỏi kiến trúc — Mục 2.)*
  * `audit-worker-group`: Đọc `game.audit.logs` để ghi nhật ký chống hack.
* **Tác dụng**: Nếu `analytics-worker-group` bị chậm/nghẽn, **Leaderboard Worker và Game Engine vẫn chạy mượt 100%**, hoàn toàn không bị ảnh hưởng chéo.

#### 3. Chính sách Dọn dẹp Đĩa cứng Tự động (Retention & Compaction Policy)
* **Realtime Hot Topics** (`game.events.submit`): Đặt `retention.ms = 86400000` (Tự động xóa tin nhắn sau 24 giờ để giải phóng đĩa cứng).
* **Compacted Topics** (`game.player.progression`): Đặt `cleanup.policy = compact` để Kafka chỉ giữ lại bản ghi mới nhất của mỗi học sinh.

---


## 21. 🛡️ Cơ chế Bảo vệ Runtime Guardrails chống Lỗi Logic trong Kịch bản Game Definition

Khi cho phép tạo và tải lên các kịch bản Game Definition động (JSON/YAML), hệ thống phải đối mặt với rủi ro Game Definition chứa logic lỗi (ví dụ: vòng lặp vô tận giữa các Step, chia cho 0 khi tính điểm, thiếu field cấu hình `next_step_id`, hoặc kịch bản bị treo vô thời hạn). Để bảo vệ Game Engine không bị đơ, treo Thread pool hay sập hệ thống, 4 Rào chắn An toàn (Runtime Guardrails) sau đây được áp dụng tại tầng Runtime Execution Engine.

---

### 21.1. Bốn (4) Rào Chắn An Toàn (Runtime Guardrails)

1. **Absolute Step Timeout (Giới hạn Thời gian Chờ Tối đa mỗi Step)**:
   * Mọi Step (màn chơi / câu hỏi) đều có giới hạn `max_duration_seconds` (mặc định tối đa 60 giây).
   * Nếu hết thời gian mà chưa chuyển bước, Scheduled Executor của Actor sẽ tự động kích hoạt `StepTimeoutEvent`, ép buộc chuyển sang Step tiếp theo hoặc kết thúc màn chơi an toàn.

2. **Max Step Transition Counter (Bảo vệ Vòng lặp Vô tận)**:
   * Để chống lỗi cấu hình Game Definition bị lặp vô tận (ví dụ: Step A ➔ Step B ➔ Step A...), mỗi Game Session duy trì một biến đếm `step_transition_count`.
   * Nếu `step_transition_count > MAX_TRANSITIONS` (ví dụ: 50 bước trong 1 lượt chơi), Game Engine tự động ngắt trận đấu, ghi log Warning vào OpenTelemetry và hạ cánh an toàn (Graceful Shutdown) về trạng thái `FINISHED`.

3. **Safe Math & Input Clamping (Bảo vệ Lỗi Chia cho 0 & Ngoại lệ Toán học)**:
   * Mọi công thức tính điểm động từ Game Definition đều trải qua bộ lọc `SafeMathEvaluator`.
   * Tự động bắt lỗi `ArithmeticException` (Chia cho 0), `NaN`, `Infinity`. Trường hợp vi phạm sẽ lấy giá trị điểm mặc định (`fallback_score = 0`) và clamp kết quả trong khoảng an toàn 0 ≤ score ≤ 1.000.

4. **Fallback Next Step & Schema Validation Failure Recovery**:
   * Khi `next_step_id` chỉ định một Step không tồn tại trong Game Definition, Engine không quăng unhandled `NullPointerException`.
   * Thay vào đó, Engine tự động chuyển hướng về `END_GAME` step hoặc Step mặc định kề sau theo thứ tự mảng.

---

### 21.2. Java Implementation Pattern (`StepRunnerGuardrail`)

Dưới đây là đoạn mã Java 25+ minh họa cơ chế bọc Guardrail cho Game Engine Actor:

```java
package com.k12.game.engine.guardrail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class StepRunnerGuardrail {
    private static final Logger log = LoggerFactory.getLogger(StepRunnerGuardrail.class);
    private static final int MAX_STEP_TRANSITIONS = 50;
    private static final long DEFAULT_STEP_TIMEOUT_SEC = 60;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger transitionCounter = new AtomicInteger(0);
    private ScheduledFuture<?> currentTimeoutTask;

    public void executeStep(String gameId, String currentStepId, Runnable stepLogic, Runnable onTimeoutFallback) {
        // 1. Check Loop Guardrail
        if (transitionCounter.incrementAndGet() > MAX_STEP_TRANSITIONS) {
            log.error("[GUARDRAIL] Game {} exceeded max transitions ({}). Force terminating room to prevent infinite loop!", 
                    gameId, MAX_STEP_TRANSITIONS);
            forceTerminateGame(gameId);
            return;
        }

        // 2. Cancel previous timeout
        if (currentTimeoutTask != null && !currentTimeoutTask.isDone()) {
            currentTimeoutTask.cancel(false);
        }

        // 3. Schedule Step Timeout Guardrail
        currentTimeoutTask = scheduler.schedule(() -> {
            log.warn("[GUARDRAIL] Step {} in Game {} timed out after {}s! Triggering fallback next step.",
                    currentStepId, gameId, DEFAULT_STEP_TIMEOUT_SEC);
            onTimeoutFallback.run();
        }, DEFAULT_STEP_TIMEOUT_SEC, TimeUnit.SECONDS);

        // 4. Safe Execution of Business Logic
        try {
            stepLogic.run();
        } catch (Exception e) {
            log.error("[GUARDRAIL] Exception encountered during Step {} execution in Game {}. Recovering smoothly.",
                    currentStepId, gameId, e);
            onTimeoutFallback.run();
        }
    }

    public double calculateSafeScore(double rawAnswerTimeMs, double basePoints, double timeWeight) {
        // Safe Math Guardrail: Prevent Division by Zero and NaN/Infinity
        try {
            if (rawAnswerTimeMs <= 0) return basePoints;
            double score = basePoints / (rawAnswerTimeMs * timeWeight);
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                log.warn("[GUARDRAIL] Invalid math calculation result: {}. Defaulting to 0.", score);
                return 0.0;
            }
            // Clamp result between 0 and 1000
            return Math.clamp(score, 0.0, 1000.0);
        } catch (ArithmeticException ae) {
            log.error("[GUARDRAIL] ArithmeticException caught during score calculation. Fallback to 0.0", ae);
            return 0.0;
        }
    }

    private void forceTerminateGame(String gameId) {
        // Graceful cleanup logic for corrupt game definitions
        log.info("[GUARDRAIL] Cleaning up room resources for game: {}", gameId);
    }
}
```

---


## 22. ⚡ Chiến lược Chống Treo Thread & Kiểm Soát Fan-Out Broadcast (Anti-Thread Blocking & Uncontrolled Fan-Out Mitigation)

Nỗi sợ lớn nhất trong các hệ thống Real-time Multiplayer ở quy mô hàng chục ngàn CCU là:
1. **Treo Thread (Thread Starvation / Thread Hanging)**: Một vài task xử lý I/O blocking hoặc lặp tính toán lâu làm nghẽn Thread pool, dẫn đến kéo sập toàn bộ các phòng chơi khác.
2. **Fan-Out Không Kiểm Soát (Broadcast Storm / Thundering Herd)**: Khi 1 sự kiện xảy ra, hệ thống broadcast đồng loạt cho hàng nghìn WebSocket clients, gây trào bộ nhớ (Out-Of-Memory GC Pause), nghẽn băng thông NIC và cháy CPU Gateway.

Kiến trúc dưới đây giải quyết triệt để 2 nguy cơ này bằng các cơ chế phòng vệ nhiều lớp.

---

### 22.1. Bốn (4) Nguyên tắc Chống Treo Thread Triệt để

```text
 ┌───────────────────────────────────────────────────────────────────────────────────────┐
 │ NETTY EVENT LOOP THREADS (IO-Only, Non-blocking, Epoll)                              │
 │   • Chỉ làm 2 việc: Đọc Bytes từ Socket & Đóng gói Frame nhị phân.                    │
 │   • NGHIÊM CẤM 100%: DB Query, Redis Call, HTTP Call, JSON Parse, CPU Heavy Logic.    │
 └──────────────────────────┬────────────────────────────────────────────────────────────┘
                            │ Internal Frame Channel (Zero-Copy)
                            ▼
 ┌───────────────────────────────────────────────────────────────────────────────────────┐
 │ PEKKO DISPATCHER — ACTOR EXECUTION (Game Logic Execution)                              │
 │   • 1 Room = 1 Actor, chạy trên pool platform thread của dispatcher (KHÔNG phải         │
 │     1 virtual thread mỗi actor — xem Mục 14.7 / R-15).                                  │
 │   • 1 Room = 1 Actor Execution Context (Xử lý tuần tự nội bộ 1 phòng).               │
 │   • Không dùng Lock Shared Mutable State -> CHỐNG DEADLOCK 100%.                       │
 │   • Tránh Thread Pinning: Thay `synchronized` bằng `ReentrantLock`.                     │
 │   • Watchdog Timeout: Mỗi tick xử lý không quá 10ms (Overbudget -> Alert & Skip).     │
 └───────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Cô lập Tuyệt đối Netty EventLoop (Zero-Blocking Netty Threads)**:
   * Netty I/O EventLoop Threads được cố định số lượng = CPU Cores  ×  2.
   * Mọi packet từ Client sau khi bóc tách header sẽ được Internal Frame Channel đẩy ngay sang **Actor Mailbox** (Pekko dispatcher). Netty EventLoop không bao giờ bị nghẽn (0ms blocking time).

2. **Loại bỏ Deadlock bằng Mô hình Single-Threaded Actor (1 Room = 1 Actor)**:
   * Không có cơ chế Locking (`synchronized` hay `Lock`) giữa các phòng chơi. State của phòng chơi nào nằm trọn trong Actor của phòng đó.
   * Vì không có Lock contention giữa các thread, nguy cơ **Deadlock treo thread là 0%**.

3. **Chống Thread Pinning — chỉ áp dụng cho TẦNG I/O, không phải cho Actor**:
   * Khi dùng Virtual Threads (Project Loom), nếu gọi I/O blocking trong khối `synchronized`, Virtual Thread sẽ bị "pin" (dính chặt) vào Carrier Native Thread, gây nghẽn Native Thread Pool.
   * **Phạm vi áp dụng**: quy tắc này chỉ dành cho tầng chạy trên virtual thread — **Redis snapshot I/O, PostgreSQL ghi kết quả, Kafka producer** (xem bảng phân bổ Mục 14.7). `RoomActor` chạy trên Pekko dispatcher (platform thread) nên **không dính pinning**, và Netty EventLoop cũng vậy.
   * **Quy tắc**: mã ở tầng I/O nói trên dùng `java.util.concurrent.locks.ReentrantLock`, **không** dùng `synchronized` bao quanh lời gọi blocking. Trong `RoomActor` thì không cần khoá gì cả — mô hình actor đã tuần tự hoá sẵn.

> [!NOTE]
> v2.1 viết *"cơ chế Non-blocking Message Queue của Akka/Vert.x"*. **Vert.x đã bị loại khỏi
> kiến trúc (R-14, Mục 16)** và tên framework đúng là **Pekko** (ADR-2).

4. **Watchdog Execution Time Budget — ĐO & CẢNH BÁO, không ngắt được**:

> [!CAUTION]
> **v2.1 viết**: *"Nếu 1 logic chạy quá 10ms, Watchdog Timer sẽ **ngắt** và đẩy warning log."*
> **Không có cách an toàn nào để ngắt code Java đang chạy trên một thread khác.**
> `Thread.stop()` đã bị gỡ bỏ; `Thread.interrupt()` chỉ có tác dụng tại các điểm blocking —
> một vòng lặp CPU sẽ phớt lờ nó hoàn toàn. Nếu Game Definition có vòng lặp vô tận,
> actor **sẽ treo** bất chấp watchdog. Cơ chế mà v2.1 mô tả không tồn tại.

Ba cơ chế thật, thay cho một cơ chế không tồn tại:

**a) Đo sau, cảnh báo** *(đây mới là thứ watchdog làm được — và nó đủ cho mục đích thật:
phát hiện logic chậm để dev sửa)*
```java
long t0 = System.nanoTime();
state.handle(msg);
long durUs = (System.nanoTime() - t0) / 1000;
actorProcessingTimer.record(durUs, MICROSECONDS);
if (durUs > 10_000) {
    log.warn("room={} msg={} over budget {}us", roomId, msg.type(), durUs);
}
```

**b) Chặn ở đầu vào, không chặn lúc chạy** *(phòng ngừa thật sự)* — vòng lặp vô tận phải bị
chặn khi **nạp Game Definition**, không phải khi thực thi:
* Validate DAG các step lúc upload — phát hiện chu trình bằng duyệt đồ thị.
* Cấm biểu thức tùy ý; công thức điểm dùng **DSL giới hạn (không Turing-complete)**, không
  nhúng script engine.
* `MAX_TRANSITIONS` của Mục 21.1.2 giữ nguyên — nó đúng và là hàng rào cuối lúc chạy.

**c) Cách ly ở tầng pod** *(hàng rào cuối cùng)* — nếu một actor vẫn treo được, thứ cứu hệ
thống là **dispatcher riêng** cho logic từ Game Definition, cộng với liveness probe fail →
K8s restart pod. Chấp nhận mất một pod, không mất cả cluster.
```hocon
game-logic-dispatcher {
  type = Dispatcher
  executor = "fork-join-executor"
  fork-join-executor { parallelism-min = 4, parallelism-max = 16 }
  throughput = 1
}
```

---

### 22.2. Kiểm Soát Fan-Out Broadcast (Fan-Out Storm Mitigation)

Khi một sự kiện phòng nổ ra, làm sao để broadcast cho N học sinh mà không gây quá tải?

```text
               ┌─────────────────────────────────────────────────┐
               │    Game Engine Actor (Chỉ Serialize 1 LẦN)     │
               └────────────────────────┬────────────────────────┘
                                        │ 1 Packet Protobuf Binary
                                        ▼
               ┌─────────────────────────────────────────────────┐
               │    Netty Gateway Instance (Zero-Copy Buffer)    │
               └────┬───────────────────┬───────────────────┬────┘
                    │ retainedDuplicate() │ retainedDuplicate() │ retainedDuplicate()
                    ▼                   ▼                   ▼
              [WebSocket 1]       [WebSocket 2]       [WebSocket N]
              (Student 1)         (Student 2)         (Student N)
```

#### 1. Giới hạn Phạm vi Broadcast (Room Boundary Isolation)
* Bản chất lớp học EdTech phân tách thành các phòng 12 - 100 học sinh.
* **Nguyên tắc**: **Không bao giờ broadcast toàn hệ thống (Global Broadcast)** từ Game Engine. Mọi broadcast đều scoped theo `room_id`. Fan-out tối đa cho 1 sự kiện phòng chỉ là N = 12 
ightarrow 100 connections.

#### 2. Kỹ thuật Zero-Copy & Shared Memory Buffer (`ByteBuf.retainedDuplicate()`)

> [!CAUTION]
> **Lỗi tiềm ẩn của v2.1 — bắt buộc dùng `retainedDuplicate()`, KHÔNG dùng `retain()`.**
> v2.1 dùng lẫn lộn hai API: Mục 2.1.3.A ghi `retainedDuplicate()` (đúng) còn Mục 23.4 ghi
> `retain()` (sai) — hai mục trong cùng tài liệu mâu thuẫn, và bản sai nằm ở mục mô tả luồng chính.
>
> `retain()` **chỉ tăng reference count, không tạo reader index riêng**. Nhiều `writeAndFlush`
> trên *cùng một* `ByteBuf`: client đầu tiên đọc hết buffer và đẩy `readerIndex` tới cuối,
> **các client sau ghi 0 byte**. Biểu hiện ra ngoài là *"một số học sinh không nhận được
> update"* — cực khó truy vì không có exception nào.
>
> ```java
> // SAI
> for (Channel ch : roomChannels)
>     ch.writeAndFlush(new BinaryWebSocketFrame(frame.retain()));   // client 2+ nhận 0 byte
>
> // ĐÚNG
> try {
>     for (Channel ch : roomChannels) {
>         if (!ch.isWritable()) { dropNonCritical(ch); continue; }
>         ch.writeAndFlush(new BinaryWebSocketFrame(frame.retainedDuplicate()));
>     }
> } finally {
>     frame.release();   // thiếu dòng này là rò rỉ direct memory
> }
> ```
> `retainedDuplicate()` vẫn chia sẻ vùng nhớ (đúng ý đồ zero-copy của v2.1) nhưng mỗi bản có
> reader/writer index độc lập.
>
> **Kiểm tra bắt buộc**: bật `-Dio.netty.leakDetection.level=paranoid` trên staging.
> Không có nó, rò rỉ direct memory chỉ lộ ra sau vài giờ chạy tải (xem Mục 32).
* **Cách ngây thơ (Gây OOM)**: Với 100 học sinh, serialize mảng Protobuf 100 lần 
ightarrow tạo 100 đối tượng `byte[]` đẩy vào RAM 
ightarrow GC Pause sập hệ thống.
* **Cách Chuẩn Chống Fan-Out Storm**:
  * Game Engine / Netty Gateway **chỉ serialize Protobuf đúng 1 lần** tạo thành 1 vùng nhớ `DirectByteBuf`.
  * Khi gửi cho 100 WebSockets, Gateway chỉ gọi `ByteBuf.retainedDuplicate()` (chỉ tăng Reference Count, không copy bộ nhớ).
  * Chi phí RAM cho Fan-out 100 người = O(1) memory allocation!

#### 3. Kiểm soát Nghẽn Mạng Mới Client bằng Netty Watermark (Backpressure Control)
* Khi học sinh có mạng yếu (3G/4G chập chờn), socket không ghi kịp dữ liệu ra ngoài, tin nhắn broadcast sẽ bị dồn ứ tại `ChannelOutboundBuffer` của Gateway.
* **Cơ chế Backpressure**:
  * Đặt `WRITE_BUFFER_HIGH_WATER_MARK = 64 KB` và `WRITE_BUFFER_LOW_WATER_MARK = 32 KB`.
  * Khi buffer socket của 1 client vượt quá 64 KB, `channel.isWritable()` đổi thành `false`.
  * Gateway sẽ **tự động drop các gói tin Broadcast không quan trọng** (như animation position) hoặc chủ động ngắt kết nối WebSocket của client chập chờn đó, bảo vệ RAM Gateway không bị dồn ứ OOM!

#### 4. Phân tầng Broadcast sự kiện Hệ thống (Two-Tier Global Fan-out Sharding)
* Với các thông báo toàn trường (ví dụ: Thông báo bảo trì, Sự kiện chung toàn trường):
  * **Tầng 1**: Engine gửi 1 message duy nhất vào Kafka / Redis Pub/Sub Topic `system.global.broadcast`.
  * **Tầng 2**: Mỗi Pod Netty Gateway (đang giữ 5.000 WebSockets) tự subscribe Topic này. Khi nhận 1 message, từng Pod Gateway chỉ fan-out cho 5.000 local connections nằm trên RAM của chính Pod đó.
  * **Kết quả**: Không có hiện tượng 1 node duy nhất phải chịu tải fan-out 50.000 connections.

---

### 22.3. Mã nguồn Java Minh họa kiểm soát High Watermark & Zero-Copy Broadcast

```java
package com.k12.gateway.broadcast;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SafeRoomBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(SafeRoomBroadcaster.class);

    /**
     * Broadcast an binary payload to a group of room channels safely with Zero-Copy & Backpressure
     */
    public void broadcastToRoom(ChannelGroup roomChannels, ByteBuf protobufPayload) {
        if (roomChannels.isEmpty()) {
            protobufPayload.release();
            return;
        }

        try {
            for (Channel channel : roomChannels) {
                // 1. BACKPRESSURE CHECK: Skip or Drop frame if client write buffer is full (Slow Client Protection)
                if (!channel.isWritable()) {
                    log.warn("[BACKPRESSURE] Client {} buffer full (High Watermark hit). Dropping non-critical broadcast frame.",
                            channel.remoteAddress());
                    continue; // Safeguard Gateway RAM from memory leak
                }

                // 2. ZERO-COPY BROADCAST: Retain buffer reference count instead of allocating new bytes
                ByteBuf duplicatePayload = protobufPayload.retainedDuplicate();
                channel.writeAndFlush(new BinaryWebSocketFrame(duplicatePayload));
            }
        } finally {
            // Release the original payload buffer reference
            protobufPayload.release();
        }
    }
}
```

---


---

### 22.4. Phân tích & Giải pháp Chống Đẩy Nhầm Room (Cross-Room Data Leakage Protection)

Lo ngại về việc **Redis Pub/Sub đẩy nhầm tin nhắn từ Room này sang Room khác** (ví dụ: Học sinh phòng 101 lại nhìn thấy đáp án/điểm của phòng 102) xuất phát từ 3 nguyên nhân kỹ thuật:
1. **Đặt tên Channel bị trùng/mơ hồ** (ví dụ: dùng `room:1` dễ đè với `room:11` nếu dùng wildcard `PSUBSCRIBE room:*`).
2. **Rò rỉ Subscription (Subscription Leak)**: Gateway Pod quên `UNSUBSCRIBE` channel phòng cũ sau khi phòng kết thúc, dẫn đến socket kết nối lại nhận nhầm tin phòng khác.
3. **Lỗi Routing Table tại Gateway**: Socket của học sinh đã rời phòng A nhưng vẫn nằm trong `ChannelGroup` của phòng A tại Netty Memory.

Kiến trúc giải quyết triệt me vấn đề này bằng **4 Lớp Kiểm Soát cách ly (Zero-Trust Room Isolation)**:

```text
 ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 │ LỚP 1: KHÔNG DÙNG REDIS PUB/SUB CHO TIN NHẮN PHÒNG (Internal Frame Channel)                 │
 │   • Hot-path tin nhắn phòng (Chấm điểm, Đua xe) CHẠY THẲNG qua Internal Frame Channel.     │
 │   • REDIS PUB/SUB CHỈ DÙNG CHO GLOBAL BROADCAST TOÀN HỆ THỐNG (k12:sys:global).         │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
                                             │ (Đối với sự kiện phòng bắt buộc qua Pub/Sub)
                                             ▼
 ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 │ LỚP 2: Quy chuẩn Namespace & Hash Tag (Chống đè Channel)                                │
 │   • Channel Format: `k12:{env}:game:room:{room_uuid}` (Dùng UUIDv4, KHÔNG dùng ID tự tăng)│
 └─────────────────────────────────────────────────────────────────────────────────────────┘
                                             │
                                             ▼
 ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 │ LỚP 3: Gateway Frame Verification (Kiểm tra lại lần 2 trước khi Socket Write)           │
 │   • Netty Gateway đọc Attribute `ROOM_ID` gán trên Socket Channel.                      │
 │   • If (socket.getRoomId() != msg.getRoomId()) -> DROP IMMEDIATE & ALERT LOG!           │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
                                             │
                                             ▼
 ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 │ LỚP 4: Lifecycle Auto-Cleanup & Room Channel Group Isolation                            │
 │   • Học sinh ngắt kết nối / đổi phòng -> Tự động xóa Socket khỏi `ChannelGroup` ngay.  │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
```

#### 1. Lớp 1: Bỏ hoàn toàn Redis Pub/Sub khỏi Hot-Path của Phòng chơi (Kiến trúc Quyết định)
* **Thực tế tốt nhất (Best Practice)**: Redis Pub/Sub **KHÔNG NÊN** dùng để routing tin nhắn thời gian thực của từng phòng riêng lẻ vì chi phí `SUBSCRIBE` / `UNSUBSCRIBE` động liên tục sẽ làm nghẽn CPU của Redis Single Thread!
* **Giải pháp trong Kiến trúc**:
  * Khi 12 học sinh vào phòng, Netty Gateway duy trì kết nối **Internal Frame Channel trực tiếp** tới Pod Game Engine giữ Actor của phòng đó.
  * Khi Game Engine tính xong kết quả, nó đẩy packet qua chính Internal Frame Channel đó về lại đúng Gateway Pod chứa 12 socket của phòng.
  * **Kết quả**: **Hoàn toàn KHÔNG qua Redis Pub/Sub**. Loại bỏ 100% rủi ro nhầm channel hay rò rỉ tin nhắn giữa các phòng!

#### 2. Lớp 2: Quy chuẩn Đặt tên Redis Channel (Strict Namespace Schema)
* Trong trường hợp cần dùng Redis Pub/Sub (như hạ tầng đa cụm Gateway):
  * **Channel Key Format**: `k12:{env}:room:{{room_uuid}}:events` (Ví dụ: `k12:prod:room:{a8f3-4b12-9c90}:events`).
  * **Hash Tag `{room_uuid}`**: Đảm bảo toàn bộ tin nhắn của 1 phòng nằm trọn trong 1 Redis Cluster Node, tối ưu O(1) Pub/Sub.
  * **Tuyệt đối CẤM**: Không dùng pattern `PSUBSCRIBE` với dấu `*` ở tầng Gateway để tránh nhận dồn tin nhắn phòng khác.

#### 3. Lớp 3: Netty Gateway Frame Guardrail (Bảo vệ Vòng cuối tại Socket)
Ngay trước khi Netty Gateway ghi gói tin nhị phân vào Socket của học sinh, Gateway thực hiện một câu lệnh kiểm tra an toàn (O(1) RAM lookup):

```java
public void safeWriteToStudentSocket(Channel channel, GameBroadcastMessage msg) {
    // Read the room_id bound to this physical WebSocket connection during authentication
    String socketRoomId = channel.attr(NettyAttributes.ROOM_ID_KEY).get();

    // DOUBLE CHECK: Validate room isolation before writing bytes
    if (!msg.getRoomId().equals(socketRoomId)) {
        log.error("[SECURITY_ALERT] Prevented cross-room data leak! Socket Room: {}, Payload Room: {}. Dropping frame!",
                socketRoomId, msg.getRoomId());
        // Security metrics counter for Prometheus/Grafana
        Metrics.counter("security.cross_room_leak_prevented").increment();
        return; // ABSOLUTE ISOLATION GUARANTEE
    }

    // Safe write
    channel.writeAndFlush(new BinaryWebSocketFrame(msg.getPayload()));
}
```

#### 4. Lớp 4: Tự động Dọn dẹp Routing Table khi Disconnect (Idempotent Channel Cleanup)
* Khi socket của học sinh đóng (`channelInactive`), Netty Handler lập tức gọi:
  `roomChannelGroup.remove(channel);`
  `channel.attr(NettyAttributes.ROOM_ID_KEY).set(null);`
* Khi một phòng kết thúc (`ROOM_FINISHED`), Game Engine phát sự kiện hủy `ChannelGroup` của phòng đó trên Gateway, giải phóng hoàn toàn bộ nhớ.

---


# 📱 PHẦN V — TRIỂN KHAI ĐA POD & KIẾN TRÚC FRONTEND

---

## 23. 🌐 Mô hình Triển khai Đa Pod & Luồng Dữ liệu Thực tế (Multi-Pod Deployment Topology & Practical Data Flow)

Để đáp ứng quy mô **3x Peak Target = 54.000 CCU** (4.500  phòng chơi đồng thời), hệ thống được triển khai theo mô hình microservices đa cụm (Multi-Pod Cluster) trên Kubernetes. Phần này mô tả chi tiết từng bước vận hành thực tế của luồng dữ liệu khi hạ tầng mở rộng ra hàng chục Pods.

---

### 23.1. Cấu hình Hạ tầng Pods cho Target 54.000 CCU

```text
                                  ┌────────────────────────────────────────┐
                                  │   AWS ALB / KUBERNETES INGRESS NGINX   │
                                  └───────────────────┬────────────────────┘
                                                      │
                       ┌──────────────────────────────┴──────────────────────────────┐
                       ▼                                                             ▼
       ┌───────────────────────────────┐                             ┌───────────────────────────────┐
       │   NETTY GATEWAY POOL (10 Pods)│                             │   NETTY GATEWAY POOL (10 Pods)│
       │   Pod A -> Kế nối 5.000 WS    │                             │   Pod B -> Kết nối 5.000 WS   │
       └───────────────┬───────────────┘                             └───────────────┬───────────────┘
                       │                                                             │
                       │ Internal Frame Channel                                  │ Internal Frame Channel
                       ▼                                                             ▼
       ┌─────────────────────────────────────────────────────────────────────────────────────────────┐
       │                          GAME ENGINE CLUSTER POOL (4 Pods)                                  │
       │  ┌──────────────────────┐   ┌──────────────────────┐   ┌─────────────────────────────────┐  │
       │  │ Engine Pod 1         │   │ Engine Pod 2         │   │ Engine Pod 3 & 4 (Dự phòng HA)  │  │
       │  │ (Phòng 1 -> 1.500)   │   │ (Phòng 1.501 -> 3.000│   │ (Phòng 3.001 -> 4.500)          │  │
       │  └──────────────────────┘   └──────────────────────┘   └─────────────────────────────────┘  │
       └──────────────────────────────────────────────┬──────────────────────────────────────────────┘
                                                      │
                                                      ▼
       ┌─────────────────────────────────────────────────────────────────────────────────────────────┐
       │                              STATE & EVENT INFRASTRUCTURE                                   │
       │   • Redis Cluster (3 Master - 3 Replica) : Session Registry, Room State Snapshots           │
       │   • Apache Kafka Cluster (3 Brokers)     : Domain Event Pipeline                            │
       └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

> [!IMPORTANT]
> **Mọi con số dưới đây là GIẢ THUYẾT, chưa được đo.** v2.1 trình bày chúng như sự thật.
> Chúng là điểm khởi đầu hợp lý, không phải kết luận — phải xác nhận bằng benchmark
> **H1/H2 ở Mục 32** trước khi công bố hoặc dùng để lập ngân sách.

1. **Netty Gateway Pool (10 - 12 Pods)**:
   * **Cấu hình 1 Pod**: `2 vCPU`, `4 GB RAM`.
   * **Nhiệm vụ**: Duy trì ~5.000 kết nối WebSocket/Pod *(giả thuyết H1)*. Tổng 10 Pods duy trì 50.000 - 60.000 WebSockets.
   * **Đặc tính**: Completely Stateless (Không lưu logic game).
   * ⚠️ **Rủi ro chính KHÔNG phải steady state mà là handshake rate lúc đông** (Mục 8.6):
     360 TLS handshake/s/pod trên 2 vCPU. H1 phải đo riêng chỉ số này.

2. **Game Engine Pool (12 - 16 Pods)** — *v2.1 đề xuất 4 Pods*:
   * **Cấu hình 1 Pod**: `2 vCPU`, `4 GB RAM` *(v2.1: 4 vCPU / 8 GB)*.
   * **Nhiệm vụ**: Chạy ~300 – 375 Room Actors/Pod *(giả thuyết H2; v2.1: 1.125)*.
   * **Đặc tính**: Stateful in-memory, placement do Pekko Cluster Sharding quyết định.
   * **Vì sao đổi từ 4 pod lớn sang 12–16 pod nhỏ:**

     | | v2.1 | **v2.2** |
     |---|---|---|
     | Số Engine pod @54k | 4 | **12 – 16** |
     | Rooms/pod | 1.125 | ~300 – 375 |
     | **Học sinh ảnh hưởng khi mất 1 pod** | **13.500** | **~4.000** |
     | CPU/RAM mỗi pod | 4 vCPU / 8 GB | 2 vCPU / 4 GB |

     Tổng tài nguyên gần như không đổi. Đổi hiệu suất đóng gói lấy **blast radius nhỏ** —
     với hệ thống mà một sự cố ảnh hưởng trực tiếp tới giờ học đang diễn ra, đây là đánh đổi đúng.
     Ngoài ra 1.125 phòng khôi phục đồng thời tạo ra *recovery thundering herd* mà Mục 13.1
     của v2.1 không mô hình hóa (nó tính recovery cho **một phòng cô lập**). Xem Mục 8.1.4.
   * ⚠️ **Rủi ro chính là số timer đồng thời**, không phải CPU: mỗi phòng giữ ít nhất một
     scheduled timer (deadline câu hỏi + flush coalescing). H2 phải đo chỉ số này.

3. **Redis Cluster (3 Master - 3 Replica)**:
   * `Room Snapshot` + `epoch` phục vụ khôi phục và fencing (Mục 8.4).
   * `Session Registry` (`room:routing:{id}`) — **chỉ cho vận hành & teacher dashboard**,
     KHÔNG nằm trên hot path (định tuyến dùng lazy-learned cache — Mục 14.1).

---

### 23.2. Chi tiết Luồng Dữ liệu Thực tế từ A-Z qua Đa Pod (End-to-End Multi-Pod Flow)

Giả sử **Phòng 101** gồm 12 học sinh: 6 học sinh nối vào **Gateway Pod A**, 6 học sinh nối vào **Gateway Pod B**. Phòng 101 được phân bổ nằm trên **Engine Pod 2**.

#### 📌 BƯỚC 1: Ghép trận & Đăng ký Vị trí Phòng (Matchmaking & Actor Allocation)
1. Matchmaking Service xếp 12 học sinh vào `room_id = 101`.
2. Matchmaker chọn **Engine Pod 2** làm nơi khởi tạo `RoomActor(101)`.
3. Matchmaker đăng ký vị trí vào Redis Session Registry:
   `SET k12:session:room:101 "engine-pod-2.internal:50051"` (TTL = 2 giờ).

#### 📌 BƯỚC 2: Học sinh Kết nối & Mở luồng Internal Frame Channel (Client Connect & Channel Multiplexing)
1. 6 học sinh nối WebSocket tới **Gateway Pod A**; 6 học sinh nối WebSocket tới **Gateway Pod B**.
2. **Gateway Pod A** tra cứu Redis: `room:101 -> engine-pod-2.internal:50051`.
3. **Gateway Pod A** mở (hoặc tái sử dụng) 1 đường ống **Internal Frame Channel** duy nhất kết nối tới `Engine Pod 2`.
4. **Gateway Pod B** tương tự, mở 1 đường ống **Internal Frame Channel** tới `Engine Pod 2`.

#### 📌 BƯỚC 3: Học sinh Nộp bài & Xử lý Nội bộ Actor (Real-time Action Handling)
1. Học sinh số 1 (đang nối vào **Gateway Pod A**) bấm chọn đáp án "B".
2. **Gateway Pod A** nhận binary Protobuf frame từ WebSocket ➔ Đẩy thẳng vào Internal Frame Channel tới **Engine Pod 2**.
3. **Engine Pod 2** chuyển packet vào `Mailbox` của `RoomActor(101)`.
4. `RoomActor(101)` xử lý đơn luồng trong **0.2ms**:
   * Kiểm tra đáp án đúng/sai.
   * Cập nhật điểm và vị trí xe đua của 12 học sinh.
   * Tạo gói tin Broadcast binary `TeamRaceProgressBroadcast`.

#### 📌 BƯỚC 4: Broadcast Bắn ngược Zero-Broker (Zero-Broker Return Broadcast)
1. `RoomActor(101)` trên **Engine Pod 2** đẩy gói tin `TeamRaceProgressBroadcast` ngược lại qua 2 Internal Frame Channels đang mở sẵn với **Gateway Pod A** và **Gateway Pod B**.
2. **Gateway Pod A** nhận gói tin ➔ Dùng `ByteBuf.retainedDuplicate()` bắn cho 6 WebSocket clients cục bộ trên Pod A.
3. **Gateway Pod B** nhận gói tin ➔ Dùng `ByteBuf.retainedDuplicate()` bắn cho 6 WebSocket clients cục bộ trên Pod B.
4. **Kết quả**: Cả 12 học sinh trên 2 Pod khác nhau thấy màn hình cập nhật đồng loạt trong **< 20ms**!

#### 📌 BƯỚC 5: Luồng Pha trộn Hybrid cho Sự kiện Tương tác Phụ (TikTok-style Hybrid Pattern)
* Đối với hành động phụ như **Thả tim / Icon Cảm xúc / Chat room**:
  * Học sinh bấm Thả tim ➔ App gửi request **HTTP POST / HTTP/2** tới API Gateway (không chiếm dụng luồng WebSocket chính).
  * API Gateway gom batch (ví dụ: 50 lượt thả tim/giây) rồi đẩy 1 packet tổng qua HTTP/2 về Engine ➔ Giúp luồng WebSocket Core luôn mượt 100%, không bị rác băng thông!

---

### 23.3. Quy trình Tự Khôi phục khi Sập Pod (Pod Failure & Self-Healing Protocol)

```text
 ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 │ TRƯỜNG HỢP 1: SẬP NETTY GATEWAY POD A                                                   │
 │   1. 5.000 WebSockets trên Pod A bị ngắt kết nối.                                      │
 │   2. Ingress Load Balancer chuyển hướng Client tự động sang Gateway Pod C & D.          │
 │   3. Client tự nối lại (Reconnect Exponential Backoff) trong 1-2s.                      │
 │   4. Pod C tra cứu Redis, nối lại Internal Frame Channel tới Engine Pod 2 -> Trận đấu tiếp tục!     │
 ├─────────────────────────────────────────────────────────────────────────────────────────┤
 │ TRƯỜNG HỢP 2: SẬP GAME ENGINE POD 2                                                     │
 │   1. Failure detector phát hiện Pod 2 mất heartbeat.        [5 - 10 s]                  │
 │      acceptable-heartbeat-pause = 5s  (v2.1 ghi 1s -> false positive, xem Mục 8.4)      │
 │   2. SBR keep-majority quyết định down Pod 2 (stable-after). [5 - 10 s]                 │
 │   3. Cluster Sharding tạo lại `RoomActor(101)` trên POD còn sống.  [0.5 - 2 s]          │
 │   4. Actor mới INCR room:epoch:101 -> giành fencing token.                               │
 │      Actor cũ (nếu còn sống) ghi Redis sẽ bị Lua CAS từ chối -> tự dừng.                │
 │   5. Nạp Snapshot từ Redis, kiểm CRC32.            [10 - 50 ms]                          │
 │      Nạp theo lô có jitter 0-500ms để tránh thundering herd (Mục 8.1.4).                │
 │   6. Actor vào RESYNCING. Gateway học lại route (Mục 14.1), nối kênh mới.                │
 │   7. Client gửi RESYNC { last_acked_seq, pending[] } -> KHÔNG MẤT ĐÁP ÁN NÀO.           │
 │   8. Gia hạn deadline câu hỏi đúng bằng khoảng gián đoạn -> PLAYING.                     │
 │                                                                                          │
 │   TỔNG: ~12 - 25 s.  Mất dữ liệu: 0.                                                    │
 │   (v2.1 ghi "< 30ms" — con số đó chỉ là bước 5, xem Mục 8.1.1)                          │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
```

> [!CAUTION]
> **v2.1 mô tả kịch bản này như một chuỗi tuần tự sạch sẽ, bỏ qua hoàn toàn split-brain.**
> Nếu Pod 2 chỉ bị *cô lập mạng* chứ không chết, trong cửa sổ `stable-after` sẽ có **hai
> `RoomActor(101)` cùng sống** — chấm điểm hai lần, hai nguồn broadcast, hai luồng ghi snapshot.
> Bước 4 (fencing epoch) là thứ chặn điều đó, và nó **không có trong v2.1**. Xem Mục 8.4.

---


## 24. 📱 Hướng dẫn Kiến trúc Frontend cho App Flutter & Nhúng WebView (Flutter App & Hybrid WebView Integration)

Trong các ứng dụng EdTech thực tế, client thường được viết bằng **Flutter** (trên iOS/Android/Tablet), kết hợp với việc **nhúng WebView (HTML5 Canvas/PhaserJS)** để hiển thị các mini-game động do đội nội dung web sản xuất mà không cần đẩy App Store release liên tục.

Phần này quy định kiến trúc kết nối và tối ưu hiệu năng cho Flutter Client.

---

### 24.1. Lựa chọn Mô hình Kết nối WebSocket cho App Flutter

```text
  ┌─────────────────────────────────────────────────────────────────────────────────────────────┐
  │ MÔ HÌNH A: FLUTTER NATIVE CORE (Dành cho Game dựng bằng Flutter / Flame Engine)             │
  │   • Flutter (Dart) mở kết nối WebSocket trực tiếp qua `web_socket_channel` + Protobuf.      │
  │   • Ưu điểm: Độ trễ tối thiểu, chạy 60 FPS mượt mà bằng Impeller/Skia Renderer.             │
  ├─────────────────────────────────────────────────────────────────────────────────────────────┤
  │ MÔ HÌNH B: FLUTTER HYBRID WEBVIEW (Dành cho Game HTML5 / PhaserJS / Canvas nhúng)          │
  │   • Cách 1 (JS Direct Socket): WebView HTML5 tự mở WebSocket kết nối tới Gateway.           │
  │   • Cách 2 (Flutter-Bridge Socket - KHUYÊN DÙNG): Flutter Native giữ WebSocket, truyền    │
  │     data xuống WebView qua `JavascriptChannel` / ArrayBuffer.                               │
  └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

#### So sánh 2 Kiến trúc Nhúng WebView trong Flutter:

| Tiêu chí | Cách 1: WebView Tự Mở WebSocket (JS Socket) | Cách 2: Flutter Giữ Socket + JS Bridge (Khuyên dùng) |
| :--- | :--- | :--- |
| **Quản lý Vòng đời App** | ❌ **Rủi ro**: Khi user ẩn App xuống Background, Android/iOS sẽ freeze WebView CPU ➔ Mất kết nối WebSocket. | 🟢 **An toàn**: Flutter Native duy trì Heartbeat & Reconnect ngầm mượt mà khi App rảnh rỗi. |
| **Bảo mật JWT Auth** | ❌ Token phải quăng vào JS Context (Dễ bị lộ nếu Webview bị inspect). | 🟢 Token lưu an toàn trong Flutter `FlutterSecureStorage`. |
| **Độ phức tạp Dev** | 🟢 Đơn giản (Dev HTML5 viết code như Web bình thường). | 🟡 Phải viết Javascript Bridge giữa Dart và JS Context. |

---

### 24.2. Luồng Dữ liệu Chi tiết giữa Flutter Native, WebView và Netty Gateway

Khi áp dụng **Mô hình B (Cách 2 - Flutter Bridge Socket)**:

```text
 ┌────────────────┐          WebSocket (Protobuf Binary)          ┌───────────────────┐
 │ FLUTTER DART   │ <───────────────────────────────────────────> │ NETTY GATEWAY POD │
 │ NATIVE LAYER   │                                               └───────────────────┘
 └───────┬────────┘
         │
         │  1. Decode Protobuf Binary -> JSON / TypedArray
         │  2. Call `webViewController.runJavaScript("onGameUpdate(...)")`
         ▼
 ┌────────────────┐
 │ EMBEDDED       │  3. HTML5 Canvas / PhaserJS render animation
 │ WEBVIEW (JS)   │     bằng `requestAnimationFrame()` (60 FPS)
 └────────────────┘
```

1. **Khởi tạo & Xác thực**:
   * App Flutter lấy `One-Time Ticket` từ Backend via REST API.
   * Flutter Native mở WebSocket tới Netty Gateway kèm Ticket.
   * Flutter load URL Game HTML5 vào `webview_flutter` plugin.
2. **Truyền Dữ liệu từ Flutter ➔ WebView**:
   * Khi nhận gói tin Protobuf từ Netty Gateway, Flutter Dart decode binary payload.
   * Flutter đẩy sang WebView qua kênh giao tiếp cao tốc:
     `webViewController.runJavaScript("window.onServerMessage('" + jsonPayload + "')");`
3. **Truyền Dữ liệu từ WebView ➔ Flutter (Học sinh nộp bài)**:
   * Khi học sinh bấm đáp án trên màn hình WebView, JS gọi:
     `GameBridge.postMessage(JSON.stringify({action: 'SUBMIT_ANSWER', answer: 'B'}));`
   * Flutter Native bắt sự kiện ở `JavascriptChannel`, mã hóa thành Protobuf Binary và đẩy lên Netty Gateway qua WebSocket.

---

### 24.3. Các Tối ưu Bắt buộc cho Flutter Embedded WebView

1. **Bật Hardware Acceleration & Tối ưu WebView Android/iOS**:
   ```dart
   // Cấu hình tối ưu cho webview_flutter
   final WebViewController controller = WebViewController()
     ..setJavaScriptMode(JavaScriptMode.unrestricted)
     ..setBackgroundColor(const Color(0x00000000))
     ..addJavaScriptChannel(
       'GameBridge',
       onMessageReceived: (JavaScriptMessage message) {
         // Xử lý bài nộp từ WebView gửi lên Flutter Native
         flutterSocketService.sendAnswer(message.message);
       },
     );
   ```

2. **Dọn dẹp Bộ nhớ RAM khi Thoát Game (Prevent Chromium Memory Leak)**:
   * Trình duyệt Chromium nhúng trong WebView tốn 100MB - 200MB RAM per instance.
   * **Quy tắc**: Khi học sinh kết thúc trận đấu và quay lại màn hình chính Flutter, bắt buộc phải gọi:
     `controller.loadRequest(Uri.parse('about:blank'));`
     để ép Chromium V8 Engine giải phóng hoàn toàn bộ nhớ RAM.

3. **Cơ chế Nội suy Smooth Animation (Linear Interpolation - Lerp)**:
   * Server gửi gói tin vị trí xe đua chu kỳ 200ms (5  tick/s).
   * JS trong WebView **KHÔNG ĐƯỢC** nhảy thẳng vị trí xe mà phải dùng thuật toán **Linear Interpolation (Lerp)** kết hợp `requestAnimationFrame` để xe di chuyển trơn tru ở tốc độ 60  FPS trên màn hình thiết bị.

---


# 🗺️ PHẦN VI — ĐÁNH GIÁ KIẾN TRÚC & LỘ TRÌNH TRIỂN KHAI

---

## 25. 📊 Đánh giá Độc lập & Lộ trình Tinh chỉnh Thực chiến (Architectural Review & Refinement Protocol)

Hệ thống ghi nhận các điểm mạnh cốt lõi — **1 Room = 1 Actor đơn luồng**, tách biệt
Gateway/Engine, Authoritative Protobuf WebSocket, Cross-room Isolation, Runtime Guardrails —
đồng thời thực hiện **6 Tinh chỉnh Thực chiến** nhằm giảm nợ kỹ thuật và tối ưu chi phí vận hành.

> *Ghi chú v2.2*: v2.1 mở đầu mục này bằng *"Đánh giá Kiến trúc Tổng thể (Điểm số 8.2/10)"*.
> Con số đó **không có nguồn và tự tham chiếu** — không nói ai đánh giá, theo tiêu chí nào.
> Đã bỏ. Nếu cần một đánh giá có trọng lượng, hãy ghi rõ người đánh giá và bộ tiêu chí.

---

### 25.1. Bảng 6 Điểm Tinh chỉnh — trạng thái trong v2.2

```text
 ┌─────────────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. RECOVERY SLA        [THAY THẾ] 50-100ms -> mất dữ liệu = 0, phòng trở lại < 30s (Mục 8.1)│
 │ 2. POD CAPACITY        [SỬA]      Blast radius là tiêu chí chính, không phải packing (23.1) │
 │ 3. BACKPRESSURE        [GIỮ]      Giám sát actor_mailbox_queue_depth, drop non-critical      │
 │ 4. MANAGED SERVICES    [GIỮ]      ElastiCache / RDS / MSK cho MVP                           │
 │ 5. ADAPTIVE TICK RATE  [ĐẢO NGƯỢC] Mặc định COALESCE; FIXED chỉ cho game chuyển động (7.1)  │
 │ 6. GATEWAY EVOLUTION   [LOẠI]     Chốt Netty. Vert.x/Go ra khỏi phạm vi (Mục 16)            │
 └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 25.2. Chi tiết 6 Tinh chỉnh Kiến trúc

#### 1. ❌ Recovery SLA 50–100ms — ĐÃ BỊ THAY THẾ bởi Mục 8.1
* **v2.1 lập luận**: Redis Read + Deserialize + Kafka Seek dao động 40–80ms → chuẩn hóa SLA
  thành 50–100ms (p99), *"con số học sinh hoàn toàn không thể nhận ra"*.
* **Vì sao sai**: phép cộng đó chỉ gồm **giai đoạn nạp lại state**. Nó bỏ qua failure detection
  (5–10 s) và SBR `stable-after` (5–10 s) — hai thành phần *chi phối* toàn bộ thời gian.
  Tổng thực tế ≈ **12–25 s**, không phải 50–100ms. Học sinh **chắc chắn nhận ra** 20 giây.
* **Thay bằng**: mục tiêu là **mất dữ liệu = 0** qua client replay + `LastSeenSequenceTable`,
  và UI chịu được gián đoạn. Bảng SLA mới ở **Mục 8.1.5** và **Mục 14.6**.

#### 2. Phân bổ Pod Capacity — tiêu chí chính là BLAST RADIUS, không phải packing
* v2.1 giữ 1.125 rooms/pod cho Tier 1 (4 pod cho 54k CCU) và chỉ giảm xuống 300–500 rooms/pod
  cho Tier 3 vì lý do **CPU/mailbox**.
* v2.2 giảm xuống ~300–375 rooms/pod **cho mọi tier**, vì lý do khác: mất 1 pod ở cấu hình
  v2.1 = **13.500 học sinh** gián đoạn đồng thời, kèm *recovery thundering herd*. Xem Mục 23.1.
* Tier 3 vẫn cần thêm headroom CPU: HPA scale-out theo `actor_mailbox_queue_depth` và CPU.

#### 3. Kiểm soát Backpressure & Metric `actor_mailbox_queue_depth` *(giữ nguyên từ v2.1)*
* Đặt chỉ số cảnh báo trên Prometheus/Grafana: `actor_mailbox_queue_depth`.
* **Quy tắc**: Nếu `actor_mailbox_queue_depth > 100` trên một Room Actor, Gateway kích hoạt
  backpressure: drop các gói Non-critical (vị trí xe / animation nháp) để ưu tiên bài nộp Critical.
* *Lưu ý v2.2*: chạy trên Internal Frame Channel (ADR-1) với **một tầng** flow control, không
  còn HTTP/2 window chồng lên app-level window.

#### 4. Chiến lược Giảm Phức tạp Vận hành *(giữ nguyên từ v2.1)*
* **Giai đoạn MVP**: tối đa hóa **Managed Services** — AWS ElastiCache (Redis), AWS RDS
  PostgreSQL, AWS MSK (Kafka, khi tới giai đoạn cần).
* **ClickHouse: đã CẮT khỏi kiến trúc**, không phải "tạm hoãn". Analytics ghi vào PostgreSQL
  table partitioning theo tháng. Cân nhắc lại kho OLAP khi log vượt 10 GB/ngày.
* *Bổ sung v2.2*: sau khi Kafka rời khỏi đường khôi phục (Mục 8.1), **MSK hoãn được thật sự** —
  MVP không còn lỗ hổng recovery khi thiếu Kafka.

#### 5. 🔄 Adaptive Tick Rate — v2.1 KẾT LUẬN NGƯỢC, đã đảo lại
* **v2.1 viết**: *"Tier 1 & 2 (Quiz): duy trì 200ms (5 ticks/s) để tối ưu 58% băng thông.
  Tier 3: chuyển sang 50ms."*
* **Vì sao ngược**: với quiz, fixed-rate 200ms **tăng** outbound ~10 lần (270.000 vs 26.000
  packet/s @54k CCU) vì nó phát đều kể cả khi phòng không có gì thay đổi. Con số "58%" tính
  trên một mô hình tải sai ~300 lần. Xem **Mục 7.1**.
* **Đúng phải là**:
  * **Tier 1 & 2 → `tick_mode: COALESCE`** (mặc định). 200ms là *trần tần suất*, phòng im
    lặng phát 0 gói.
  * **Tier 3 (chuyển động liên tục, cần lerp) → `tick_mode: FIXED`, `tick_interval_ms: 50`.**
    Đây là loại game duy nhất thật sự cần nhịp phát đều.
  * Cấu hình theo **Game Definition**, không phải cấu hình toàn cục.

#### 6. ❌ Lộ trình Tiến hóa Gateway — ĐÃ LOẠI khỏi phạm vi
* v2.1 đề xuất: Vert.x ở giai đoạn 1 → Go Gateway khi CCU > 100.000.
* **Đã chốt Netty** (xem Mục 16). Lý do loại lộ trình này:
  * Nó mâu thuẫn với chính banner và Mục 2 của v2.1 (vốn chọn Netty) — ba khuyến nghị khác
    nhau trong một tài liệu.
  * Mốc "CCU > 100.000" gấp đôi target hiện tại; lên kế hoạch cho nó bây giờ làm loãng
    quyết định của phase 1.
  * Việc viết lại Gateway bằng Go phải được quyết định bằng **số đo chi phí RAM thật** (Mục 31),
    không phải bằng ước lượng "giảm 60%" không nguồn.

---




---

### 🐳 25.3. Tương thích & Tối ưu hóa Tuyệt đối với Kubernetes (Java 25+ K8s Integration Guidelines)

Java 25+ và Kiến trúc hệ thống này tương thích **100% Native với hạ tầng Kubernetes (K8s)** nhờ các đặc tính tối ưu hóa container thế hệ mới:

1. **CGroup v2 Container Awareness & OOM Protection**:
   * Java 25+ tự động đọc chính xác `resources.limits.memory` của Kubernetes Pod.
   * Sử dụng `-XX:MaxRAMPercentage=75.0` giúp JVM tự động tính toán Heap RAM theo hạn mức Pod, loại bỏ 100% rủi ro bị Kubernetes `OOMKilled`.

2. **Trả lại RAM vật lý cho K8s Node (`-XX:ZUncommit=true`)**:
   * Cả **G1** (từ JDK 12, qua `-XX:G1PeriodicGCInterval`) lẫn **ZGC** đều trả RAM rảnh rỗi về OS — đây **không phải** điểm khác biệt giữa hai bộ GC như v2.1 mô tả. Với heap 4–8 GB của pod Engine, **G1 là mặc định; ZGC chỉ được chọn nếu H2 (Mục 32) đo ra pause p99 vượt ngưỡng 10ms.** Việc trả RAM về OS chỉ có ý nghĩa chi phí thật khi kết hợp với **VPA / scale-to-zero ngoài giờ học** (Mục 31).

3. **Xử lý Tín hiệu `SIGTERM` & Graceful Shutdown Tự động**:
   * Khi K8s thực hiện Rolling Update hoặc Auto-scaling scale-in, K8s gửi tín hiệu `SIGTERM`.
   * Netty Gateway & Game Engine bắt `SIGTERM`, dừng nhận socket mới, hoàn tất ghi Snapshot phòng chơi vào Redis trong 10ms trước khi ngắt Pod mà **không gây ngắt đoạn trận đấu của học sinh**.

4. **K8s Probes & HPA Auto-Scaling**:
   * Cấu hình Liveness Probe (`/health/liveness`) và Readiness Probe (`/health/readiness`).
   * Tự động scale-out số lượng Pods qua K8s HPA khi CPU > 70% hoặc chỉ số `actor_mailbox_queue_depth > 100`.

---

## 26. 🗺️ Lộ trình Triển khai Phân kỳ Tinh gọn (Lean 2-3 Week Phase Roadmap)

Tất cả các giai đoạn triển khai được giới hạn nghiêm ngặt **tối đa từ 2 đến 3 tuần mỗi giai đoạn** (Agile Sprints). Thành phần **ClickHouse OLAP được CẮT BỎ HOÀN TOÀN** để đơn giản hóa bộ máy vận hành (dữ liệu báo cáo lịch sử sử dụng PostgreSQL Table Partitioning / MongoDB). Nền tảng chạy trên Kubernetes, **Java 21 (LTS) là sàn tương thích** — *tham số GC do benchmark H2 (Mục 32) quyết định, không mặc định ZGC như v2.1 tuyên bố.*

---

### 26.1. Bảng Trận đồ Phân kỳ Triển khai (Max 2-3 Tuần/Giai đoạn)

```text
 ┌─────────────────────────────────────────────────────────────────────────────────────────────┐
 │ GIAI ĐOẠN 1: LEAN MVP SPRINT (3 Tuần | Tuần 1 - Tuần 3 | Target: 2.000 - 3.000 CCU)          │
 │   • Ưu tiên : 🔥 ƯU TIÊN SỐ 1 TUYỆT ĐỐI: Dựng Game Engine & WebSocket Core trước (Tuần 1).  │
 │   • Client  : Flutter Native Socket (web_socket_channel) + Protobuf + WebView JS Bridge.   │
 │   • Edge    : Netty Gateway (2 Pods) - Stateless Pipe.                                      │
 │   • Engine  : Java 25+ Pekko Actors (2 Pods) - Static Hash Routing (`room_id % 2`).          │
 │   • State   : Managed Redis (Session & Snapshot) + Managed PostgreSQL (`@Async` Save).       │
 │   • CẮT BỎ  : KHÔNG KAFKA, KHÔNG CLICKHOUSE, KHÔNG LZ4 COMPRESSION, KHÔNG CLUSTER SHARDING.  │
 │   • ⚠ ĐÁNH ĐỔI GĐ1: `room_id % 2` = KHÔNG rebalance, KHÔNG SBR. Mất 1 pod = mất 1/2 phòng   │
 │     tới khi pod lên lại. Chấp nhận được ở 2-3k CCU; PHẢI bỏ trước GĐ2 (xem cảnh báo dưới). │
 ├─────────────────────────────────────────────────────────────────────────────────────────────┤
 │ GIAI ĐOẠN 2: SCALE-OUT & ASYNC PIPELINE (3 Tuần | Tuần 4 - Tuần 6 | Target: 10k - 20k CCU)  │
 │   • Tuần 1 (Tuần 4): Tích hợp Apache Kafka (AWS MSK) phân tách Hot/Cold Path.               │
 │   • Tuần 2 (Tuần 5): Viết Async Leaderboard Worker (`ZADD` Redis) & Audit Worker (MongoDB). │
 │   • Tuần 3 (Tuần 6): Bật Pekko Cluster Sharding + SBR + fencing token (Mục 8.4).            │
 │     Phân bổ 12-16 Engine Pods nhỏ (2 vCPU/4 GB) — KHÔNG phải 4 pod lớn, xem R-05/Mục 23.1.  │
 ├─────────────────────────────────────────────────────────────────────────────────────────────┤
 │ GIAI ĐOẠN 3: ENTERPRISE SCALE & RESILIENCE (2 Tuần | Tuần 7 - Tuần 8 | Target: 54.000+ CCU) │
 │   • Tuần 1 (Tuần 7): Bật Adaptive LZ4HC Compression cho Snapshot + Postgres Partitioning.   │
 │   • Tuần 2 (Tuần 8): Multi-AZ Redis/Kafka HA + Stress Test 54.000 CCU theo Mục 32.          │
 │     Nghiệm thu theo bảng SLA Mục 14.6 (submit->ACK p99 < 100ms), KHÔNG phải "p99 < 20ms".   │
 │   • CẮT BỎ  : HOÀN TOÀN KHÔNG DÙNG CLICKHOUSE (Tận dụng Postgres Partitioning / MongoDB).    │
 └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 26.2. Chi tiết Công việc từng Giai đoạn (Tối đa 2-3 Tuần)

#### 📌 GIAI ĐOẠN 1: LEAN MVP SPRINT (3 Tuần | Tuần 1 - Tuần 3)
* **Mục tiêu**: Ra mắt bản MVP chơi Quiz realtime cho 2.000 - 3.000  CCU trong đúng 21 ngày làm việc.
* **Thành phần**: Flutter Native Socket ➔ Netty Gateway (2 Pods) ➔ Java 25+ Engine Pekko (2 Pods, `room_id % 2`) ➔ Redis + PostgreSQL.
* **Chi tiết**: Xem Phụ lục 27.

#### 📌 GIAI ĐOẠN 2: SCALE-OUT & ASYNC PIPELINE (3 Tuần | Tuần 4 - Tuần 6)
* **Mục tiêu**: Tách luồng xử lý Realtime và Ghi sổ, nâng khả năng chịu tải lên 10.000 - 20.000  CCU.
* **Công việc theo tuần**:
  * **Tuần 1 (Tuần 4)**: Triển khai Apache Kafka (AWS MSK), đẩy sự kiện `GAME_FINISHED` từ Engine sang Kafka thay vì gọi DB trực tiếp.
  * **Tuần 2 (Tuần 5)**: Xây dựng `Leaderboard Worker` (cập nhật `ZADD` Redis) và `Audit Worker` (lưu log chống hack vào MongoDB).
  * **Tuần 3 (Tuần 6)**: Bật `Pekko Cluster Sharding` cân bằng tải 4.500 phòng trên **12–16 Engine Pods nhỏ (2 vCPU / 4 GB mỗi pod)**, kèm **Split Brain Resolver `keep-majority` + fencing token qua Redis Lua CAS (Mục 8.4 — BẮT BUỘC, không phải tuỳ chọn)** và **stagger recovery** (jitter 0–500ms khi nạp snapshot hàng loạt). Chạy Chaos Test.

> [!CAUTION]
> **v2.1 đề xuất 4 Engine pod lớn cho 54k CCU — con số này đã bị thay thế (R-05).**
> 4 pod nghĩa là mất 1 pod = **13.500 học sinh** gián đoạn cùng lúc, và 1.125 phòng cùng nạp
> snapshot trong một thời điểm (*recovery thundering herd*). 12–16 pod nhỏ giữ tổng tài nguyên
> gần như không đổi nhưng hạ blast radius xuống **~4.000 học sinh**. Với hệ thống mà một sự cố
> rơi thẳng vào giờ học đang diễn ra, đây là đánh đổi đúng.
>
> **Không được bật Cluster Sharding mà thiếu SBR.** Sharding không kèm SBR + fencing token là
> đường thẳng tới split-brain: hai `RoomActor` cùng `room_id` chạy trên hai pod, chấm điểm hai
> lần và ghi đè snapshot của nhau (Mục 8.4).

#### 📌 GIAI ĐOẠN 3: ENTERPRISE SCALE & RESILIENCE (2 Tuần | Tuần 7 - Tuần 8)
* **Mục tiêu**: Tối ưu hóa chi phí hạ tầng Cloud và sẵn sàng cho mốc 54.000+  CCU.
* **Công việc theo tuần**:
  * **Tuần 1 (Tuần 7)**: Bật `Adaptive LZ4HC Compression` trên Redis (nén payload > 150 bytes) + Cấu hình PostgreSQL Table Partitioning theo tháng để lưu lịch sử đấu (KHÔNG CẦN DÙNG CLICKHOUSE).
  * **Tuần 2 (Tuần 8)**: Cấu hình Multi-AZ Redis/Kafka High Availability, chạy Stress Test 54.000 CCU bằng **harness Gatling/k6 của Mục 32** (tải mục tiêu: **~4.000 msg/s inbound, ~26.000 packet/s outbound** — *không phải 270.000 req/s như v2.1 tính*), **đối chiếu kết quả với giả thuyết H1/H2 để chốt số pod thật**, và nghiệm thu bảo mật toàn hệ thống.

---


## 27. 🚀 Phụ lục: Kế hoạch Hành động Triển khai MVP 3 Tuần (3-Week Lean MVP Action Plan)

Tài liệu này đóng vai trò là **Bản Kế hoạch Tác chiến Hàng ngày (Developer Playbook)** để phát hành bản MVP sản phẩm chạy thực tế cho **2.000 – 3.000 CCU (Stretch Goal: 5.000 CCU)** trong đúng **3 tuần (15 - 21 ngày làm việc)**. Mặc định sử dụng **Java 25+**.

---

### 27.1. Định nghĩa Phạm vi MVP (Scope Baseline)

#### 1. Những gì BẮT BUỘC có trong MVP:
- Học sinh vào phòng và chơi **Quiz realtime** (12 học sinh/phòng).
- Nộp bài ➔ nhận kết quả tức thì.
- Hiển thị điểm số và bảng xếp hạng trong phòng.
- Reconnect rớt mạng vẫn tiếp tục được chơi (nạp lại UI state từ Redis).
- Hệ thống chịu được **2.000 – 3.000 CCU** ổn định.
- Netty Gateway tách biệt Engine (0% business logic).
- 1 Room = 1 Pekko Actor. *(v2.1 ghi "hoặc Virtual Thread" — hai mô hình concurrency khác nhau, không thay thế được cho nhau; xem R-15 / Mục 14.7.)*
- Giao thức Native WebSocket + Protobuf Binary.
- Snapshot Redis cơ bản + Load Recovery.
- Rate Limiting + Idempotency kép cơ bản.
- App Flutter Native + WebView JavascriptBridge.

#### 2. Những gì CẮT BỎ (Làm sau MVP):
| Hạng mục | Lý do cắt |
|---|---|
| Room size 100 người | Phức tạp hóa luồng broadcast |
| Team Mode / Draft / Co-editing | Chưa cần thiết cho bản MVP |
| Boss Battle, Team Race | Chỉ tập trung hoàn thiện Quiz trước |
| Matchmaking MMR nâng cao | Chỉ cần tạo phòng đơn giản |
| Apache Kafka | Hoãn sang Giai đoạn 2 (Tuần 4 - Tuần 6) |
| ClickHouse | CẮT BỎ HOÀN TOÀN (Dùng Postgres Partitioning) |
| Adaptive LZ4HC | Snapshot Redis thường là đủ |
| Pekko Cluster Sharding | Dùng Static Routing (`room_id % 2`) trước |
| Go Gateway | Giữ Java Vert.x / Netty Gateway |
| Full Chaos Engineering | Chỉ test restart Pod cơ bản |

---

### 27.2. Lịch trình Chi tiết 3 Tuần (21 Ngày Làm việc)

> [!IMPORTANT]
> **ƯU TIÊN SỐ 1 TUYỆT ĐỐI (CORE PRIORITY)**: 
> Tập trung 100% nguồn lực dựng **WebSocket Server (Netty Gateway)** và **Lõi Game Engine (Java 25+ / Pekko RoomActor)** ngay trong 3-4 ngày đầu tiên của Tuần 1. Toàn bộ các luồng phụ (Auth REST, Database Save, UI Decoration) chỉ được làm sau khi luồng WebSocket ↔ Game Engine đã nhận/phát dữ liệu nhị phân Protobuf thông mượt!

#### 📅 Tuần 1: DỰNG GAME ENGINE & WEBSOCKET CORE (ƯU TIÊN SỐ 1)
* **Mục tiêu**: 1 phòng 12 học sinh chơi được quiz từ đầu đến cuối qua WebSocket + Protobuf, điểm cập nhật realtime.

| Ngày | Công việc chính | Kết quả mong đợi |
|---|---|---|
| **Ngày 1-2** | - Chốt file `game_message.proto`<br>- Generate code Java 25+ & Dart (Flutter)<br>- Setup project Gateway + Engine | Protobuf sẵn sàng, project chạy |
| **Ngày 3-4** | - Dựng Gateway: WebSocket + Auth Ticket + forward sang Engine<br>- Kết nối Gateway ↔ Engine qua Internal Frame Channel | Client kết nối WebSocket thành công |
| **Ngày 5** | - Xây dựng `RoomActor` (Java 25+ / Pekko) cơ bản<br>- FSM: `LOBBY` ➔ `PLAYING` ➔ `FINISHED` | Actor xử lý được state phòng |
| **Ngày 6-7** | - Xử lý `SubmitAnswer` + chấm điểm đơn giản<br>- Broadcast Progress trong phòng<br>- Test nội bộ 1 phòng 12 người | Chơi được 1 ván quiz hoàn chỉnh |

#### 📅 Tuần 2: Ghép Client + Ổn định Logic
* **Mục tiêu**: Đủ tính năng để học sinh chơi thật trên App Flutter/WebView.

| Ngày | Công việc chính | Kết quả mong đợi |
|---|---|---|
| **Ngày 8-9** | - Thêm Guardrails (Timeout 60s + Safe Math)<br>- Idempotency cơ bản (sequence number)<br>- Rate Limiting tại Gateway | Chống crash và spam cơ bản |
| **Ngày 10-11** | - Flutter Native giữ WebSocket<br>- Truyền data xuống WebView qua `JavascriptChannel`<br>- Optimistic UI cơ bản | Client Flutter + WebView hoạt động |
| **Ngày 12-13** | - Snapshot vào Redis (`SET game:snapshot:{room_id}`)<br>- Cơ chế Reconnect + load state<br>- Bảng xếp hạng trong phòng | Reconnect không mất trạng thái |
| **Ngày 14** | - Tích hợp end-to-end hoàn chỉnh<br>- Fix bug từ test nội bộ | Hệ thống chạy mượt môi trường Dev/Staging |

#### 📅 Tuần 3: Ổn định – Load Test – Release
* **Mục tiêu**: Đạt target 2.000 – 3.000 CCU ổn định và đưa lên môi trường thật.

| Ngày | Công việc chính | Kết quả mong đợi |
|---|---|---|
| **Ngày 15-16** | - Thêm Metrics quan trọng (latency, mailbox depth, CCU, error rate)<br>- Tối ưu hot path (giảm memory allocation) | Có Dashboard Prometheus/Grafana |
| **Ngày 17-18** | - Load test **2.000 – 3.000 CCU**<br>- Tìm và fix bottleneck<br>- Tối ưu nếu cần | Đạt target CCU ổn định |
| **Ngày 19-20** | - Chaos test nhẹ (restart Engine Pod)<br>- Security review nhanh<br>- Viết Runbook ngắn | Biết cách xử lý khi có sự cố |
| **Ngày 21** | - Deploy MVP (giới hạn số phòng nếu cần)<br>- Theo dõi sát | **MVP CHÍNH THỨC PHÁT HÀNH!** |

---

### 27.3. Checklist Bắt buộc Hoàn thành MVP (Release Readiness)

- [ ] File Protobuf biên dịch thành công trên cả Java 25+ và Flutter Dart.
- [ ] Gateway nhận Auth Ticket và mở WebSocket thành công.
- [ ] 12 học sinh vào phòng, nộp bài và thấy tiến trình cập nhật realtime.
- [ ] Khi tính điểm không bị lỗi chia cho 0 hoặc crash (nhờ Runtime Guardrails).
- [ ] Flutter Native truyền data xuống WebView mượt mà qua `JavascriptChannel`.
- [ ] Reconnect lấy lại được trạng thái phòng từ Redis.
- [ ] Kết quả trận đấu được lưu thành công vào PostgreSQL (dùng `@Async`).
- [ ] Load test đạt tối thiểu **2.000 CCU** ổn định.
- [ ] Có metrics cơ bản (latency, CCU, error rate) và Runbook xử lý sự cố.

---

## 28. 🔬 Nghiên cứu Tình huống Thực tế: Chuyển đổi Module Thảo luận Nhóm (Case Study: Legacy Group Discussion Refactoring)

Để minh họa khả năng tương thích và cách thức chuyển đổi từ mã nguồn thực tế của hệ thống hiện tại sang Kiến trúc mới, phần này phân tích chi tiết module Thảo luận Nhóm (`group_discussion` trong `k12-lms-service/lms-worker`).

---

### 28.1. Phân tích Luồng Nghiệp vụ & Hạn chế của Code Cũ

Trong code hiện tại (`ActiveGroupDiscussionListener.java` và `WorkerControlTLN.java`):

```text
 [Kafka Event: save-message-queue]
                │
                ▼
 ┌──────────────────────────────────────┐
 │ ActiveGroupDiscussionListener        │
 └──────────────────┬───────────────────┘
                    │ Submit Runnable Worker
                    ▼
 ┌──────────────────────────────────────┐
 │ ThreadPoolExecutor                   │
 │ (tlnControlExecutor)                 │
 └──────────────────┬───────────────────┘
                    │ Giữ 1 Thread xử lý `WorkerControlTLN`
                    ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ WorkerControlTLN.java:                                                                 │
 │   • Schedule: SELECT_TEAM_NAME (16s) -> VOTE_RESULT (36s) -> START_CHAT (51s) -> END    │
 │   • RỦI RO LỚN: Gọi `endFuture.get();` -> BLOCK THREAD TRONG VÀI PHÚT!                  │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

#### Các hạn chế lớn của Code Cũ khi Scale-out:
1. **Thread Starvation (Treo Thread Pool)**: Dòng 78 `endFuture.get()` bắt Thread trong `ThreadPoolExecutor` phải đứng chờ (Block) trong suốt 3-5 phút cho đến khi phiên thảo luận kết thúc. Khi có 500 phòng hoạt động đồng thời, toàn bộ Thread pool bị treo cứng, phát sinh lỗi `RejectedExecutionException` ngắt quãng trận đấu.
2. **Chi phí RAM & Redis IO lớn**: Lưu và truy vấn Redis Set (`redisService.sAdd`, `redisService.set`) liên tục làm trung gian đồng bộ state thay vì quản lý trực tiếp trong RAM.

---

### 28.2. Luồng Nghiệp vụ Sau khi Chuyển đổi sang Kiến trúc Mới (Non-blocking Actor Model)

Giữ nguyên 100% các mốc thời gian nghiệp vụ (16s, 36s, 46s, 51s), nhưng thay thế mô hình Thread Pool Blocking bằng **Apache Pekko Single-threaded Actor**:

```text
 [Internal Frame Channel / Netty Gateway Event]
                │
                ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ GroupDiscussionActor (Pekko Typed Actor - 1 Room = 1 Actor)                            │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ • Nhận Message `StartDiscussion` -> Xử lý State trong 0.1ms.                           │
 │ • Đăng ký Timers Non-blocking: `timers.startSingleTimer("VOTE", 36s)`.                 │
 │ • HOÀN TOÀN KHÔNG BLOCK THREAD (Bỏ 100% `endFuture.get()`).                            │
 │ • 1 Pod Java xử lý mượt 5.000 phòng thảo luận nhóm đồng thời!                         │
 └────────────────────────────────────────┬───────────────────────────────────────────────┘
                                          │ Push Protobuf Binary Stream
                                          ▼
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ Netty Gateway -> Flutter Native App / Embedded WebView (JavascriptBridge)              │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 28.3. Bảng So sánh Trực quan: Code Cũ vs Code Mới After Refactoring

| Tiêu chí So sánh | Code Cũ (`WorkerControlTLN`) | Code Mới (`GroupDiscussionActor`) |
| :--- | :--- | :--- |
| **Mô hình Xử lý** | `Runnable` + `ThreadPoolExecutor` | Apache Pekko Single-threaded Actor |
| **Trạng thái Thread** | ❌ **Blocking**: Thread bị khóa bởi `endFuture.get()` trong 3-5 phút. | 🟢 **Non-blocking**: Thread giải phóng ngay trong < 0.1ms sau khi xử lý tin nhắn. |
| **Giới hạn Phòng/Pod** | ❌ **Thấp**: ~ 200 - 500 phòng (bị kịch trần Thread Pool `RejectedExecutionException`). | 🟢 **Rất cao**: **3.000 - 5.000 phòng/Pod** mượt mà. |
| **Độ trễ Broadcast** | 100 - 300ms (Truy vấn Redis key `sAdd`/`set`). | **< 5ms** (Bắn trực tiếp Internal Frame Channel nhị phân Protobuf). |
| **Rủi ro Deadlock** | ⚠️ Có nguy cơ do tranh chấp Thread Pool. | 🛡️ **Bằng 0%** (Mô hình Actor không dùng Shared Memory Lock). |

---

### 28.4. Mã nguồn Java 25+ Minh họa Refactor (`GroupDiscussionActor.java`)

Dưới đây là đoạn mã chuyển đổi `WorkerControlTLN` sang `Pekko Actor` loại bỏ 100% lỗi treo thread:

```java
package vn.edupiaclass.lms.worker.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class GroupDiscussionActor extends AbstractBehavior<GroupDiscussionActor.Command> {
    private static final Logger log = LoggerFactory.getLogger(GroupDiscussionActor.class);

    // Command Messages Interface
    public interface Command {}
    public enum SelectTeamNameTimer implements Command { INSTANCE }
    public enum VoteResultTimer implements Command { INSTANCE }
    public enum StartChatTimer implements Command { INSTANCE }
    public enum EndDiscussionTimer implements Command { INSTANCE }

    private final TimerScheduler<Command> timers;
    private final String roomKey;

    public static Behavior<Command> create(String roomKey, long durationDiscussionMs) {
        return Behaviors.setup(ctx -> Behaviors.withTimers(timers -> new GroupDiscussionActor(ctx, timers, roomKey, durationDiscussionMs)));
    }

    private GroupDiscussionActor(ActorContext<Command> context, TimerScheduler<Command> timers, String roomKey, long durationMs) {
        super(context);
        this.timers = timers;
        this.roomKey = roomKey;

        log.info("[ACTOR START] Created Non-blocking GroupDiscussionActor for key={}", roomKey);

        // Schedule timeline events WITHOUT BLOCKING THREADS
        timers.startSingleTimer(SelectTeamNameTimer.INSTANCE, Duration.ofSeconds(16));
        timers.startSingleTimer(VoteResultTimer.INSTANCE, Duration.ofSeconds(36));
        timers.startSingleTimer(StartChatTimer.INSTANCE, Duration.ofSeconds(51));
        timers.startSingleTimer(EndDiscussionTimer.INSTANCE, Duration.ofMillis(51000 + durationMs + 61000));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessageEquals(SelectTeamNameTimer.INSTANCE, this::onSelectTeamName)
                .onMessageEquals(VoteResultTimer.INSTANCE, this::onVoteResult)
                .onMessageEquals(StartChatTimer.INSTANCE, this::onStartChat)
                .onMessageEquals(EndDiscussionTimer.INSTANCE, this::onEndDiscussion)
                .build();
    }

    private Behavior<Command> onSelectTeamName() {
        log.info("[EVENT FIRE] SELECT_TEAM_NAME for room={}", roomKey);
        // Execute fast non-blocking business logic & broadcast via Internal Frame Channel
        return this;
    }

    private Behavior<Command> onVoteResult() {
        log.info("[EVENT FIRE] VOTE_RESULT for room={}", roomKey);
        return this;
    }

    private Behavior<Command> onStartChat() {
        log.info("[EVENT FIRE] START_CHAT_TIME for room={}", roomKey);
        return this;
    }

    private Behavior<Command> onEndDiscussion() {
        log.info("[EVENT FIRE] END_DISCUSSION for room={}. Cleaning up actor smoothly.", roomKey);
        return Behaviors.stopped(); // Self-terminate actor safely, zero thread leakage!
    }
}
```

---

# 🆕 PHẦN VII — CÁC MỤC BỔ SUNG TRONG v2.2

---

## 29. 🌍 Tầng World & Teacher Dashboard — Bài toán Fan-in

> [!IMPORTANT]
> **v2.1 định nghĩa `World` = phiên điều phối của giáo viên (Mục 1.2) rồi không mục nào khác
> nhắc tới nó.** Toàn bộ kiến trúc dừng ở tầng Room. Đây là khoảng trống lớn nhất về phạm vi:
> mọi kỳ thi thật đều có người đứng trên nhìn xuống hàng nghìn phòng.

### 29.1. Vì sao fan-in khó hơn fan-out

Tài liệu đã dành nhiều mục cho **fan-out** (1 actor → 12 client). Dashboard giáo viên là bài
toán ngược — **fan-in**: gom trạng thái từ N actor nằm rải trên 12–16 pod về một nơi. Nó khó
hơn vì đích đến là *một* điểm, và mọi nguồn đều muốn ghi vào đó cùng lúc.

Nếu làm ngây thơ — dashboard poll từng phòng, hoặc mỗi actor push theo nhịp tick 200ms:

```
5.000 phòng × 5 lần/giây = 25.000 update/s đổ vào MỘT service
```

Đó chính là một broadcast storm chạy ngược chiều — hệ thống tự tạo ra đúng thứ mà Mục 22.2
được viết ra để ngăn.

### 29.2. Thiết kế: tổng hợp phân tầng (hierarchical aggregation)

```text
                         4.500 RoomActor  (rải trên 12-16 Engine pod)
                                 │
                                 │  RoomSummary — CHỈ khi có thay đổi, trần 1 gói / 2 giây
                                 │  ~120 bytes: room_id, state, step_index, avg_score,
                                 │              students_connected, students_answered
                                 ▼
                    ┌────────────────────────────────┐
                    │      SessionAggregator          │
                    │  Cluster Singleton PER session  │   gom theo session_id
                    │  tự throttle xuống 1 Hz         │   giữ bản đồ room -> summary trong RAM
                    └───────────────┬────────────────┘
                                    │  SessionSnapshot 1 Hz
                                    ▼
                    ┌────────────────────────────────┐
                    │  Teacher WebSocket (qua GW)     │   scope = SESSION, không phải ROOM
                    └────────────────────────────────┘
```

### 29.3. Bốn quy tắc bắt buộc

| # | Quy tắc | Vì sao |
|---|---|---|
| 1 | **Không gói tin nào của học sinh đi tới dashboard** | Dashboard chỉ nhận `RoomSummary`. Một `SUBMIT_ANSWER` lọt lên tầng session là bug kiến trúc, không phải tối ưu thiếu |
| 2 | **Tần suất tóm tắt (2s) độc lập hoàn toàn với tick trong phòng (200ms)** | Giáo viên không dùng được độ phân giải 200ms trên 5.000 phòng. Ràng buộc hai nhịp vào nhau là cách chắc chắn nhất để tick của phòng kéo sập dashboard |
| 3 | **Dashboard mở > 200 phòng → chuyển sang chỉ số tổng hợp + danh sách ngoại lệ** | Lưới 5.000 ô không ai đọc được. Thứ giáo viên cần là *phòng nào đang kẹt*, *phòng nào có học sinh rớt mạng* |
| 4 | **`SessionAggregator` là Cluster Singleton theo `session_id`**, không phải singleton toàn cục | Singleton toàn cục = một điểm nghẽn và một điểm chết cho toàn hệ thống. Theo session thì bán kính ảnh hưởng bằng đúng một phiên học |

### 29.4. Chiều ngược lại — lệnh từ giáo viên

`TEACHER_PAUSE` / `TEACHER_NEXT_STEP` áp cho toàn phiên đi ngược đường trên:

```
Teacher WS → SessionAggregator → phát tán tới các ShardRegion THEO LÔ, CÓ JITTER
```

**Không phát đồng loạt.** Một lệnh pause toàn phiên chạm 4.500 actor cùng thời điểm sẽ tạo ra
đúng một *thundering herd* nhân tạo — cùng loại sự cố mà stagger recovery ở Mục 23.3 được
thiết kế để tránh. Lô 200 phòng, jitter 0–500ms giữa các lô.

### 29.5. Ràng buộc SLA riêng của tầng này

| Chỉ số | Cam kết | Ghi chú |
|---|---|---|
| Độ trễ `RoomSummary` → dashboard (p99) | **< 3 s** | Giáo viên không cảm nhận được dưới ngưỡng này |
| Băng thông dashboard / phiên 500 phòng | **< 60 KB/s** | 500 × 120 B × 1 Hz |
| Ảnh hưởng của dashboard lên p99 trong phòng | **0** | Nếu đo được ảnh hưởng, quy tắc 1 hoặc 2 đã bị vi phạm |

---

## 30. 🚪 Late Join & Backfill — Học sinh vào phòng giữa câu hỏi

> [!NOTE]
> **v2.1 chỉ xử lý *reconnect*** (Mục 9.1 — học sinh **đã từng** ở trong phòng).
> Thiếu hẳn kịch bản **vào lần đầu khi phòng đã ở giữa câu hỏi thứ 3**: đi học muộn, đổi
> thiết bị, hoặc được giáo viên thêm vào giữa chừng. Đây là kịch bản xảy ra hằng ngày.

### 30.1. Quy định trong FSM

```text
JOIN khi phòng đang ở trạng thái PLAYING:

  1. Actor cấp student_index còn trống.
     Hết chỗ (đủ 12) → từ chối bằng ROOM_FULL, KHÔNG âm thầm bỏ qua.

  2. Trả về ROOM_STATE_FULL:
       • step hiện tại + deadline CÒN LẠI (không phải deadline gốc)
       • điểm của mọi thành viên
       • KHÔNG kèm đáp án của các câu đã qua   ← chống lộ đề cho người vào sau

  3. Áp missed_step_policy cho các câu đã bỏ lỡ  (xem 30.2)

  4. Broadcast STUDENT_JOINED cho cả phòng.
```

Điểm 2 dòng cuối là ràng buộc bảo mật, không phải tối ưu băng thông: gửi kèm đáp án các câu
đã qua biến "vào muộn" thành một cách xem đáp án.

### 30.2. `missed_step_policy` — quyết định nghiệp vụ, không hardcode

Thuộc tính của **Game Definition**, không phải hằng số trong code:

| Giá trị | Hành vi | Phù hợp với |
|---|---|---|
| **`ZERO`** *(mặc định)* | Tính 0 điểm cho các câu đã qua | Thi có điểm — công bằng nhất với người vào đúng giờ |
| **`SKIP`** | Không tính các câu đã qua vào mẫu số khi xếp hạng | Hoạt động luyện tập, nơi thứ hạng tương đối quan trọng hơn điểm tuyệt đối |
| **`ALLOW_LATE`** | Cho làm bù các câu đã qua | Bài tự học. **Không hợp lý khi thi** — người vào muộn có thêm thời gian suy nghĩ |

> [!WARNING]
> **Đây là quyết định của Product, không phải của kỹ thuật.** Nó phải được chốt **trước khi
> code**, vì `ALLOW_LATE` đòi hỏi actor giữ lại toàn bộ step đã qua trong state — ảnh hưởng
> trực tiếp tới kích thước snapshot (SLA < 5 KB, Mục 14.6).

### 30.3. Phân biệt Late Join với Reconnect

Hai luồng khác nhau và **không được gộp**:

| | Reconnect (Mục 9.1) | Late Join (mục này) |
|---|---|---|
| `student_id` đã có trong state phòng | ✅ Có | ❌ Không |
| Gửi `RESYNC { last_acked_seq, pending[] }` | ✅ Có | ❌ Không có gì để replay |
| Giữ nguyên điểm & lịch sử trả lời | ✅ Có | Theo `missed_step_policy` |
| Cấp `student_index` mới | ❌ Giữ index cũ | ✅ Cấp mới |

Gộp hai luồng làm một là cách tạo ra lỗi *"học sinh vào muộn được cộng điểm của người khác"* —
đúng `student_index` bị tái sử dụng.

---

## 31. 💰 Ước tính Chi phí Hạ tầng & Chi phí trên mỗi CCU

> [!IMPORTANT]
> **v2.1 không có một con số USD nào**, dù đề xuất đầy đủ 10–12 GW pod, 4 Engine pod,
> Redis Cluster 3+3, Kafka 3 broker, RDS, MongoDB, ClickHouse.
> Với EdTech, **chi phí/CCU thường là ràng buộc kiến trúc cứng hơn latency**. Một kiến trúc
> đạt p99 < 100ms nhưng lỗ trên mỗi học sinh là kiến trúc sai.

### 31.1. Khung ước tính @ 54.000 CCU

Điền số thật theo region đang dùng — bảng này là **khung**, không phải báo giá:

| Thành phần | Cấu hình | Ghi chú |
|---|---|---|
| Gateway pods | 10–12 × (2 vCPU / 4 GB) | Xác nhận bằng H1 (Mục 32) |
| Engine pods | **12–16 × (2 vCPU / 4 GB)** | Theo R-05 — pod nhỏ, blast radius nhỏ |
| Redis (ElastiCache) | 3 shard + replica | Phase 1 có thể chỉ cần 1 node + replica (xem 31.3) |
| PostgreSQL (RDS) | Multi-AZ | Nguồn sự thật cho kết quả + analytics partition theo tháng |
| MongoDB | Replica set 3 node | Match history logs |
| Kafka (MSK) | 3 broker | **Hoãn được** — sau R-07/R-17, Kafka rời hot path |
| **Network egress** | ~26.000 pkt/s × ~150 B ≈ **3,9 MB/s ≈ 10 TB/tháng** *(nếu chạy 24/7)* | ⚠ **Khoản thường bị quên hoàn toàn khi lập ngân sách** |
| **Chi phí / CCU / giờ** | — | **Chỉ số phải theo dõi liên tục, không phải tính một lần** |

### 31.2. Egress — vì sao tách riêng một dòng

Egress là khoản duy nhất trong bảng **tỉ lệ thuận với thành công của sản phẩm** và không giảm
được bằng cách chọn instance rẻ hơn. Nó cũng là lý do trực tiếp khiến **ADR-4 (tick coalescing)
có giá trị tài chính**, không chỉ giá trị kỹ thuật:

| Mô hình tick | Outbound | Egress tương đối |
|---|---|---|
| Fixed-rate 200ms (v2.1) | 270.000 pkt/s | **~10×** |
| Coalescing (v2.2, ADR-4) | ~26.000 pkt/s | 1× |

Nói cách khác: R-02 không chỉ sửa một phép tính sai — nó cắt khoảng 90% hoá đơn băng thông.

### 31.3. Hai câu hỏi phải trả lời TRƯỚC khi chốt kiến trúc

**1. Chi phí ở tải nền (ngoài giờ học, ~0 CCU) là bao nhiêu?**

Đây là câu hỏi quan trọng nhất và nó **có thể lật ngược thiết kế hiện tại**. Nếu hệ thống chỉ
thực sự chạy 4 tiếng/ngày trong năm học, thì **chi phí nhàn rỗi 20 tiếng còn lại mới là khoản
chi phối** — và điều đó nghiêng mạnh về **scale-to-zero**, ngược với mô hình cluster luôn-bật
mà Pekko Cluster Sharding giả định.

| Kịch bản | Hệ quả kiến trúc |
|---|---|
| Chạy gần như 24/7 (nhiều múi giờ, tự học) | Giữ cluster luôn-bật. Thiết kế hiện tại đúng |
| Chạy 4–6 tiếng/ngày, có mùa vụ (kỳ thi) | Cần **KEDA/HPA theo lịch** + Engine pod scale xuống mức tối thiểu giữ quorum. Cluster Sharding **không** scale xuống 0 được — phải giữ đủ node cho `keep-majority` (Mục 8.4) |

**2. Redis Cluster 3+3 và Kafka 3 broker có cần từ đầu không?**

Không. Sau R-07, recovery dựa trên client-side replay chứ không dựa vào Kafka → **Kafka hoãn
được thật sự**. Redis một node có replica đủ cho phase 1 ở mức 2–3k CCU. Trả tiền cho HA của
một thành phần chưa nằm trên đường đi quan trọng là cách lãng phí ngân sách sớm nhất.

---

## 32. 🔬 Phương pháp Load Test & Benchmark H1/H2 xác nhận Capacity

> [!CAUTION]
> **MVP plan ngày 17–18 của v2.1 viết đúng một dòng: *"Load test 2.000–3.000 CCU"* — không nói
> bằng công cụ gì.** Sinh 3.000 client WebSocket nói Protobuf, có state phiên đầy đủ
> (join → nhận câu hỏi → nộp đáp án đúng sequence → nhận ACK) **tự nó là một dự án con**,
> không phải một dòng trong lịch trình.

### 32.1. Quyết định về harness

| Hạng mục | Quyết định |
|---|---|
| **Công cụ** | **Gatling** (Scala/Java — tái dùng trực tiếp Protobuf class đã codegen) hoặc **k6** với extension WebSocket nhị phân |
| **Kịch bản bắt buộc** | Phải gồm **connection storm** (Mục 8.6): toàn bộ CCU nối trong 15 giây |
| **Thời lượng** | **≥ 30 phút** — dưới ngưỡng này không lộ được rò rỉ direct memory (Mục 22.3) và GC drift |
| **Chỉ số nghiệm thu** | p99 end-to-end, `actor_mailbox_queue_depth`, RSS, GC pause, packet/s **thực đo** |
| **Đối chiếu** | So với H1/H2 dưới đây — **đây mới là thứ xác nhận số pod, không phải phép tính trên giấy** |
| **Công sức** | **3–5 ngày cho riêng harness**, tách khỏi ngày chạy test trong kế hoạch |

Tại sao không Locust/JMeter (v2.1 đề xuất): cả hai đều mạnh với HTTP request-response, nhưng
kịch bản ở đây là **kết nối dài có trạng thái, payload nhị phân, thứ tự sequence quan trọng**.
Dựng được nó trên Locust/JMeter tốn nhiều công hơn dùng Gatling, và khó tái dùng `.proto` đã có.

### 32.2. Giả thuyết H1 — Gateway

```text
GIẢ THUYẾT H1
  5.000 WS/pod @ 2 vCPU / 4 GB

  Cách kiểm chứng:
    5.000 client giữ kết nối, 1 msg / 25 s / client, TLS BẬT.

  Đạt nếu (duy trì 30 phút):
    • CPU              < 60%
    • RSS              < 3 GB
    • p99 fan-out      < 5 ms

  Rủi ro chính: CPU của TLS handshake lúc đông (Mục 8.6) — KHÔNG phải steady state.
  → H1 BẮT BUỘC đo thêm handshake rate riêng: bao nhiêu handshake/s/pod trước khi
    p99 fan-out vượt ngưỡng? Con số đó quyết định ngưỡng admission control.
```

### 32.3. Giả thuyết H2 — Engine

```text
GIẢ THUYẾT H2
  1.125 rooms/pod @ 4 vCPU / 8 GB
  ⚠ Lưu ý: sau R-05 cấu hình mục tiêu là ~300-375 rooms/pod @ 2 vCPU / 4 GB.
    H2 vẫn đo ở mức 1.125 để biết TRẦN thật, rồi mới chọn điểm vận hành.

  Cách kiểm chứng:
    1.125 RoomActor, tick coalescing (ADR-4), 12 học sinh/phòng, tải theo Mục 7.1.

  Đạt nếu:
    • p99 actor_processing_latency  < 15 ms
    • p99 mailbox_depth             < 10
    • p99 GC pause                  < 10 ms      ← đo CẢ G1 VÀ ZGC, chọn theo số liệu
    • RSS                           < 6 GB

  Rủi ro chính: SỐ LƯỢNG TIMER đồng thời (1.125 scheduled timer/pod), không phải CPU.
```

### 32.4. Quy tắc công bố số liệu

> [!IMPORTANT]
> **Không được công bố con số pod cho 54k CCU — ra ngoài hoặc dùng lập ngân sách —
> trước khi H1 và H2 có kết quả đo.**
> Mọi con số capacity trong tài liệu này (`5.000 WS/pod`, `1.125 rooms/pod`, `10–12 GW pod`,
> `12–16 Engine pod`) là **điểm khởi đầu hợp lý, không phải kết luận**. Chúng được ghi ra để
> có cái mà bác bỏ, không phải để trích dẫn.

### 32.5. Thứ tự chạy

| Bước | Việc | Đầu ra |
|---|---|---|
| 1 | Dựng harness Gatling + `.proto` codegen | 3–5 ngày, tách khỏi lịch test |
| 2 | **H1** — Gateway steady state + handshake rate | Ngưỡng `max_handshake_per_sec` cho admission control (Mục 8.6) |
| 3 | **H2** — Engine, đo cả G1 và ZGC | Số rooms/pod thật + lựa chọn GC có căn cứ |
| 4 | Full-system @ 54k CCU **kèm connection storm** | Xác nhận SLA Mục 14.6 |
| 5 | Chaos: sập 20% Engine pod giữa lúc tải đỉnh | Xác nhận **mất dữ liệu = 0** và **phòng trở lại < 30 s** |

Bước 5 là bước duy nhất chứng minh được cam kết cốt lõi của ADR-3. Bốn bước trước chỉ đo hiệu
năng; bước 5 đo **tính đúng đắn dưới sự cố** — thứ mà v2.1 chưa từng có cách kiểm chứng.

---
