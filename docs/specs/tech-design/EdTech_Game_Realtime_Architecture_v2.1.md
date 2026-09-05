# 🎯 TÀI LIỆU KIẾN TRÚC HỆ THỐNG GAME THỜI GIAN THỰC (v2.1)
## High-Concurrency EdTech Realtime Multiplayer Game Platform (Target: 54.000 CCU)

> **Mặc định Nền tảng**: Java 25+ (Generational ZGC & Compact Object Headers) | Apache Pekko Typed Actor | Netty/Vert.x Gateway | Native WebSocket + Protobuf Binary | Flutter Native + Embedded WebView Bridge | K8s CGroup v2 Native

---

## 📌 MỤC LỤC TỔNG QUAN KIẾN TRÚC (MASTER TABLE OF CONTENTS)

### 🏛️ PHẦN I: TỔNG QUAN HỆ THỐNG & ĐỊNH HƯỚNG KIẾN TRÚC
* **Mục 1**: Tổng quan Dự án, Phân cấp Định danh & Lý do Chọn Tech Stack (Java 25+ vs Golang)
* **Mục 2**: Sơ đồ Kiến trúc Master v3.0 & Phân tách Thành phần Chi tiết
* **Mục 3**: Hệ sinh thái Dịch vụ Trò chơi (AWS Game Tech Patterns: Matchmaking, Leaderboards, Profile)

### ⚡ PHẦN II: THIẾT KẾ KỸ THUẬT CỐT LÕI & LUỒNG DỮ LIỆU REALTIME
* **Mục 4**: Quy chuẩn Giao tiếp Protobuf Schema Specification (`game_message.proto`)
* **Mục 5**: Mô hình Thiết kế Cốt lõi (Catalog Patterns) & Lifecycle Finite State Machine (FSM)
* **Mục 6**: Sơ đồ Chuỗi Vòng đời Game Hoàn chỉnh (Matchmaking ➔ Finished)
* **Mục 7**: Tối ưu Hiệu năng & Phân tích Con Số Vàng 200ms Tick Aggregation Window
* **Mục 8**: Quy chuẩn 5 Dạng Game Thực tế & Lifecycle Protocol 6 Bước Triển khai
* **Mục 9**: Domain-Driven Kafka Topic Modeling & Partitioning Strategy

### 🛡️ PHẦN III: AN TOÀN HỆ THỐNG, BẢO VỆ RUNTIME & KHÔI PHỤC SỰ CỐ
* **Mục 10**: Quy trình Xác thực WebSocket Auth Protocol (One-Time Ticket Auth & Channel Attributes)
* **Mục 11**: Bảo mật, Chống chịu Lỗi (Fault Tolerance) & Phục hồi Sự cố (< 50ms Recovery SLA)
* **Mục 12**: Cơ chế Bảo vệ Runtime Guardrails chống Lỗi Logic trong Game Definition
* **Mục 13**: Chiến lược Chống Treo Thread & Kiểm Soát Fan-Out Broadcast
* **Mục 14**: Giải pháp Chống Đẩy Nhầm Room (Cross-Room Data Leakage Protection)
* **Mục 15**: Đánh giá Rủi ro Chi tiết & Chiến lược Mitigation (Points A ➔ F)

### 🚀 PHẦN IV: MỞ RỘNG QUY MÔ & TỐI ƯU HẠ TẦNG
* **Mục 16**: Mô hình Triển khai Đa Pod & Luồng Dữ liệu Thực tế (10 Gateway Pods, 4 Engine Pods)
* **Mục 17**: Phân tích Quy mô Phòng 100 Học sinh & 5 Bước Kỹ thuật Tối ưu
* **Mục 18**: Đánh giá Lựa chọn WebSocket Gateway (Vert.x vs Netty vs Spring WebFlux)
* **Mục 19**: Đánh giá Thay thế Socket.io sang Native WebSocket + Protobuf
* **Mục 20**: Kỹ thuật Hệ thống Nâng cao & Technical SLAs (Mailbox Metrics, Circuit Breakers)
* **Mục 21**: Tương thích & Tối ưu hóa Tuyệt đối với Kubernetes Container Environment

### 📱 PHẦN V: KIẾN TRÚC FRONTEND & NGHIÊN CỨU TÌNH HUỐNG THỰC TẾ
* **Mục 22**: Hướng dẫn Triển khai Tầng Frontend Client (Optimistic UI, Exponential Backoff, Clock Sync)
* **Mục 23**: Hướng dẫn Kiến trúc cho App Flutter & Nhúng WebView (`JavascriptChannel` Bridge)
* **Mục 24**: Nghiên cứu Tình huống Thực tế: Refactor Module Thảo luận Nhóm (`group_discussion`)

### 🗺️ PHẦN VI: ĐÁNH GIÁ KIẾN TRÚC & LỘ TRÌNH TRIỂN KHAI THỰC CHUYÊN
* **Mục 25**: Đánh giá Độc lập (Score 8.2/10 Integration) & 6 Tinh chỉnh Thực chiến
* **Mục 26**: Lộ trình Triển khai Phân kỳ Tinh gọn (Lean 2-3 Week Phase Roadmap | NO ClickHouse)
* **Mục 27**: Phụ lục: Kế hoạch Hành động Triển khai MVP 3 Tuần (Developer Playbook | Core Priority First)

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
   * **Netty**: Quản lý WebSocket & Smart Fan-out.
   * **Redis**: Quản lý State, Idempotency (SETNX) & Hot LZ4HC Snapshot.
   * **Kafka**: Durable Event Logging, Event Sourcing & Fast Replay Recovery.

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
   * Bài toán 1 Room = 1 Actor đơn luồng cần một Actor Framework chuẩn hóa (như Akka / Pekko trên JVM) hỗ trợ sẵn Mailbox Queue, Cluster Sharding, Rebalancing và FSM State Machine.
   * Nếu dùng Golang, lập trình viên sẽ phải **tự code tay** cơ chế Mailbox Channel, Mutex Locking và State Management cho từng phòng – rất dễ dẫn đến rủi ro **Goroutine Leak** hoặc **Deadlock** khi vận hành thực tế.

