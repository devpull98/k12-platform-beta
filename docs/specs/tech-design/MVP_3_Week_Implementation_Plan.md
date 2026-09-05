# KẾ HOẠCH TRIỂN KHAI MVP 3 TUẦN (3-WEEK LEAN MVP IMPLEMENTATION PLAN)
## Hệ thống Game Thời gian Thực EdTech Multiplayer (v2.1)

**Phiên bản:** 2.1 (Hợp nhất vào Tài liệu Kiến trúc Tổng thể)  
**Mục tiêu:** Ra mắt MVP trong đúng 3 tuần (21 ngày làm việc)  
**Target CCU MVP:** 2.000 – 3.000 CCU (Stretch goal: 5.000 CCU)  
**Mặc định Nền tảng:** Java 25+ (Generational ZGC & Compact Object Headers)  
**Tài liệu tham chiếu chính:** [EdTech_Game_Realtime_Architecture_v2.1.md](file:///d:/Educa/k12-backend-java/docs/EdTech_Game_Realtime_Architecture_v2.1.md)

---

## 1. Định nghĩa Phạm vi MVP

> [!IMPORTANT]
> **ƯU TIÊN SỐ 1 TUYỆT ĐỐI (CORE PRIORITY)**: 
> Tập trung $100\%$ nguồn lực dựng **WebSocket Server (Netty Gateway)** và **Lõi Game Engine (Java 25+ / Pekko RoomActor)** ngay trong 3-4 ngày đầu tiên của Tuần 1. Toàn bộ các luồng phụ (Auth REST, Database Save, UI Decoration) chỉ được làm sau khi luồng WebSocket ↔ Game Engine đã nhận/phát dữ liệu nhị phân Protobuf thông mượt!

### 1.1. Những gì BẮT BUỘC có trong MVP
- Học sinh vào phòng và chơi **Quiz realtime** (12 học sinh/phòng).
- Nộp bài ➔ nhận kết quả tức thì.
- Hiển thị điểm số và bảng xếp hạng trong phòng.
- Reconnect rớt mạng vẫn tiếp tục được chơi (nạp lại UI state từ Redis).
- Hệ thống chịu được **2.000 – 3.000 CCU** ổn định.
- Gateway tách biệt Engine (0% business logic).
- 1 Room = 1 Pekko Actor (hoặc Virtual Thread Java 25+).
- Native WebSocket + Protobuf Binary.
- Snapshot Redis cơ bản + Recovery.
- Rate Limiting + Idempotency kép cơ bản.
- Flutter Native + WebView JavascriptBridge.

### 1.2. Những gì CẮT BỎ (Làm sau MVP)
| Hạng mục | Lý do cắt |
|---|---|
| Room size 100 người | Quá phức tạp cho luồng broadcast |
| Team Mode / Draft / Co-editing | Chưa cần thiết cho bản MVP |
| Boss Battle, Team Race | Chỉ làm Quiz trước |
| Matchmaking MMR nâng cao | Chỉ cần tạo phòng đơn giản |
| Apache Kafka | Hoãn sang Giai đoạn 2 (Tuần 4 - Tuần 6) |
| ClickHouse | CẮT BỎ HOÀN TOÀN (Dùng Postgres Partitioning) |
| Adaptive LZ4HC | Snapshot Redis thường là đủ |
| Pekko Cluster Sharding | Dùng Static Routing (`room_id % 2`) trước |
| Go Gateway | Giữ Java Vert.x / Netty Gateway |
| Full Chaos Engineering | Chỉ test restart Pod cơ bản |

---

## 2. Công nghệ sử dụng trong MVP

| Thành phần | Công nghệ | Ghi chú |
|---|---|---|
| **Client** | Flutter + `webview_flutter` + Protobuf | Flutter Native giữ WebSocket |
| **Gateway** | Netty hoặc Vert.x (Java 25+) | 2 Pods tối thiểu (Stateless) |
| **Engine** | Java 25+ + Pekko Actor (Generational ZGC) | 2 Pods tối thiểu ($1.500 	ext{ rooms/pod}$) |
| **State** | Managed Redis (ElastiCache) | Session + Snapshot |
| **Database** | Managed PostgreSQL (RDS) | Auth + Lưu kết quả trận (`@Async`) |
| **Giao tiếp nội bộ** | gRPC Bi-directional Stream | Direct Gateway ↔ Engine Stream |
| **Monitoring** | Prometheus + Grafana cơ bản | Latency, CCU, Mailbox depth |

---

## 3. Lịch trình Chi tiết 3 Tuần (21 Ngày Làm việc)

### 📅 Tuần 1: DỰNG GAME ENGINE & WEBSOCKET CORE (ƯU TIÊN SỐ 1)
**Mục tiêu:** 1 phòng 12 học sinh chơi được quiz từ đầu đến cuối qua WebSocket + Protobuf, điểm cập nhật realtime.

| Ngày | Công việc chính | Kết quả mong đợi |
|---|---|---|
| **Ngày 1-2** | - Chốt file `game_message.proto`<br>- Generate code Java 25+ & Dart (Flutter)<br>- Setup project Gateway + Engine | Protobuf sẵn sàng, project chạy |
| **Ngày 3-4** | - Dựng Gateway: WebSocket + Auth Ticket + forward gRPC<br>- Kết nối Gateway ↔ Engine qua gRPC Stream | Client kết nối WebSocket thành công |
| **Ngày 5** | - Xây dựng `RoomActor` (Java 25+ / Pekko) cơ bản<br>- FSM: `LOBBY` ➔ `PLAYING` ➔ `FINISHED` | Actor xử lý được state phòng |
| **Ngày 6-7** | - Xử lý `SubmitAnswer` + chấm điểm đơn giản<br>- Broadcast Progress trong phòng<br>- Test nội bộ 1 phòng 12 người | Chơi được 1 ván quiz hoàn chỉnh |

### 📅 Tuần 2: Ghép Client + Ổn định Logic
**Mục tiêu:** Đủ tính năng để học sinh dùng thật trên App Flutter/WebView.

| Ngày | Công việc chính | Kết quả mong đợi |
|---|---|---|
| **Ngày 8-9** | - Thêm Guardrails (Timeout 60s + Safe Math)<br>- Idempotency cơ bản (sequence number)<br>- Rate Limiting tại Gateway | Chống crash và spam cơ bản |
| **Ngày 10-11** | - Flutter Native giữ WebSocket<br>- Truyền data xuống WebView qua `JavascriptChannel`<br>- Optimistic UI cơ bản | Client Flutter + WebView hoạt động |
| **Ngày 12-13** | - Snapshot vào Redis (`SET game:snapshot:{room_id}`)<br>- Cơ chế Reconnect + load state<br>- Bảng xếp hạng trong phòng | Reconnect không mất trạng thái |
| **Ngày 14** | - Tích hợp end-to-end hoàn chỉnh<br>- Fix bug từ test nội bộ | Hệ thống chạy mượt trên môi trường dev/staging |

### 📅 Tuần 3: Ổn định – Load Test – Release
**Mục tiêu:** MVP được triển khai, chịu được 2.000–3.000 CCU, có monitoring và runbook.

| Ngày | Công việc chính | Kết quả mong đợi |
|---|---|---|
| **Ngày 15-16** | - Thêm Metrics quan trọng (latency, mailbox depth, CCU, error rate)<br>- Tối ưu hot path (giảm memory allocation) | Có dashboard giám sát cơ bản |
| **Ngày 17-18** | - Load test **2.000 – 3.000 CCU**<br>- Tìm và fix bottleneck<br>- Tối ưu nếu cần | Đạt target CCU ổn định |
| **Ngày 19-20** | - Chaos test nhẹ (restart Engine Pod)<br>- Security review nhanh<br>- Viết Runbook ngắn | Biết cách xử lý khi có sự cố |
| **Ngày 21** | - Deploy MVP (giới hạn số phòng nếu cần)<br>- Theo dõi sát | **MVP CHÍNH THỨC RELEASE!** |

---

## 4. Bảng Kế hoạch Tiếp theo (Giai đoạn 2 & 3: Tối đa 2-3 Tuần/Giai đoạn)

| Giai đoạn | Thời gian | Mục tiêu & Công việc | Target CCU |
|---|---|---|---|
| **Giai đoạn 1 (Lean MVP)** | **3 Tuần** (Tuần 1 - 3) | Dựng Game Engine, Netty Gateway, Protobuf, Redis, Postgres `@Async`. | **2k - 3k CCU** |
| **Giai đoạn 2 (Scale-Out)** | **3 Tuần** (Tuần 4 - 6) | Tích hợp Kafka (AWS MSK), Pekko Cluster Sharding, Async Leaderboard Worker. | **10k - 20k CCU** |
| **Giai đoạn 3 (Enterprise)**| **2 Tuần** (Tuần 7 - 8) | Adaptive LZ4HC Compression, Postgres Partitioning, Multi-AZ HA, Stress Test 54k CCU. | **54.000+ CCU** |

*Lưu ý: ClickHouse được CẮT BỎ HOÀN TOÀN để giữ bộ máy gọn nhẹ.*