4. **Quản lý Bộ nhớ & Profiling Đẳng cấp Doanh nghiệp**:
   * Với Java 17+, bộ dọn rác **ZGC (Zero Pause GC)** giữ thời gian dừng (*Pause Time*) dưới **1ms** ngay cả khi Heap RAM phình to chứa hàng ngàn Actor In-Memory.
   * Hệ sinh thái công cụ đo đạc profiling chuyên sâu (*Async-Profiler, JFR - Java Flight Recorder, JProfiler*) giúp các kỹ sư kiểm soát chính xác từng byte bộ nhớ của 54.000 CCU (3x Target Peak).

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
                       │                                       │                     │ DIRECT gRPC        │
                       │                                       │                     ▼ Stream (< 1ms)     │
                       │                                       │  ┌─────────────────────────────────────┐  │
                       │                                       │  │ GAME SESSION ACTOR SERVICE (4 Pods) │  │
                       │                                       │  │ 1 Room = 1 Single-threaded Actor   │  │
                       │                                       │  │ FSM, Watchdog 10ms, Guardrails     │  │
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
  ├─────────────────────────┬──────────────────────────┬──────────────────────────┬───────────────┤
  │     REDIS CLUSTER       │         MONGODB          │        POSTGRESQL        │  CLICKHOUSE   │
  │ • Session Registry      │ • Match History Logs     │ • User Accounts & Auth   │ • Big Data    │
  │   (room_id -> engine_pod│ • Player Profile & Items │ • Class Hierarchy        │   Analytics & │
  │ • Adaptive LZ4 Snapshot │ • Unstructured Game Data │ • Curriculum Content     │   Reports     │
  │ • Idempotency (SETNX)   │                          │                          │               │
  │ • Global Leaderboards   │                          │                          │               │
  └─────────────────────────┴──────────────────────────┴──────────────────────────┴───────────────┘
```

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
Tầng này gồm 2 Service tách biệt giao tiếp **Trực tiếp qua gRPC Bi-directional Stream (< 1ms)**:

> [!IMPORTANT]
> **QUY TẮC KIẾN TRÚC NGHIÊM NGẶT (STRICT SEPARATION OF CONCERNS RULE)**:
> 1. Tầng WebSocket Gateway (Network Edge) **TUYỆT ĐỐI KHÔNG ĐƯỢC CHỨA BẤT KỲ BUSINESS LOGIC NÀO** (Không tính điểm, không kiểm tra luật game, không giữ state phòng). Tầng này là **Pure Stateless Pipe & Border Control**: Duy trì kết nối socket, xác thực JWT, Rate Limit, gRPC Pass-through mảng bytes Protobuf sang Game Engine, và Smart Fan-out broadcast bằng Zero-Copy `ByteBuf.retain()`.
> 2. **BỎ HOÀN TOÀN REDIS PUB/SUB KHỎI LUỒNG TIN NHẮN PHÒNG**: Mọi tin nhắn thời gian thực của phòng được đẩy trực tiếp qua gRPC Stream giữa Gateway và Engine Actor. Redis Pub/Sub CHỈ dùng cho thông báo bảo trì toàn hệ thống (`k12:sys:global`).

* **A. Netty Gateway Pool (10 - 12 Pods - Network Edge)**:
  * *Công nghệ*: Netty Native Epoll Transport.
  * *Trách nhiệm*: Quản lý $5.000$ WebSockets/Pod, Token Bucket Rate Limiting (5 req/s/client), kiểm soát **Netty High Watermark Backpressure** (Drop tin nhắn non-critical khi client mạng yếu), Smart Fan-out bằng Zero-Copy `ByteBuf.retainedDuplicate()`.
* **B. Game Session Actor Pool (4 Pods - Business Core)**:
  * *Công nghệ*: Java 25+ (Spring Boot 3.x / Akka Cluster / Project Loom Virtual Threads).
  * *Trách nhiệm*: Mô hình **1 Room = 1 Single-threaded Actor** ($1.125$ phòng/Pod). Triệt tiêu 100% Race Condition & Deadlock.
  * *Bảo vệ Runtime*: Trang bị **Watchdog Execution Time Budget (10ms Guard)** và **Bốn Rào Chắn An Toàn (Runtime Guardrails)** chống lỗi lặp vô tận, timeout bước chơi, và lỗi chia cho 0 từ Game Definition.

#### 4. Tầng Hàng đợi Sự kiện (Kafka Event Bus)
* **Công nghệ**: Apache Kafka Cluster (3 Brokers).
* **Trách nhiệm**: Đóng vai trò là *Durable Event Log* với 3 Nhóm Topic Domain (`game.events.submit`, `game.matchmaking.tickets`, `game.analytics.raw-answers`). Mọi tin nhắn nộp bài được partition theo `room_id` để đảm bảo thứ tự tuyệt đối $100\%$.

#### 5. Tầng Worker Bất đồng bộ (Async Workers)
* **Leaderboard Worker**: Consume sự kiện từ Kafka để ZADD xếp hạng toàn trường vào Redis.
* **Analytics & Audit Worker**: Consume các sự kiện nộp bài để ghi Batch Insert vào ClickHouse & MongoDB phục vụ báo cáo Big Data.

#### 6. Tầng Đa Dữ liệu (Shared Polyglot Data & Cache Layer)
* **Redis Cluster (In-Memory)**: Lưu **Session Registry** (`room_id -> engine_pod`), Adaptive LZ4 Snapshot nóng (`game:snapshot:{room_id}`), khóa chống trùng request (`SETNX`), Redis Sorted Sets (`ZADD`) cho Leaderboard.
* **MongoDB (NoSQL Document)**: Lưu trữ Lịch sử chi tiết từng trận đấu (Match History Logs).
* **PostgreSQL (Relational RDBMS)**: Lưu trữ Tài khoản, Phân quyền, Lớp học, Ngân hàng Câu hỏi.
* **ClickHouse (Columnar OLAP)**: Lưu trữ và phân tích Big Data báo cáo học tập.

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

## 4. 🌐 Tổng quan Kênh Giao tiếp (Communication Channels)

```text
 ┌───────────────────┬──────────────────────────┬─────────────────────────────┬────────────────────────────────────────────────────────┐
 │ Kênh Giao tiếp    │ Giao thức                │ Tầng tham gia               │ Mục đích sử dụng chính                                 │
 ├───────────────────┼──────────────────────────┼─────────────────────────────┼────────────────────────────────────────────────────────┤
 │ Control Traffic   │ HTTP / REST (JSON)       │ Spring Boot API ↔ Clients   │ Xác thực, tạo phòng, lấy state reconnect, lệnh Teacher.│
 │ Realtime Traffic  │ WebSocket (Protobuf)     │ Netty ↔ Actor ↔ Students    │ Truyền dữ liệu realtime 2 chiều (nộp bài, draft, progress).│
 │ Internal Routing  │ gRPC / Direct IPC        │ Netty Gateway ↔ Engine Pods │ Định tuyến tin nhắn từ Gateway đến đúng Actor room_id. │
 │ Async / Durable   │ Kafka Topic (Event Bus)  │ Actor ➔ Kafka ➔ Analytics   │ Event Sourcing, Audit, Analytics và Replay Recovery.   │
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

### 7.1. Phân tích Kỹ thuật & Lý do Chọn Con Số Vàng 200ms (200ms Tick Aggregation Window Rationale)

Con số **200ms (tương đương 5 ticks/giây)** được tính toán chính xác làm "Điểm ngọt" (Sweet Spot) tối ưu cho hệ thống Real-time EdTech Multiplayer dựa trên 3 trụ cột kỹ thuật:

#### 1. Sinh lý học Nhận thức Mắt người & Kỹ thuật Nội suy Client (Client-side Lerp)
* **Ngưỡng mắt người**: Mắt người bắt đầu nhận biết sự giật/khựng hình ảnh ở khoảng $100	ext{ms} - 200	ext{ms}$.
* **Kết hợp Nội suy tuyến tính (Linear Interpolation - Lerp)**: Server gửi snapshot trạng thái 200ms/lần ($5	ext{ ticks/s}$). Tại client (Flutter / WebView), JS/Dart áp dụng thuật toán Lerp kết hợp `requestAnimationFrame()` để tự động "vẽ nội suy" các khung hình trung gian ở tốc độ **$60	ext{ FPS}$ ($16.6	ext{ms}/	ext{frame}$)**.
* **Kết quả**: Màn hình học sinh hiển thị mượt 100% như game 60 FPS Native mà Server chỉ tốn chi phí 5 ticks/s.

#### 2. Phép toán Tối ưu Băng thông Network & Tải CPU Server tại 54.000 CCU ($4.500 	ext{ phòng}$)
* **Kịch bản KHÔNG gom tick (Bắn ngắt đoạn tức thì)**: 12 học sinh nộp bài ➔ phát sinh $pprox 12	ext{ msgs/s/phòng}$.
  $$	ext{Tổng Network Load} = 4.500 	ext{ phòng} 	imes 12 	ext{ học sinh} 	imes 12 	ext{ msgs} = 648.000 	ext{ packets/sec!}$$
* **Kịch bản DÙNG cửa sổ gom tick 200ms**: Server gom toàn bộ thao tác trong 200ms thành ĐÚNG 1 gói Snapshot tổng.
  $$	ext{Tổng Network Load} = 4.500 	ext{ phòng} 	imes 12 	ext{ học sinh} 	imes 5 	ext{ ticks} = 270.000 	ext{ packets/sec!}$$
* **Kết quả**: **Tiết kiệm $58.3\%$ tổng lượng packet trên đường truyền (NIC)** và giảm $70\%$ chi phí CPU format JSON/Protobuf tại Gateway!

#### 3. Tương thích với Độ trễ Hạ tầng Mạng Di động (3G / 4G / Wi-Fi Trường học)
* Mạng Wi-Fi trường học hoặc 3G/4G di động có latency dao động $30	ext{ms} - 80	ext{ms}$.
* Bắn tick quá dày ($20	ext{ms}/$lượt $= 50	ext{ ticks/s}$) sẽ gây hiện tượng ùn tắc bộ đệm (Bufferbloat) trên trạm phát 4G/Modem Wi-Fi của điện thoại, gây **Jitter (lúc giật khựng, lúc tăng tốc)** và làm nóng máy, nhanh tụt pin thiết bị học sinh.
* Cửa sổ 200ms giữ cho đường truyền di động luôn ổn định và mát máy.

---

### 7.2. Tối ưu Snapshot & Định dạng Protobuf Payload

1. **Adaptive LZ4HC Snapshot**:
   * Khi State thay đổi + quá 1 giây ➔ Chụp Snapshot.
   * Khi State không đổi + quá 3 giây ➔ Chụp Snapshot.
   * Các sự kiện quan trọng (`START`, `FINISH`, Đổi câu) ➔ Chụp Snapshot bắt buộc.
   * Serialize bằng Protobuf, nén LZ4HC (mức 4-6) nếu dung lượng > 150 bytes, lưu Redis kèm `TTL = 90 phút` và `last_kafka_offset`.
2. **Tối ưu Protobuf Field Types**:
   * Sử dụng `uint32`, `fixed64` cho mốc thời gian và điểm số.
   * Sử dụng `student_index` (từ 0 – 11) thay vì chuỗi `student_id` UUID dài trong các packet realtime để giảm kích thước payload xuống < 50 bytes.

---

## 8. 🛡️ Bảo mật, Chống chịu Lỗi (Fault Tolerance) & Khôi phục

### 8.1. Cơ chế Phục hồi Sự cố Pod (< 30ms Recovery)
1. **Khởi động lại Pod**: Pod mới được Kubernetes khởi tạo thay thế Pod cũ bị lỗi.
2. **Load Snapshot nóng**: Actor kết nối Redis trích xuất bản Snapshot gần nhất (`game:snapshot:{room_id}`).
3. **Giải nén & Rebuild**: Giải nén LZ4HC, deserialize Protobuf để tái tạo toàn bộ `Game State` in-memory.
4. **Kafka Replay**: Kết nối Kafka Topic, Seek tới `last_kafka_offset + 1` và replay các sự kiện chưa kịp lưu trong vài trăm ms cuối.
5. **Hoàn tất khôi phục**: Thời gian recovery kỳ vọng **< 20–30ms**, phiên học diễn ra liên tục không bị gián đoạn.

### 8.2. Bảo mật & Chống Spam
* **JWT Validation**: Phân tích và kiểm tra chữ ký Public Key ngay tại WebSocket Handshake của Netty Gateway.
* **Channel Attributes**: Khóa cứng `student_id` và `room_id` vào thuộc tính của Netty Channel sau khi Handshake thành công.
* **Token Bucket Rate Limiting**: Giới hạn tối đa X gói tin/giây trên mỗi kết nối WebSocket để ngăn chặn Scripting/Botnet.

---

### 8.3. Cơ chế Rate Limiting & Chống Spam Chi tiết tại Netty Gateway

Để đảm bảo hệ thống không bị ngắt kết nối hoặc quá tải CPU do tấn công Botnet / Auto-Clicker từ phía Client, tầng **Netty Gateway** triển khai 3 cấp độ phòng thủ Rate Limiting:

1. **Connection Rate Limit (Chống DDoS WebSocket Handshake)**:
   * Áp dụng tại HTTP Upgrade Handshake.
   * Giới hạn tối đa **5 lượt Handshake WebSocket / 1 địa chỉ IP / 1 phút**. Quá ngưỡng trả về HTTP 429.

2. **Message Rate Limit (Chống Spam Hot Path - Token Bucket / Bucket4j)**:
   * Mỗi `Netty Channel` (kết nối WebSocket của 1 học sinh) đính kèm 1 `Bucket` giới hạn bằng thư viện **Bucket4j**.
   * Cấu hình: Capacity = 5 tokens, Refill Rate = 5 tokens/giây.
   * Nếu học sinh gửi quá 5 packet/giây (`SUBMIT_ANSWER`, `UPDATE_DRAFT`), Netty trả về gói tin `RATE_LIMIT_EXCEEDED` và ngắt xử lý packet đó ngay tại biên, **không forward sang Game Engine**.

3. **Deduplication / Idempotency Check (Chống gửi trùng đáp án)**:
   * Mỗi request gửi từ Client mang một `client_request_id` duy nhất.
   * Server sử dụng Redis `SETNX` với khóa `lock:req:{client_request_id}` (TTL = 5 giây) để loại bỏ các gói tin gửi trùng lặp do lag mạng.

---

## 9. 📱 Hướng dẫn Thiết kế & Tối ưu Phía Client (Frontend)

Toàn bộ ứng dụng Client (Web/Mobile App) cần tuân thủ 4 quy tắc cốt lõi:

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
                 │                                  │                                  │ gRPC Internal
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
     * Ban đầu: Tìm đối thủ trong dải $\pm 50$ MMR.
     * Sau 5 giây chưa đủ 12 người: Tự động nới dải sang $\pm 150$ MMR để tránh học sinh chờ lâu.
  3. **Match Found Event**: Khi đủ 12 người, dịch vụ tạo `room_id`, gọi API spawn `Game Session Actor` và trả về `ws_url` để 12 client tự kết nối vào Netty Gateway.

### 10.2. Dịch vụ Bảng Xếp Hạng Toàn cầu (Global & Seasonal Leaderboard Service)
* **Kịch bản Nghiệp vụ**: Bảng xếp hạng Realtime toàn trường, Bảng xếp hạng theo Tuần/Mùa (Season), Bảng xếp hạng Bang hội/Lớp.
* **Cơ chế Kỹ thuật (Redis Sorted Sets Pattern)**:
  * Độc lập hoàn toàn với Bảng xếp hạng 200ms trong phòng.
  * Sử dụng Redis **Sorted Set (`ZADD`, `ZREVRANK`, `ZREVRANGE`)**:
    * Cập nhật điểm: `ZADD leaderboard:season_2026 {score} {student_id}` ➔ Độ phức tạp $O(\log N)$, phản hồi trong dưới 1ms.
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
* **Time Complexity**: **$O(1)$** cho mỗi thao tác `SUBMIT_ANSWER`. Tìm kiếm học sinh qua `student_index` (0-11) mất $O(1)$, cập nhật điểm mất $O(1)$.
* **Space Complexity**: **$O(S + T)$** với $S=12$ (học sinh) và $T=4$ (nhóm) ➔ Hằng số cực nhỏ **$O(1)$ Memory**, đảm bảo Game State không bị phình RAM.

#### 2. Thuật toán Gom nhóm & Nén Delta 200ms (`200ms Aggregation Window`)
* **Bài toán**: Tránh gửi lại toàn bộ Game State phình to xuống Client mỗi 200ms.
* **Giải pháp Thuật toán (Delta State Extraction)**:
  * So sánh State hiện tại và State của 200ms trước.
  * Chỉ trích xuất các thuộc tính có sự thay đổi (*Dirty Bit / Delta Encoding*).
* **Time Complexity**: **$O(K)$** với $K$ là số thuộc tính bị thay đổi ($K \le 12$).
* **Kết quả**: Giảm dung lượng gói tin Protobuf từ 2KB xuống **< 80 bytes**.

#### 3. Thuật toán Ghép trận MMR (`Matchmaking Ticket Expansion`)
* **Cấu trúc dữ liệu**: Redis Sorted Set (`zset`) với `score = MMR_rating`.
* **Time Complexity**:
  * Chèn Ticket tìm trận: **$O(\log N)$** với $N$ là tổng số người trong hàng chờ.
  * Thuật toán quét nới dải Expansion Window: **$O(M)$** với $M$ là số ứng viên trong cửa sở dãn dải MMR.

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
* **Thực tế triển khai**: Luồng Ghi (Command) xử lý qua Kafka & Actor Memory. Luồng Truy vấn báo cáo (Query) đọc bất đồng bộ từ ClickHouse & Redis Leaderboard.

#### 3. Circuit Breaker Pattern
* **Vị trí áp dụng**: `gRPC Internal Client & DB Connections`.
* **Thực tế triển khai**: Dùng Resilience4j tự động ngắt cầu chì (Open Circuit) khi gRPC Stream hoặc Database bị đơ/chết ➔ Trả về Fallback data chứ không ngắt gián đoạn hệ thống.

---


## 13. ⚠️ Đánh giá Rủi ro Kỹ thuật & Giải pháp Phòng ngừa Chi tiết (Risk Assessment & Mitigation Strategy)

Đánh giá phản biện kỹ thuật chuyên sâu cho 6 rủi ro trọng yếu (từ **A** đến **F**) của hệ thống và giải pháp phòng ngừa trước khi triển khai thực tế:

```text
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ A. RỦI RO TẦNG STATEFUL & RECOVERY TIME (Akka Cluster Sharding & Realistic SLAs)      │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ B. RỦI RO COUPLING gRPC STREAM (Bulkhead Thread Isolation & Circuit Breaker)           │
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
  1. **Quản lý Vị trí Actor**: Sử dụng **Akka / Pekko Cluster Sharding** kết hợp với Redis Routing Table (`room:routing:{room_id}` ➔ `engine_pod_ip`) để định vị Actor tự động mà không cần hardcode IP.
  2. **Điều chỉnh SLA Recovery Thực tế**: Tạm thời điều chỉnh SLA Recovery mục tiêu trên lý thuyết thành **50ms – 100ms (p99)**. Bắt buộc phải thực hiện bài test **Chaos Engineering & Load Benchmark** (đánh sập Pod under load 54.000 CCU (3x Target Peak)) để đo đạc số liệu p50/p99 thực tế trước khi cam kết chính thức với đối tác.
  3. **Tối ưu Kích thước Snapshot**: Ép kích thước `Game State Snapshot` < 5KB và phân luồng nạp Kafka Replay bất đồng bộ.

---

### 13.2. Rủi ro B: Thắt nút Giao tiếp gRPC Stream giữa Gateway và Actor

* **Vấn đề**: gRPC Bi-directional Stream là điểm nối liền (*Single Point of Coupling*) quan trọng. Nếu một Engine Pod bị chậm, nó có nguy cơ gây tắc nghẽn Thread Worker của Netty Gateway.
* **Giải pháp Phòng ngừa**:
  1. **Bulkhead Thread Isolation Pattern**: Tách biệt hoàn toàn Thread Pool xử lý WebSocket I/O của Netty và Thread Pool gọi gRPC Client. Một Engine Pod bị chậm sẽ không bao giờ ảnh hưởng đến các kết nối WebSocket khác tại Gateway.
  2. **Circuit Breaker & Backpressure**: Tích hợp **Resilience4j Circuit Breaker** cho gRPC Call. Khi Engine Pod không phản hồi trong 500ms, Gateway lập tức ngắt kết nối (Open Circuit) và chuyển sang giao tiếp dự phòng.

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
  * **Tầng 2 (Server)**: Trong `Game State` RAM của Actor lưu trữ một bảng `LastSeenSequenceTable[StudentIndex]`. Actor chỉ chấp nhận gói tin nếu `sequence_number > last_seen_sequence`. Mọi gói tin nhỏ hơn hoặc bằng sẽ bị loại bỏ lập tức với thời gian xử lý $O(1)$ trong RAM.

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
 [Client WS] ──► [Netty Gateway] ──► [gRPC Stream] ──► [Actor Engine] ──► [Kafka]
     │                 │                   │                  │             │
     └─────────────────┴─────────┬─────────┴──────────────────┴─────────────┘
                                 ▼
                    [OpenTelemetry Context Propagation]
                       (Trace_ID xuyên suốt toàn bộ luồng)
```

1. **Distributed Tracing (OpenTelemetry)**: Tự động đính kèm `trace_id` và `span_id` vào Protobuf Header, truyền xuyên suốt từ `Client WS` ➔ `Netty Gateway` ➔ `gRPC` ➔ `Game Session Actor` ➔ `Kafka`.
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
 │ 1. ROOM AFFINITY & CONSISTENT HASHING RECONNECT ROUTING                                │
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
 │ 7. TỐI ƯU OVERHEAD BẰNG PROJECT LOOM (Virtual Threads + Actor Model Java 25+)          │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 14.1. Consistent Hashing cho Reconnect & Room Affinity

* **Cơ chế**: Áp dụng **Consistent Hashing Ring** dựa trên `hash(room_id)` ngay tại tầng Netty Gateway.
* **Tác dụng**: Khi học sinh rớt mạng reconnect, Gateway tính `hash(room_id)` để định tuyến kết nối WebSocket mới trực tiếp tới đúng Engine Pod giữ Actor phòng đó mà không cần tra cứu Database tập trung liên tục.

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
  * `checksum` (XXHash64 / CRC32): Mã băm toàn vẹn dữ liệu.
* **Quy trình Khôi phục Safe Recovery**:
  1. Đọc Snapshot từ Redis ➔ Kiểm tra `CRC32 Checksum`.
  2. Nếu Checksum không khớp (Corrupted) ➔ Kích hoạt cơ chế **Fail-safe Fallback**: Bỏ qua snapshot và replay 100% sự kiện từ Kafka Event Stream từ đầu trận.

---

### 14.4. Cơ chế Backpressure từ Actor về Gateway (Mailbox Protection)

* **Phòng ngừa Mailbox Overflow**: Khi số lượng tin nhắn chờ trong Mailbox của Actor chạm ngưỡng nguy hiểm (`depth > 200 msgs`):
  1. Actor gửi tín hiệu Backpressure `PAUSE_READ` về Netty Gateway qua gRPC Stream.
  2. Netty Gateway lập tức tạm ngắt quyền đọc trên WebSocket Channel (`channel.config().setAutoRead(false)`) hoặc trả về thông báo `RATE_LIMIT_EXCEEDED` lập tức cho client.
  3. Khi Mailbox xả bớt (`depth < 50 msgs`), Actor gửi tín hiệu `RESUME_READ` để Gateway mở lại đường truyền.

---

### 14.5. Kế hoạch Chaos Engineering Sớm (Thử nghiệm Độc tính)

Thực thi Chaos Engineering bằng **Chaos Mesh** trên Môi trường Staging dưới tải 54.000 CCU (3x Target Peak) giả lập:

* 🧪 **Kịch bản 1 (Pod Mortality)**: Kill ngẫu nhiên 20% Engine Pods giữa cao điểm ➔ Đo thời gian Re-sharding và SLA Recovery.
* 🧪 **Kịch bản 2 (Network Partition)**: Cắt đứt kết nối gRPC giữa 1 Gateway Pod và 1 Engine Pod ➔ Đo độ nhạy của Circuit Breaker & Reconnect Logic.
* 🧪 **Kịch bản 3 (Storage Degradation)**: Thêm 50ms Latency Spike vào Redis ➔ Kiểm tra khả năng chịu tải của Adaptive Snapshot.

---

### 14.6. Bảng Cam kết Chỉ số Kỹ thuật Nội bộ (Internal Technical SLAs)

| Chỉ số Kỹ thuật (Internal Metric) | Mức Cam kết SLA (Target p99) | Công cụ Giám sát |
| :--- | :--- | :--- |
| **Actor Processing Time** | **$< 15	ext{ms}$** | Prometheus & OpenTelemetry |
| **Snapshot Serialization & Compress Time** | **$< 5	ext{ms}$** | Micrometer Timers |
| **Snapshot Payload Size** | **$< 5	ext{KB}$** | Prometheus Gauge |
| **Kafka Produce Latency** | **$< 10	ext{ms}$** | Kafka Client Metrics |
| **Gateway Smart Fan-out Latency** | **$< 5	ext{ms}$** | Netty Channel Metrics |
| **Full Recovery Time (p99 under load)** | **$< 50	ext{ms} - 100	ext{ms}$** | Chaos Test Suite |

---

### 14.7. Tối ưu Overhead bằng Java 25+ Project Loom (Virtual Threads)

* **Mô hình kết hợp Loom + Actor**:
  * Mỗi Actor Mailbox được thực thi bởi 1 **Virtual Thread** (`Executors.newVirtualThreadPerTaskExecutor()`) thay vì OS Platform Thread truyền thống.
  * Giảm tiêu tốn RAM của Thread từ **1MB xuống < 2KB**, cho phép chạy hàng chục ngàn Actor phòng game song song trên 1 Pod với bộ nhớ cực kỳ tiết kiệm mà vẫn giữ nguyên mô hình code đơn luồng an toàn!

---


## 15. 📈 Phân tích Độ ổn định & Kỹ thuật Xử lý khi Room Size Tăng lên 100 Người (100-Student Scale-Up Analysis)

Khi mở rộng quy mô phòng học từ **12 học sinh lên 100 học sinh/phòng**, hệ thống đối mặt với sự bùng nổ phi tuyến tính về số lượng thông điệp (*$N^2$ Message Explosion*) và áp lực băng thông.

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
  * Số tin nhắn broadcast outbound = $100 	imes 100 = 10.000	ext{ msgs/giây/phòng}$.
  * Với dung lượng payload $100	ext{ bytes}$ ➔ Băng thông outbound = **$1	ext{ MB/s}$ cho duy nhất 1 phòng**.
  * Nếu có 1.000 phòng 100 người ➔ Tổng băng thông outbound Gateway = **$1	ext{ GB/s}$ (8 Gbps)** ➔ **Rủi ro sập băng thông Gateway (Network Saturation)**.

#### 2. Áp lực Hàng đợi Mailbox & Latency tại Actor (Engine Pod)
* 100 bài nộp dồn vào Mailbox Queue của Actor trong 1 giây.
* Nếu xử lý từng bài nộp mất $1	ext{ms}$ ➔ Độ trễ xử lý bài nộp cuối cùng của phòng bị đẩy lên **$100	ext{ms}$** (Vượt cam kết SLA $15	ext{ms}$).
* Dung lượng Game State nén LZ4 Snapshot tăng từ $< 1	ext{KB}$ lên **$5	ext{KB} - 8	ext{KB}$**.

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
* Số tin nhắn broadcast/giây per room giảm từ $10.000	ext{ msgs/s}$ xuống **chỉ còn $5 	imes 100 = 500	ext{ msgs/s}$** (Tiết kiệm 95% băng thông!).

#### 2. Nén Trạng thái Thay đổi (Delta State Compression)
* Không bắn lại toàn bộ danh sách 100 học sinh.
* Chỉ bóc tách và nén mảng `StudentProgress` của những em **có điểm số/tiến trình thay đổi** trong 200ms vừa qua.
* Kích thước Payload Protobuf giảm từ $8	ext{KB}$ xuống **< 150 bytes**.

#### 3. Phân đoạn Bảng xếp hạng (Top-N Leaderboard Broadcast Pattern)
* Server không gửi toàn bộ vị trí của 100 người cho cả 100 em.
* Server chỉ gửi **Top 10 / Top 20 học sinh dẫn đầu** + **Thứ hạng cá nhân của chính học sinh đó** (`your_rank: 45, your_score: 120`).
* Kích thước mảng Protobuf giảm 85%.

#### 4. Actor Lock-free Batch Mailbox Processing
* Hỗ trợ Actor xử lý batch 10 - 20 bài nộp cùng lúc trong RAM (`batchConsumer`) thay vì xử lý đơn lẻ từng bài.
* Độ trễ xử lý 100 msgs trong Mailbox giảm từ $100	ext{ms}$ xuống **< 8ms**.

#### 5. Netty Zero-Copy Direct Buffer Slicing
* Tại Gateway, Netty mã hóa mảng bytes Protobuf 1 lần duy nhất (`ByteBuf.retainedDuplicate()`) và fan-out mảng bytes đó đến 100 WebSocket Channels qua Direct Memory mà không copy bộ nhớ ➔ **Zero Heap Allocation Overhead**!

---


## 16. ⚡ Đánh giá Khả năng Thay thế Spring WebFlux bằng Eclipse Vert.x cho Tầng Gateway

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

### 16.2. Kết luận Khuyên dùng (Architectural Recommendation)

1. **Tại Service 1 - WebSocket Gateway (Edge Layer)**: **RẤT NÊN CHUYỂN SANG ECLIPSE VERT.X**.
   * Gateway chỉ làm nhiệm vụ hứng kết nối I/O, xác thực JWT, Rate-limit và Smart Fan-out. Dùng Vert.x giúp Gateway đạt tốc độ truyền tải cực đại (*Sub-millisecond*), siêu nhẹ RAM (30MB/Pod) và khởi động container trong < 1 giây.
2. **Tại Service 2 - Game Engine (Core Business Logic)**: **GIỮ NGUYÊN JAVA 21+ (LOOM / AKKA + SPRING BOOT 3)**.
   * Tầng Game Engine chứa logic nghiệp vụ phức tạp, cần Virtual Threads (Loom) hoặc Akka Actor Framework + Spring Data cho PostgreSQL/MongoDB.

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
* **DevOps**: Chạy kịch bản giả lập **54.000 CCU** (270.000 req/s) qua Locust/JMeter ➔ Đánh sập ngẫu nhiên 20% Pods bằng Chaos Mesh ➔ Đảm bảo thời gian khôi phục phòng < 50ms (p99).

#### 📌 BƯỚC 6: Phát hành & Khách hàng Chơi thành công (Production Release & User Play)
1. Giáo viên bấm nút "Bắt đầu Game Đua Xe" trên Dashboard.
2. Matchmaking Service xếp 12 học sinh vào phòng.
3. Netty Gateway đón 12 kết nối WebSocket, validate JWT Token.
4. Học sinh chọn đáp án ➔ Netty đẩy gRPC Stream ➔ Actor đơn luồng chấm điểm ➔ Gom 200ms broadcast ➔ 4 chiếc xe trên màn hình 12 học sinh đồng loạt lao về đích mượt mà!

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
 │    • game.analytics.raw-answers : Dữ liệu thô cho ClickHouse Big Data Báo cáo.            │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 20.1. Ba (3) Nguyên tắc Vàng Quản lý Kafka Topics

#### 1. Partitioning Strategy by `game_id` / `room_id` (Đảm bảo Tuần tự 100%)
* Mọi tin nhắn nộp bài gửi vào Kafka Topic `game.events.submit` bắt buộc phải đính kèm `PartitionKey = room_id`.
* **Tác dụng**: Tất cả bài nộp của cùng 1 phòng học sẽ luôn rơi vào **cùng 1 Kafka Partition**, đảm bảo các sự kiện được xếp hàng và Consume theo đúng thứ tự thời gian $1, 2, 3...N$.

#### 2. Phân tách Độc lập Consumer Groups (Consumer Group Isolation)
* Các nghiệp vụ tiêu thụ dữ liệu được tách thành các **Consumer Group riêng biệt**:
  * `leaderboard-worker-group`: Đọc `game.events.lifecycle` để ZADD xếp hạng toàn trường.
  * `analytics-worker-group`: Đọc `game.analytics.raw-answers` để ghi Batch Insert vào ClickHouse.
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
   * Tự động bắt lỗi `ArithmeticException` (Chia cho 0), `NaN`, `Infinity`. Trường hợp vi phạm sẽ lấy giá trị điểm mặc định (`fallback_score = 0`) và clamp kết quả trong khoảng an toàn $0 \le score \le 1.000$.

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
                            │ gRPC Async Stream (Zero-Copy)
                            ▼
 ┌───────────────────────────────────────────────────────────────────────────────────────┐
 │ VIRTUAL THREADS / ACTOR WORKER POOL (Game Logic Execution)                             │
 │   • 1 Room = 1 Actor Execution Context (Xử lý tuần tự nội bộ 1 phòng).               │
 │   • Không dùng Lock Shared Mutable State -> CHỐNG DEADLOCK 100%.                       │
 │   • Tránh Thread Pinning: Thay `synchronized` bằng `ReentrantLock`.                     │
 │   • Watchdog Timeout: Mỗi tick xử lý không quá 10ms (Overbudget -> Alert & Skip).     │
 └───────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Cô lập Tuyệt đối Netty EventLoop (Zero-Blocking Netty Threads)**:
   * Netty I/O EventLoop Threads được cố định số lượng $= 	ext{CPU Cores} 	imes 2$.
   * Mọi packet từ Client sau khi bóc tách header sẽ được gRPC Stream đẩy ngay sang **Virtual Thread / Actor Mailbox**. Netty EventLoop không bao giờ bị nghẽn (0ms blocking time).

2. **Loại bỏ Deadlock bằng Mô hình Single-Threaded Actor (1 Room = 1 Actor)**:
   * Không có cơ chế Locking (`synchronized` hay `Lock`) giữa các phòng chơi. State của phòng chơi nào nằm trọn trong Actor của phòng đó.
   * Vì không có Lock contention giữa các thread, nguy cơ **Deadlock treo thread là $0\%$**.

3. **Chống Thread Pinning trong Java 25+ Virtual Threads**:
   * Khi dùng Virtual Threads (Project Loom), nếu gọi I/O blocking trong khối `synchronized`, Virtual Thread sẽ bị "pin" (dính chặt) vào Carrier Native Thread, gây nghẽn Native Thread Pool.
   * **Quy tắc**: Toàn bộ mã nguồn Engine dùng `java.util.concurrent.locks.ReentrantLock` hoặc cơ chế Non-blocking Message Queue của Akka/Vert.x.

4. **Watchdog Execution Time Budget Guard**:
   * Mỗi sự kiện xử lý trong Game Engine Actor được cấp một Time Budget tối đa **10ms**.
   * Nếu 1 logic chạy quá 10ms (ví dụ do thuật toán phức tạp), Watchdog Timer sẽ ngắt và đẩy warning log về OpenTelemetry để dev tối ưu hóa, đảm bảo không một room nào chiếm dụng thread quá lâu.

---

### 22.2. Kiểm Soát Fan-Out Broadcast (Fan-Out Storm Mitigation)

Khi một sự kiện phòng nổ ra, làm sao để broadcast cho $N$ học sinh mà không gây quá tải?

```text
               ┌─────────────────────────────────────────────────┐
               │    Game Engine Actor (Chỉ Serialize 1 LẦN)     │
               └────────────────────────┬────────────────────────┘
                                        │ 1 Packet Protobuf Binary
                                        ▼
               ┌─────────────────────────────────────────────────┐
               │    Netty Gateway Instance (Zero-Copy Buffer)    │
               └────┬───────────────────┬───────────────────┬────┘
                    │ ByteBuf.retain()  │ ByteBuf.retain()  │ ByteBuf.retain()
                    ▼                   ▼                   ▼
              [WebSocket 1]       [WebSocket 2]       [WebSocket N]
              (Student 1)         (Student 2)         (Student N)
```

#### 1. Giới hạn Phạm vi Broadcast (Room Boundary Isolation)
* Bản chất lớp học EdTech phân tách thành các phòng $12 - 100$ học sinh.
* **Nguyên tắc**: **Không bao giờ broadcast toàn hệ thống (Global Broadcast)** từ Game Engine. Mọi broadcast đều scoped theo `room_id`. Fan-out tối đa cho 1 sự kiện phòng chỉ là $N = 12 
ightarrow 100$ connections.

#### 2. Kỹ thuật Zero-Copy & Shared Memory Buffer (`ByteBuf.retain()`)
* **Cách ngây thơ (Gây OOM)**: Với 100 học sinh, serialize mảng Protobuf 100 lần $
ightarrow$ tạo 100 đối tượng `byte[]` đẩy vào RAM $
ightarrow$ GC Pause sập hệ thống.
* **Cách Chuẩn Chống Fan-Out Storm**:
  * Game Engine / Netty Gateway **chỉ serialize Protobuf đúng 1 lần** tạo thành 1 vùng nhớ `DirectByteBuf`.
  * Khi gửi cho 100 WebSockets, Gateway chỉ gọi `ByteBuf.retainedDuplicate()` (chỉ tăng Reference Count, không copy bộ nhớ).
  * Chi phí RAM cho Fan-out 100 người $= O(1)$ memory allocation!

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
 │ LỚP 1: KHÔNG DÙNG REDIS PUB/SUB CHO TIN NHẮN PHÒNG (Direct gRPC Stream)                 │
 │   • Hot-path tin nhắn phòng (Chấm điểm, Đua xe) CHẠY THẲNG qua gRPC Bi-directional.     │
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
  * Khi 12 học sinh vào phòng, Netty Gateway duy trì kết nối **gRPC Bi-directional Streaming trực tiếp** tới Pod Game Engine giữ Actor của phòng đó.
  * Khi Game Engine tính xong kết quả, nó đẩy packet qua chính gRPC Stream đó về lại đúng Gateway Pod chứa 12 socket của phòng.
  * **Kết quả**: **Hoàn toàn KHÔNG qua Redis Pub/Sub**. Loại bỏ $100\%$ rủi ro nhầm channel hay rò rỉ tin nhắn giữa các phòng!

#### 2. Lớp 2: Quy chuẩn Đặt tên Redis Channel (Strict Namespace Schema)
* Trong trường hợp cần dùng Redis Pub/Sub (như hạ tầng đa cụm Gateway):
  * **Channel Key Format**: `k12:{env}:room:{{room_uuid}}:events` (Ví dụ: `k12:prod:room:{a8f3-4b12-9c90}:events`).
  * **Hash Tag `{room_uuid}`**: Đảm bảo toàn bộ tin nhắn của 1 phòng nằm trọn trong 1 Redis Cluster Node, tối ưu $O(1)$ Pub/Sub.
  * **Tuyệt đối CẤM**: Không dùng pattern `PSUBSCRIBE` với dấu `*` ở tầng Gateway để tránh nhận dồn tin nhắn phòng khác.

#### 3. Lớp 3: Netty Gateway Frame Guardrail (Bảo vệ Vòng cuối tại Socket)
Ngay trước khi Netty Gateway ghi gói tin nhị phân vào Socket của học sinh, Gateway thực hiện một câu lệnh kiểm tra an toàn ($O(1)$ RAM lookup):

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


# 🚀 PHẦN IV: MỞ RỘNG QUY MÔ & TỐI ƯU HẠ TẦNG

---

## 23. 🌐 Mô hình Triển khai Đa Pod & Luồng Dữ liệu Thực tế (Multi-Pod Deployment Topology & Practical Data Flow)

Để đáp ứng quy mô **3x Peak Target = 54.000 CCU** ($4.500 	ext{ phòng chơi}$ đồng thời), hệ thống được triển khai theo mô hình microservices đa cụm (Multi-Pod Cluster) trên Kubernetes. Phần này mô tả chi tiết từng bước vận hành thực tế của luồng dữ liệu khi hạ tầng mở rộng ra hàng chục Pods.

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
                       │ gRPC Bi-directional Stream                                  │ gRPC Bi-directional Stream
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

1. **Netty Gateway Pool (10 - 12 Pods)**:
   * **Cấu hình 1 Pod**: `2 vCPU`, `4 GB RAM`.
   * **Nhiệm vụ**: Duy trì $5.000$ kết nối WebSocket/Pod. Tổng 10 Pods duy trì $50.000 - 60.000$ WebSockets.
   * **Đặc tính**: Completely Stateless (Không lưu logic game).

2. **Game Engine Pool (4 Pods)**:
   * **Cấu hình 1 Pod**: `4 vCPU`, `8 GB RAM`.
   * **Nhiệm vụ**: Chạy $1.125$ Room Actors/Pod. Quản lý tính toán điểm, thời gian, trạng thái FSM của phòng.
   * **Đặc tính**: Stateful in-memory (Phòng nào gắn chặt vào Actor trên Pod đó).

3. **Redis Cluster (3 Master - 3 Replica)**:
   * Lưu `Session Registry` (Tra cứu `room_id -> engine_pod_ip`) và `Room Snapshot` phục vụ khôi phục khi sập Pod.

---

### 23.2. Chi tiết Luồng Dữ liệu Thực tế từ A-Z qua Đa Pod (End-to-End Multi-Pod Flow)

Giả sử **Phòng 101** gồm 12 học sinh: 6 học sinh nối vào **Gateway Pod A**, 6 học sinh nối vào **Gateway Pod B**. Phòng 101 được phân bổ nằm trên **Engine Pod 2**.

#### 📌 BƯỚC 1: Ghép trận & Đăng ký Vị trí Phòng (Matchmaking & Actor Allocation)
1. Matchmaking Service xếp 12 học sinh vào `room_id = 101`.
2. Matchmaker chọn **Engine Pod 2** làm nơi khởi tạo `RoomActor(101)`.
3. Matchmaker đăng ký vị trí vào Redis Session Registry:
   `SET k12:session:room:101 "engine-pod-2.internal:50051"` (TTL = 2 giờ).

#### 📌 BƯỚC 2: Học sinh Kết nối & Mở luồng gRPC Stream (Client Connect & gRPC Multiplexing)
1. 6 học sinh nối WebSocket tới **Gateway Pod A**; 6 học sinh nối WebSocket tới **Gateway Pod B**.
2. **Gateway Pod A** tra cứu Redis: `room:101 -> engine-pod-2.internal:50051`.
3. **Gateway Pod A** mở (hoặc tái sử dụng) 1 đường ống **gRPC Bi-directional Stream** duy nhất kết nối tới `Engine Pod 2`.
4. **Gateway Pod B** tương tự, mở 1 đường ống **gRPC Stream** tới `Engine Pod 2`.

#### 📌 BƯỚC 3: Học sinh Nộp bài & Xử lý Nội bộ Actor (Real-time Action Handling)
1. Học sinh số 1 (đang nối vào **Gateway Pod A**) bấm chọn đáp án "B".
2. **Gateway Pod A** nhận binary Protobuf frame từ WebSocket ➔ Đẩy thẳng vào gRPC Stream tới **Engine Pod 2**.
3. **Engine Pod 2** chuyển packet vào `Mailbox` của `RoomActor(101)`.
4. `RoomActor(101)` xử lý đơn luồng trong **$0.2	ext{ms}$**:
   * Kiểm tra đáp án đúng/sai.
   * Cập nhật điểm và vị trí xe đua của 12 học sinh.
   * Tạo gói tin Broadcast binary `TeamRaceProgressBroadcast`.

#### 📌 BƯỚC 4: Broadcast Bắn ngược Zero-Broker (Zero-Broker Return Broadcast)
1. `RoomActor(101)` trên **Engine Pod 2** đẩy gói tin `TeamRaceProgressBroadcast` ngược lại qua 2 gRPC Streams đang mở sẵn với **Gateway Pod A** và **Gateway Pod B**.
2. **Gateway Pod A** nhận gói tin ➔ Dùng `ByteBuf.retain()` bắn cho 6 WebSocket clients cục bộ trên Pod A.
3. **Gateway Pod B** nhận gói tin ➔ Dùng `ByteBuf.retain()` bắn cho 6 WebSocket clients cục bộ trên Pod B.
4. **Kết quả**: Cả 12 học sinh trên 2 Pod khác nhau thấy màn hình cập nhật đồng loạt trong **$< 20	ext{ms}$**!

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
 │   4. Pod C tra cứu Redis, nối lại gRPC Stream tới Engine Pod 2 -> Trận đấu tiếp tục!     │
 ├─────────────────────────────────────────────────────────────────────────────────────────┤
 │ TRƯỜNG HỢP 2: SẬP GAME ENGINE POD 2                                                     │
 │   1. Akka Cluster / Kubernetes phát hiện Pod 2 bị sập (Heartbeat timeout 1s).            │
 │   2. Akka Sharding tự khởi tạo lại `RoomActor(101)` trên ENGINE POD 3.                  │
 │   3. Actor mới nạp lại Room State Snapshot mới nhất từ Redis Cluster (< 10ms).           │
 │   4. Cập nhật vị trí mới trên Redis Session: `room:101 -> engine-pod-3.internal:50051`.  │
 │   5. Gateway Pod A & B tự ngắt stream cũ, nối stream mới sang Pod 3 -> Phòng hồi phục!  │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
```

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
   * Trình duyệt Chromium nhúng trong WebView tốn $100	ext{MB} - 200	ext{MB}$ RAM per instance.
   * **Quy tắc**: Khi học sinh kết thúc trận đấu và quay lại màn hình chính Flutter, bắt buộc phải gọi:
     `controller.loadRequest(Uri.parse('about:blank'));`
     để ép Chromium V8 Engine giải phóng hoàn toàn bộ nhớ RAM.

3. **Cơ chế Nội suy Smooth Animation (Linear Interpolation - Lerp)**:
   * Server gửi gói tin vị trí xe đua chu kỳ $200	ext{ms}$ ($5 	ext{ tick/s}$).
   * JS trong WebView **KHÔNG ĐƯỢC** nhảy thẳng vị trí xe mà phải dùng thuật toán **Linear Interpolation (Lerp)** kết hợp `requestAnimationFrame` để xe di chuyển trơn tru ở tốc độ $60 	ext{ FPS}$ trên màn hình thiết bị.

---


# 🗺️ PHẦN VI: ĐÁNH GIÁ KIẾN TRÚC & LỘ TRÌNH TRIỂN KHAI THỰC CHUYÊN

---

## 25. 📊 Đánh giá Độc lập & Lộ trình Tinh chỉnh Thực chiến (Architectural Review & Refinement Protocol)

Dựa trên Đánh giá Kiến trúc Tổng thể (Điểm số **8.2 / 10**), hệ thống ghi nhận các điểm mạnh cốt lõi (Mô hình 1 Room = 1 Actor đơn luồng, Tách biệt Gateway/Engine, Authoritative Protobuf WebSocket, Cross-room Isolation, Runtime Guardrails) đồng thời thực hiện **6 Tinh chỉnh Thực chiến** nhằm làm mịn hệ thống, giảm nợ kỹ thuật (Technical Debt) và tối ưu chi phí vận hành.

---

### 25.1. Bảng 6 Điểm Tinh chỉnh Thực chiến (Six Strategic Refinements)

```text
 ┌─────────────────────────────────────────────────────────────────────────────────────────────┐
 │ 1. RECOVERY SLA: Thực tế hóa từ < 30ms sang 50 - 100ms (p99 Target)                         │
 │ 2. POD CAPACITY: Scale Pod linh hoạt theo Game Tier (Tier 3 -> Max 300-500 rooms/pod)     │
 │ 3. gRPC BACKPRESSURE: Giám sát chỉ số `actor_mailbox_queue_depth` & Circuit Breaker        │
 │ 4. ĐƠN GIẢN HÓA OPERATIONAL STACK: Dùng Managed Services (AWS MSK, ElastiCache) cho MVP     │
 │ 5. ADAPTIVE TICK RATE: 200ms cho Tier 1/2 (Quiz) & 50ms cho Tier 3 (Bấm chuông phản xạ)    │
 │ 6. GATEWAY EVOLUTION: Java Vert.x (Giai đoạn 1) -> Go Gateway (Giai đoạn 2 khi CCU > 100k)   │
 └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 25.2. Chi tiết 6 Tinh chỉnh Kiến trúc

#### 1. Thực tế hóa Recovery SLA (Mục tiêu 50 – 100ms p99)
* **Thực tế**: Dưới tải cao ($54.000 	ext{ CCU}$), tổng thời gian gồm Redis Read + Protobuf Deserialize + Kafka Seek `last_offset` thực tế sẽ dao động từ $40	ext{ms} - 80	ext{ms}$.
* **Chuẩn hóa**: Điều chỉnh SLA phục hồi sự cố sập Pod về mức **$50 - 100	ext{ms}$ (p99)**. Đây là con số học sinh hoàn toàn không thể nhận ra trên màn hình mobile.

#### 2. Phân bổ Pod Capacity theo Game Tier & Quy mô Phòng
* Không dùng chung định mức $1.125$ rooms/pod cho mọi dạng game:
  * **Tier 1 (Quiz 12 học sinh)**: Giữ nguyên $1.125 	ext{ rooms/pod}$ ($4 	ext{ Pods}$ cho $54	ext{k CCU}$).
  * **Tier 3 (Phát hiện va chạm, Boss Battle, 100 học sinh/phòng)**: Giảm xuống **$300 - 500 	ext{ rooms/pod}$**. Hệ thống Kubernetes HPA tự động scale-out số lượng Game Engine Pods từ **4 Pods lên 8 - 10 Pods** để giảm áp lực cho Mailbox và CPU.

#### 3. Kiểm soát gRPC Stream Backpressure & Metric `actor_mailbox_queue_depth`
* Đặt chỉ số cảnh báo cao độ trên Prometheus/Grafana: `actor_mailbox_queue_depth`.
* **Quy tắc**: Nếu `actor_mailbox_queue_depth > 100` trên một Room Actor (do client nộp bài quá dồn dập hoặc logic bị nghẽn), Gateway sẽ kích hoạt **Stream Backpressure**: Drop ngắt đuôi các gói tin Non-critical (như vị trí xe / animation nháp) để ưu tiên xử lý bài nộp Critical.

#### 4. Chiến lược Giảm Phức tạp Vận hành (Operational Simplification Roadmap)
Để giảm chi phí DevOps và debug cho đội ngũ phát triển:
* **Giai đoạn MVP (Hiện tại)**: Tối đa hóa việc dùng **Managed Services**:
  * AWS ElastiCache cho Redis Cluster.
  * AWS MSK cho Apache Kafka.
  * AWS RDS PostgreSQL cho Relational Data.
  * Tạm hoãn triển khai ClickHouse cho đến khi dung lượng log vượt quá $10	ext{GB/ngày}$ (Tạm thời ghi Analytics thẳng vào PostgreSQL / MongoDB).

#### 5. Adaptive Tick Rate (Tần suất Tick động theo Dạng Game)
* **Tier 1 & 2 (Quiz trắc nghiệm, Đua xe tiến độ)**: Duy trì **$200	ext{ms}$ ($5 	ext{ ticks/s}$)** để tối ưu $58\%$ băng thông mạng.
* **Tier 3 (Bấm chuông cướp quyền, Phản xạ tức thì $< 50	ext{ms}$)**: Tự động chuyển sang **$50	ext{ms}$ ($20 	ext{ ticks/s}$)** cho riêng phòng chơi đó, đảm bảo trải nghiệm nhạy bén tối đa.

#### 6. Lộ trình Tiến hóa Gateway (Phased Gateway Evolution)
* **Giai đoạn 1 (Hiện tại)**: Triển khai **Java Vert.x Native Epoll Gateway** để tái sử dụng toàn bộ kiến thức Java 25+, Protobuf Data Model và rút ngắn thời gian phát triển (Time-to-market).
* **Giai đoạn 2 (Khi CCU > 100.000)**: Tách lớp Gateway chuyển đổi sang **Go (Golang WebSocket Gateway)** để giảm $60\%$ RAM Footprint và tối ưu chi phí hạ tầng Cloud.

---




---

### 🐳 25.3. Tương thích & Tối ưu hóa Tuyệt đối với Kubernetes (Java 25+ K8s Integration Guidelines)

Java 25+ và Kiến trúc hệ thống này tương thích **100% Native với hạ tầng Kubernetes (K8s)** nhờ các đặc tính tối ưu hóa container thế hệ mới:

1. **CGroup v2 Container Awareness & OOM Protection**:
   * Java 25+ tự động đọc chính xác `resources.limits.memory` của Kubernetes Pod.
   * Sử dụng `-XX:MaxRAMPercentage=75.0` giúp JVM tự động tính toán Heap RAM theo hạn mức Pod, loại bỏ $100\%$ rủi ro bị Kubernetes `OOMKilled`.

2. **Trả lại RAM vật lý cho K8s Node (`-XX:ZUncommit=true`)**:
   * Khác với G1GC cũ giữ khư khư RAM, ZGC trên Java 25+ tự động nhả RAM rảnh rỗi về cho K8s Node OS trong giờ thấp điểm, giúp hạ chi phí server K8s đáng kể.

3. **Xử lý Tín hiệu `SIGTERM` & Graceful Shutdown Tự động**:
   * Khi K8s thực hiện Rolling Update hoặc Auto-scaling scale-in, K8s gửi tín hiệu `SIGTERM`.
   * Netty Gateway & Game Engine bắt `SIGTERM`, dừng nhận socket mới, hoàn tất ghi Snapshot phòng chơi vào Redis trong $10	ext{ms}$ trước khi ngắt Pod mà **không gây ngắt đoạn trận đấu của học sinh**.

4. **K8s Probes & HPA Auto-Scaling**:
   * Cấu hình Liveness Probe (`/health/liveness`) và Readiness Probe (`/health/readiness`).
   * Tự động scale-out số lượng Pods qua K8s HPA khi CPU > 70% hoặc chỉ số `actor_mailbox_queue_depth > 100`.

---

## 26. 🗺️ Lộ trình Triển khai Phân kỳ Tinh gọn (Lean 2-3 Week Phase Roadmap)

Tất cả các giai đoạn triển khai được giới hạn nghiêm ngặt **tối đa từ 2 đến 3 tuần mỗi giai đoạn** (Agile Sprints). Thành phần **ClickHouse OLAP được CẮT BỎ HOÀN TOÀN** để đơn giản hóa bộ máy vận hành (dữ liệu báo cáo lịch sử sử dụng PostgreSQL Table Partitioning / MongoDB). Mặc định sử dụng **Java 25+ (Generational ZGC & Compact Object Headers)** trên môi trường Kubernetes.

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
 ├─────────────────────────────────────────────────────────────────────────────────────────────┤
 │ GIAI ĐOẠN 2: SCALE-OUT & ASYNC PIPELINE (3 Tuần | Tuần 4 - Tuần 6 | Target: 10k - 20k CCU)  │
 │   • Tuần 1 (Tuần 4): Tích hợp Apache Kafka (AWS MSK) phân tách Hot/Cold Path.               │
 │   • Tuần 2 (Tuần 5): Viết Async Leaderboard Worker (`ZADD` Redis) & Audit Worker (MongoDB). │
 │   • Tuần 3 (Tuần 6): Bật Pekko Cluster Sharding tự động phân bổ 4 Engine Pods & Chaos Test. │
 ├─────────────────────────────────────────────────────────────────────────────────────────────┤
 │ GIAI ĐOẠN 3: ENTERPRISE SCALE & RESILIENCE (2 Tuần | Tuần 7 - Tuần 8 | Target: 54.000+ CCU) │
 │   • Tuần 1 (Tuần 7): Bật Adaptive LZ4HC Compression cho Snapshot + Postgres Partitioning.   │
 │   • Tuần 2 (Tuần 8): Multi-AZ Redis/Kafka HA + Stress Test 54.000 CCU (p99 < 20ms) -> DONE!│
 │   • CẮT BỎ  : HOÀN TOÀN KHÔNG DÙNG CLICKHOUSE (Tận dụng Postgres Partitioning / MongoDB).    │
 └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 26.2. Chi tiết Công việc từng Giai đoạn (Tối đa 2-3 Tuần)

#### 📌 GIAI ĐOẠN 1: LEAN MVP SPRINT (3 Tuần | Tuần 1 - Tuần 3)
* **Mục tiêu**: Ra mắt bản MVP chơi Quiz realtime cho $2.000 - 3.000 	ext{ CCU}$ trong đúng 21 ngày làm việc.
* **Thành phần**: Flutter Native Socket ➔ Netty Gateway (2 Pods) ➔ Java 25+ Engine Pekko (2 Pods, `room_id % 2`) ➔ Redis + PostgreSQL.
* **Chi tiết**: Xem Phụ lục 27.

#### 📌 GIAI ĐOẠN 2: SCALE-OUT & ASYNC PIPELINE (3 Tuần | Tuần 4 - Tuần 6)
* **Mục tiêu**: Tách luồng xử lý Realtime và Ghi sổ, nâng khả năng chịu tải lên $10.000 - 20.000 	ext{ CCU}$.
* **Công việc theo tuần**:
  * **Tuần 1 (Tuần 4)**: Triển khai Apache Kafka (AWS MSK), đẩy sự kiện `GAME_FINISHED` từ Engine sang Kafka thay vì gọi DB trực tiếp.
  * **Tuần 2 (Tuần 5)**: Xây dựng `Leaderboard Worker` (cập nhật `ZADD` Redis) và `Audit Worker` (lưu log chống hack vào MongoDB).
  * **Tuần 3 (Tuần 6)**: Bật `Pekko Cluster Sharding` tự động cân bằng tải 4.500 phòng trên 4 Engine Pods và chạy Chaos Test nhẹ.

#### 📌 GIAI ĐOẠN 3: ENTERPRISE SCALE & RESILIENCE (2 Tuần | Tuần 7 - Tuần 8)
* **Mục tiêu**: Tối ưu hóa chi phí hạ tầng Cloud và sẵn sàng cho mốc $54.000+ 	ext{ CCU}$.
* **Công việc theo tuần**:
  * **Tuần 1 (Tuần 7)**: Bật `Adaptive LZ4HC Compression` trên Redis (nén payload > 150 bytes) + Cấu hình PostgreSQL Table Partitioning theo tháng để lưu lịch sử đấu (KHÔNG CẦN DÙNG CLICKHOUSE).
  * **Tuần 2 (Tuần 8)**: Cấu hình Multi-AZ Redis/Kafka High Availability, chạy Stress Test 54.000 CCU (270.000 req/s) và nghiệm thu bảo mật toàn hệ thống.

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
- 1 Room = 1 Pekko Actor (hoặc Virtual Thread Java 25+).
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
> Tập trung $100\%$ nguồn lực dựng **WebSocket Server (Netty Gateway)** và **Lõi Game Engine (Java 25+ / Pekko RoomActor)** ngay trong 3-4 ngày đầu tiên của Tuần 1. Toàn bộ các luồng phụ (Auth REST, Database Save, UI Decoration) chỉ được làm sau khi luồng WebSocket ↔ Game Engine đã nhận/phát dữ liệu nhị phân Protobuf thông mượt!

#### 📅 Tuần 1: DỰNG GAME ENGINE & WEBSOCKET CORE (ƯU TIÊN SỐ 1)
* **Mục tiêu**: 1 phòng 12 học sinh chơi được quiz từ đầu đến cuối qua WebSocket + Protobuf, điểm cập nhật realtime.

| Ngày | Công việc chính | Kết quả mong đợi |
|---|---|---|
| **Ngày 1-2** | - Chốt file `game_message.proto`<br>- Generate code Java 25+ & Dart (Flutter)<br>- Setup project Gateway + Engine | Protobuf sẵn sàng, project chạy |
| **Ngày 3-4** | - Dựng Gateway: WebSocket + Auth Ticket + forward gRPC<br>- Kết nối Gateway ↔ Engine qua gRPC Stream | Client kết nối WebSocket thành công |
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
1. **Thread Starvation (Treo Thread Pool)**: Dòng 78 `endFuture.get()` bắt Thread trong `ThreadPoolExecutor` phải đứng chờ (Block) trong suốt 3-5 phút cho đến khi phiên thảo luận kết thúc. Khi có $500$ phòng hoạt động đồng thời, toàn bộ Thread pool bị treo cứng, phát sinh lỗi `RejectedExecutionException` ngắt quãng trận đấu.
2. **Chi phí RAM & Redis IO lớn**: Lưu và truy vấn Redis Set (`redisService.sAdd`, `redisService.set`) liên tục làm trung gian đồng bộ state thay vì quản lý trực tiếp trong RAM.

---

### 28.2. Luồng Nghiệp vụ Sau khi Chuyển đổi sang Kiến trúc Mới (Non-blocking Actor Model)

Giữ nguyên $100\%$ các mốc thời gian nghiệp vụ ($16	ext{s}, 36	ext{s}, 46	ext{s}, 51	ext{s}$), nhưng thay thế mô hình Thread Pool Blocking bằng **Apache Pekko Single-threaded Actor**:

```text
 [gRPC Stream / Netty Gateway Event]
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
| **Trạng thái Thread** | ❌ **Blocking**: Thread bị khóa bởi `endFuture.get()` trong 3-5 phút. | 🟢 **Non-blocking**: Thread giải phóng ngay trong $< 0.1	ext{ms}$ sau khi xử lý tin nhắn. |
| **Giới hạn Phòng/Pod** | ❌ **Thấp**: ~ $200 - 500$ phòng (bị kịch trần Thread Pool `RejectedExecutionException`). | 🟢 **Rất cao**: **$3.000 - 5.000$ phòng/Pod** mượt mà. |
| **Độ trễ Broadcast** | $100 - 300	ext{ms}$ (Truy vấn Redis key `sAdd`/`set`). | **$< 5	ext{ms}$** (Bắn trực tiếp gRPC Stream nhị phân Protobuf). |
| **Rủi ro Deadlock** | ⚠️ Có nguy cơ do tranh chấp Thread Pool. | 🛡️ **Bằng $0\%$** (Mô hình Actor không dùng Shared Memory Lock). |

---

### 28.4. Mã nguồn Java 25+ Minh họa Refactor (`GroupDiscussionActor.java`)

Dưới đây là đoạn mã chuyển đổi `WorkerControlTLN` sang `Pekko Actor` loại bỏ $100\%$ lỗi treo thread:

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
        // Execute fast non-blocking business logic & broadcast via gRPC Stream
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
