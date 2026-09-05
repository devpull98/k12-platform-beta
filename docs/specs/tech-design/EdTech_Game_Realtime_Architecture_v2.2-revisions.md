# 🔧 BẢN ĐIỀU CHỈNH KIẾN TRÚC v2.1 → v2.2

## EdTech Realtime Multiplayer — Erratum & Architecture Revisions

> **Quan hệ với v2.1**: Tài liệu này KHÔNG thay thế `EdTech_Game_Realtime_Architecture_v2.1.md`.
> Nó liệt kê các điều chỉnh bắt buộc, mỗi mục ghi rõ *v2.1 viết gì → sai ở đâu → thay bằng gì*,
> đủ cụ thể để áp thẳng vào tài liệu gốc và sinh ra v2.2.
>
> **Phạm vi**: Realtime core (WebSocket Gateway + Game Engine). Matchmaking, Player Profile,
> Leaderboard Service, client Flutter nằm ngoài phạm vi bản điều chỉnh này.

---

## 0. Quyết định kiến trúc đã chốt (ADR tóm tắt)

| ID | Quyết định | Thay cho v2.1 |
|---|---|---|
| **ADR-1** | **Tách Gateway ↔ Engine, kênh nội bộ TCP + length-prefixed Protobuf (KHÔNG gRPC)** | v2.1 dùng gRPC bi-directional stream |
| **ADR-2** | **Pekko Typed + Cluster Sharding** làm runtime Engine, bắt buộc kèm SBR + fencing token | v2.1 mô tả Pekko/Akka nhưng ghép nhầm với Virtual Threads |
| **ADR-3** | **Recovery mục tiêu = client sống sót qua gián đoạn**, không phải "khôi phục dưới 50ms" | v2.1 cam kết `<30ms` / `<50ms` / `50–100ms` (3 giá trị mâu thuẫn) |
| **ADR-4** | **Tick coalescing (dirty-flag)** làm mặc định; fixed-rate tick chỉ bật cho game chuyển động liên tục | v2.1 mặc định fixed-rate 200ms cho mọi loại game |

### ADR-1 chi tiết — kênh nội bộ Gateway ↔ Engine

```
Client --WS + Protobuf--> [GW pod] --TCP, length-prefixed Protobuf--> [Engine pod]
                          stateless                                    ShardRegion
```

- **Envelope dùng lại `GameMessage` của v2.1**, bổ sung 2 field routing. Một schema cho cả biên
  và nội bộ, không codegen hai lần.
- **Framing**: 4-byte big-endian length prefix. Dùng `LengthFieldBasedFrameDecoder` /
  `LengthFieldPrepender` có sẵn của Netty — không tự viết parser.
- **Multiplex**: MỘT connection dùng chung cho mọi phòng giữa mỗi cặp (GW pod, Engine pod).
  Không phải một connection mỗi phòng.
- **Backpressure MỘT tầng duy nhất**: `channel.isWritable()` + high/low watermark trên chính
  socket nội bộ. Đây là lợi ích chính so với gRPC — gRPC có HTTP/2 flow-control window *và*
  app-level window chồng lên nhau, khi nghẽn rất khó xác định tầng nào đang chặn.

**Lý do bỏ gRPC**: gRPC mang lại codegen, deadline, interceptor, client-side LB. Ở đây
codegen không cần (đã có Protobuf envelope), deadline vô nghĩa với stream dài, LB do
Cluster Sharding lo. Thứ duy nhất còn lại là flow-control chồng tầng — một khoản nợ, không phải lợi ích.

---

## 1. Bảng tổng hợp 25 điều chỉnh

| ID | Mục v2.1 | Mức độ | Vấn đề |
|---|---|---|---|
| R-01 | 7.1.2 | 🔴 Sai số liệu | Mô hình tải sai ~300 lần |
| R-02 | 7.1, 25.2.5 | 🔴 Sai kết luận | Tick cố định 200ms làm *tăng* tải cho quiz |
| R-03 | 8.1, 13.1, 14.6, 25.2.1 | 🔴 Sai phép đo | Recovery SLA đo sai giai đoạn, 3 giá trị mâu thuẫn |
| R-04 | 23.1, 25.2.2 | 🟠 Giả định | Capacity/pod là số chưa đo, trình bày như sự thật |
| R-05 | 23.1 | 🟠 Thiết kế | 4 Engine pod = blast radius 13.500 học sinh |
| R-06 | 23.3 | 🔴 Đúng đắn | Split-brain không được nhắc tới |
| R-07 | 8.1, 14.3 | 🔴 Đúng đắn | Snapshot ↔ Kafka offset không atomic |
| R-08 | (thiếu) | 🔴 Đúng đắn | Không có server-authoritative timestamp |
| R-09 | 8.3.1 | 🔴 Bug | Rate limit theo IP chặn đứng trường học sau NAT |
| R-10 | (thiếu) | 🔴 Thiết kế | Connection storm đầu giờ chưa được thiết kế |
| R-11 | 22.2, 23.4 | 🔴 Bug | `ByteBuf.retain()` dùng sai → client thứ 2 trở đi nhận 0 byte |
| R-12 | 22.1.4 | 🟠 Không khả thi | Watchdog "ngắt" logic đang chạy — không làm được |
| R-13 | 13.2 | 🟠 Rỗng | Circuit breaker "chuyển sang giao tiếp dự phòng" — không tồn tại |
| R-14 | 2, 16, 25.2.6 | 🟠 Mâu thuẫn | Netty vs Vert.x vs WebFlux: ba câu trả lời khác nhau |
| R-15 | 14.7 | 🟠 Mâu thuẫn | Pekko + Virtual Threads: hai mô hình concurrency đánh nhau |
| R-16 | 2.1.6 vs 26 | 🟡 Mâu thuẫn | ClickHouse vừa có vừa bị cắt |
| R-17 | 8.1 vs MVP 1.2 | 🔴 Mâu thuẫn | MVP cắt Kafka nhưng recovery phụ thuộc Kafka replay |
| R-18 | 2.1.3, 13.2, 23.2 | 🟠 Thay đổi | gRPC → kênh nội bộ TCP + Protobuf (ADR-1) |
| R-19 | 14.1 | 🟠 Mâu thuẫn | Consistent hashing ở GW xung đột với Cluster Sharding |
| R-20 | 1.2 | 🟠 Thiếu | Tầng World / Teacher dashboard: bài toán fan-in bị bỏ trống |
| R-21 | (thiếu) | 🟡 Thiếu | Late join / backfill giữa câu hỏi |
| R-22 | (thiếu) | 🟠 Thiếu | Không có ước tính chi phí hạ tầng |
| R-23 | MVP ngày 17-18 | 🟠 Thiếu | Không có phương pháp load test |
| R-24 | 8.3.2 | 🟡 Xung đột | 5 msg/s/client xung đột với draft co-editing |
| R-25 | toàn tài liệu | 🟡 Chất lượng | LaTeX macro hỏng, điểm 8.2/10 không nguồn, Mục 26/27 lặp |

---

# PHẦN A — SAI ĐỊNH LƯỢNG

## R-01 · Mô hình tải sai ~300 lần

**Mục 7.1.2 viết:**
> 4.500 phòng × 12 học sinh × 12 msgs = 648.000 packets/sec

**Sai ở đâu**: hệ số `12 msgs` không có nguồn gốc. Trong quiz, mỗi học sinh nộp **một đáp án
mỗi ~20–30 giây**, không phải 12 gói/giây. Phép nhân còn trộn lẫn chiều inbound và outbound.

**Thay bằng — mô hình tải chuẩn:**

```
THAM SỐ
  R  = 4.500 phòng đồng thời
  S  = 12 học sinh/phòng
  Q  = 25 s  (thời gian trung bình cho 1 câu hỏi)
  U  = 54.000 CCU = R × S

INBOUND (client → server)
  submit_rate = U / Q = 54.000 / 25 ≈ 2.160 msg/s
  + heartbeat: U / 30s = 1.800 msg/s
  ────────────────────────────────────────
  Tổng inbound ≈ 4.000 msg/s

OUTBOUND (server → client), tick coalescing
  mỗi submit sinh 1 broadcast tới S client
  outbound = submit_rate × S = 2.160 × 12 ≈ 26.000 packet/s
  (coalescing gom nhiều submit gần nhau → thực tế thấp hơn)
```

Con số cần dùng khi thiết kế capacity là **~4.000 msg/s inbound, ~26.000 packet/s outbound**,
không phải 648.000. Sai lệch này đã đẩy mọi tính toán hạ tầng phía sau lệch theo.

---

## R-02 · Tick cố định 200ms làm *tăng* tải cho quiz

**Mục 7.1 kết luận**: 200ms là "con số vàng", tiết kiệm 58,3% packet.
**Mục 25.2.5 củng cố**: 200ms cho Tier 1/2, 50ms cho Tier 3.

**Sai ở đâu**: cả hai kết luận đều ngược với Tier 1.

| Mô hình | Outbound @ 54k CCU |
|---|---|
| Fixed-rate 200ms (v2.1) | 4.500 × 12 × 5 = **270.000 packet/s — liên tục, kể cả khi phòng im lặng** |
| Coalescing (chỉ bắn khi dirty) | ≈ **26.000 packet/s** |

Fixed-rate tick nặng hơn **~10 lần** cho đúng loại game mà MVP nhắm tới. Fixed-rate là pattern
đúng cho game *chuyển động liên tục* — nơi client cần luồng snapshot đều để nội suy (lerp).
Với quiz, giữa hai lần nộp bài **không có gì để nội suy**, tick chỉ gửi lại state không đổi.

**Thay bằng — coalescing tick (dirty-flag):**

```java
// Trong RoomActor
private boolean dirty = false;
private long lastFlushAt = 0;
private static final long MIN_INTERVAL_MS = 200;   // trần tần suất, không phải nhịp

void onSubmit(SubmitAnswer cmd) {
    applyToState(cmd);
    dirty = true;
    long now = clock.millis();
    if (now - lastFlushAt >= MIN_INTERVAL_MS) {
        flush(now);                       // đủ giãn cách → bắn ngay
    } else if (!flushScheduled) {
        // hẹn đúng thời điểm đủ 200ms kể từ lần bắn trước
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
    broadcast(buildDeltaSnapshot());
    dirty = false;
    lastFlushAt = now;
}
```

**Ngữ nghĩa thay đổi**: 200ms không còn là *nhịp phát*, nó là **trần tần suất
(rate ceiling)**. Phòng không hoạt động phát 0 gói. Trải nghiệm client không đổi — độ trễ
tối đa vẫn là 200ms — nhưng tải nền biến mất.

**Giữ nguyên từ v2.1**: phân loại Critical / Non-Critical ở Mục 13.3 và 14.2 vẫn đúng và
vẫn cần. `ANSWER_ACK`, `GAME_OVER`, `TEACHER_COMMAND` bypass hoàn toàn cơ chế trên.

**Khi nào bật fixed-rate**: chỉ cho game chuyển động liên tục (Boss Battle, Team Race dạng
đua vị trí). Bật theo **Game Definition**, thuộc tính `tick_mode: COALESCE | FIXED` +
`tick_interval_ms`. Không phải cấu hình toàn cục.

---

## R-03 · Recovery SLA đo sai giai đoạn

**v2.1 đưa ra 3 giá trị mâu thuẫn:**
- Mục 8.1: `< 20–30ms`
- Mục lục Phần III: `< 50ms`
- Mục 13.1, 14.6, 25.2.1: `50–100ms (p99)`

**Sai ở đâu**: cả ba đều chỉ đo *giai đoạn nạp lại state* (Redis read + deserialize +
Kafka seek). Đó là phần rẻ nhất và không phải phần chi phối. Thời gian thực tế:

```
T_total = T_detect + T_down + T_rebalance + T_load + T_reconnect

  T_detect     phi-accrual failure detector nhận ra node chết      5 – 10 s
  T_down       SBR quyết định + down node (stable-after)           5 – 10 s
  T_rebalance  ShardRegion tái phân bổ shard sang node còn sống    0.5 – 2 s
  T_load       Redis read + deserialize snapshot  ← "50ms" của v2.1  10 – 50 ms
  T_reconnect  client phát hiện đứt + backoff + nối lại            1 – 2 s
  ──────────────────────────────────────────────────────────────────────
  T_total ≈ 12 – 25 s
```

Không thể ép `T_detect` xuống 1s như Mục 23.3 mô tả — false positive khi GC pause hoặc
network blip sẽ down node đang khỏe, gây mất phòng hàng loạt. Đây không phải vấn đề tuning,
đây là đánh đổi cố hữu của failure detection.

**Thay bằng — đổi mục tiêu, không đổi con số:**

> Mục tiêu không phải "khôi phục dưới 50ms" mà là **học sinh không mất dữ liệu và không
> mất phiên khi hệ thống gián đoạn 15–25 giây**.

Cụ thể:

| Cơ chế | Trách nhiệm |
|---|---|
| **Client buffer** | Client giữ N submission gần nhất chưa được ACK; tự gửi lại khi nối lại |
| **Server dedupe** | `LastSeenSequenceTable` (đã có ở Mục 13.4) loại bỏ bản trùng — O(1), không cần Redis |
| **UI trạng thái** | Overlay "Đang đồng bộ…", KHÔNG văng lỗi, KHÔNG mất đáp án đã chọn |
| **Gia hạn deadline** | Khi phòng phục hồi, deadline câu hỏi được cộng bù đúng khoảng thời gian gián đoạn |

**SLA công bố mới:**

| Chỉ số | Cam kết |
|---|---|
| Mất dữ liệu đáp án khi sập Engine pod | **0** (nhờ client replay + dedupe) |
| Thời gian phòng hoạt động trở lại (p99) | **< 30 s** |
| Thời gian nạp lại state sau khi actor được tạo (p99) | **< 100 ms** |
| Học sinh phải thao tác lại thủ công | **Không** |

Ba dòng đầu là thứ khách hàng quan tâm. Dòng "< 100ms" là thứ v2.1 đang gọi nhầm là recovery time.

---

## R-04 · Capacity/pod là giả thuyết chưa đo

**Mục 23.1 viết** như sự thật: 5.000 WS/pod trên `2 vCPU / 4 GB`; 1.125 rooms/pod trên `4 vCPU / 8 GB`.

**Sai ở đâu**: không có benchmark nào chống lưng. Chúng là điểm khởi đầu hợp lý, không phải kết luận.

**Thay bằng** — đánh dấu rõ là giả thuyết, kèm phép thử để xác nhận:

```
GIẢ THUYẾT H1 — Gateway
  5.000 WS/pod @ 2 vCPU / 4 GB
  Kiểm chứng: 5.000 client giữ kết nối, 1 msg/25s/client, TLS bật.
  Đạt nếu: CPU < 60%, RSS < 3 GB, p99 fan-out < 5ms trong 30 phút.
  Rủi ro chính: TLS handshake CPU lúc đông (xem R-10), không phải steady state.

GIẢ THUYẾT H2 — Engine
  1.125 rooms/pod @ 4 vCPU / 8 GB
  Kiểm chứng: 1.125 RoomActor, tick coalescing, 12 học sinh/phòng, tải như R-01.
  Đạt nếu: p99 actor_processing_latency < 15ms, mailbox_depth p99 < 10,
           GC pause p99 < 10ms, RSS < 6 GB.
  Rủi ro chính: số lượng timer đồng thời (1.125 scheduled timer/pod), không phải CPU.
```

**Không được công bố con số pod cho 54k CCU trước khi H1 và H2 có kết quả đo.**

---

## R-05 · Blast radius 4 Engine pod

**Mục 23.1 viết**: 4 Engine pod cho 54.000 CCU.

**Sai ở đâu**: mất 1 pod = 1.125 phòng = **13.500 học sinh** gián đoạn cùng lúc. Và toàn bộ
1.125 phòng đó khôi phục đồng thời → 1.125 lượt Redis read + 1.125 actor spawn dồn vào một
thời điểm. Đây là *recovery thundering herd*, không phải "50ms mỗi phòng".

Mục 13.1 và 25.2.1 tính recovery cho **một phòng cô lập**, không mô hình hóa kịch bản này.

**Thay bằng:**

| | v2.1 | v2.2 |
|---|---|---|
| Số Engine pod @54k | 4 | **12 – 16** |
| Rooms/pod | 1.125 | ~300 – 375 |
| Học sinh ảnh hưởng khi mất 1 pod | 13.500 | **~4.000** |
| CPU/RAM mỗi pod | 4 vCPU / 8 GB | 2 vCPU / 4 GB |

Tổng tài nguyên gần như không đổi. Đánh đổi hiệu suất đóng gói để lấy blast radius nhỏ —
với hệ thống mà một sự cố ảnh hưởng trực tiếp tới giờ học đang diễn ra, đây là đánh đổi đúng.

Bổ sung bắt buộc: **stagger recovery**. Khi shard được tái phân bổ hàng loạt, actor nạp
snapshot theo lô có jitter ngẫu nhiên 0–500ms, tránh đồng loạt đập vào Redis.

---

# PHẦN B — LỖ HỔNG TÍNH ĐÚNG ĐẮN

## R-06 · Split-brain không được nhắc tới

**Mục 23.3 viết:**
> Akka Cluster / Kubernetes phát hiện Pod 2 bị sập (Heartbeat timeout 1s).
> Akka Sharding tự khởi tạo lại `RoomActor(101)` trên ENGINE POD 3.

**Sai ở đâu**: đây là hazard kinh điển của Cluster Sharding và v2.1 không nhắc một chữ.
Khi network partition xảy ra, hai phía cluster đều tin phía kia đã chết → **hai
`RoomActor(101)` cùng chạy trên hai pod**. Hậu quả: chấm điểm hai lần, hai nguồn broadcast
mâu thuẫn, hai luồng ghi snapshot đè lên nhau.

Heartbeat timeout 1s làm xác suất này *cao*, không phải thấp.

**Thay bằng — hai lớp bảo vệ, cả hai đều bắt buộc:**

### Lớp 1 — Split Brain Resolver

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

### Lớp 2 — Fencing token (SBR không thay thế được)

SBR đảm bảo *cuối cùng* chỉ còn một node, nhưng tồn tại cửa sổ `stable-after` (10s) mà cả
hai actor cùng sống. Trong cửa sổ đó cả hai ghi Redis. Vì vậy mọi write phải mang epoch:

```
Khi RoomActor khởi động:
  epoch = INCR room:epoch:{room_id}          -- đơn điệu tăng, không bao giờ lặp
  actor giữ epoch trong RAM suốt vòng đời

Mọi ghi snapshot dùng Lua script CAS:
  if redis.call('GET', KEYS[1]) == false
     or tonumber(redis.call('HGET', KEYS[1], 'epoch')) <= tonumber(ARGV[1]) then
       redis.call('HSET', KEYS[1], 'epoch', ARGV[1], 'data', ARGV[2])
       return 1
  else
       return 0     -- epoch cũ → từ chối
  end

Actor nhận về 0 → tự biết mình là bản zombie → dừng ngay, không broadcast thêm.
```

Đây chính là cơ chế v2.1 hoàn toàn thiếu. `Snapshot Versioning & CRC32` ở Mục 14.3 bảo vệ
chống *hỏng dữ liệu*, không bảo vệ chống *hai người ghi*.

### Lớp 3 — vận hành

```yaml
# Engine Deployment
terminationGracePeriodSeconds: 45        # đủ để CoordinatedShutdown leave cluster sạch
```
```hocon
pekko.coordinated-shutdown.exit-jvm = on
pekko.cluster.shutdown-after-unsuccessful-join-seed-nodes = 40s
```

Không có graceful leave, **mỗi lần rolling update là một lần nghi ngờ split-brain**.

---

## R-07 · Snapshot ↔ Kafka offset không atomic

**Mục 8.1 viết**: khôi phục = nạp snapshot Redis, rồi replay Kafka từ `last_kafka_offset + 1`.

**Sai ở đâu**: snapshot ghi async (Mục 7.2: mỗi 1–3 giây), Kafka produce cũng async.
Hai thao tác này **không nằm trong một transaction**. Vậy `last_kafka_offset` lưu trong
snapshot có thể không tương ứng với state trong chính snapshot đó:

- Nếu offset được ghi *trước* khi produce hoàn tất → replay bỏ sót sự kiện → **mất đáp án**.
- Nếu offset được ghi *sau* → replay lặp sự kiện → cần idempotent replay (may mắn là
  `LastSeenSequenceTable` xử lý được, nhưng chỉ khi bảng đó nằm *trong* snapshot).

Cộng thêm R-17: MVP cắt Kafka hoàn toàn → recovery MVP chỉ có snapshot → **mất tới 3 giây
đáp án**. Với bài kiểm tra có điểm, đáp án học sinh biến mất là lỗi nghiệp vụ, không phải
"học sinh không nhận ra".

**Thay bằng — client-side replay làm nguồn đúng đắn chính:**

```
1. Client giữ ring buffer N=10 submission gần nhất, kèm sequence_number.
2. Submission được xóa khỏi buffer khi nhận ANSWER_ACK.
3. Khi reconnect, client gửi RESYNC { last_acked_seq, pending[] }.
4. Actor áp dụng pending[] qua LastSeenSequenceTable → bản trùng bị loại O(1).
5. Actor trả về state hiện tại → client vẽ lại UI.
```

**Hệ quả**: recovery không còn phụ thuộc Kafka. Kafka trở về đúng vai trò của nó —
event log cho analytics/audit — và có thể hoãn sang phase sau mà không tạo lỗ hổng nào.
Snapshot Redis trở thành *tối ưu tốc độ* (đỡ phải replay từ client), không phải *nguồn đúng đắn*.

**Bắt buộc**: `LastSeenSequenceTable` phải nằm **trong** snapshot. Nếu không, sau recovery
actor mất trí nhớ về sequence và sẽ chấp nhận lại bản trùng.

---

## R-08 · Thiếu server-authoritative timestamp

**v2.1 không có**. Mục 9.3 chỉ nói về clock sync để **vẽ đồng hồ đếm ngược trên UI**.

**Sai ở đâu**: điểm số phụ thuộc tốc độ trả lời (Mục 3, `Score Calculator`: "độ chính xác
và tốc độ"). Nếu thời điểm trả lời do client cung cấp, học sinh sửa được điểm bằng cách
sửa timestamp. Đây là lỗ hổng gian lận trực tiếp trong hệ thống có triết lý
"Authoritative Server" ghi ở Mục 1.3.1.

**Thay bằng:**

```
1. Actor ghi server_question_started_at khi phát QUESTION_STARTED.
2. Khi SubmitAnswer tới, actor đóng dấu server_received_at = clock.millis() NGAY tại
   thời điểm lấy khỏi mailbox — KHÔNG dùng field timestamp trong GameMessage.
3. response_time_ms = server_received_at − server_question_started_at
4. Từ chối nếu server_received_at > deadline + GRACE (GRACE = 500ms, bù RTT mạng).
5. Field `timestamp` trong GameMessage chỉ dùng để đo latency/telemetry — KHÔNG BAO GIỜ
   dùng để tính điểm.
```

Cần ghi rõ điều 5 vào Mục 6 (Protobuf schema) dưới dạng comment trong `.proto`, nếu không
sẽ có người dùng nhầm.

**Lưu ý về công bằng**: cách tính này khiến học sinh mạng chậm bị thiệt (RTT tính vào thời
gian trả lời). Chấp nhận được — đó là đánh đổi bắt buộc của authoritative server, và `GRACE`
hấp thụ phần lớn chênh lệch. Không được "sửa" bằng cách tin timestamp client.

---

## R-09 · Rate limit theo IP chặn đứng trường học sau NAT

**Mục 8.3.1 viết:**
> Giới hạn tối đa 5 lượt Handshake WebSocket / 1 địa chỉ IP / 1 phút. Quá ngưỡng trả về HTTP 429.

**Sai ở đâu**: đây là **bug chặn khách hàng mục tiêu**. Một trường 500 học sinh đi ra
Internet qua **một IP public NAT duy nhất**. Học sinh thứ 6 trở đi nhận 429. Toàn bộ mô hình
kinh doanh của hệ thống là "cả lớp/cả trường cùng vào một lúc" — rule này chặn đúng kịch bản đó.

**Thay bằng — rate limit phân tầng, khóa theo danh tính:**

| Tầng | Khóa | Ngưỡng | Mục đích |
|---|---|---|---|
| L1 — chống DDoS thô | IP | **300 handshake/phút** | Chỉ chặn flood thực sự; không bao giờ chạm tới trong dùng bình thường |
| L2 — chống lạm dụng tài khoản | `student_id` (từ JWT) | **10 handshake/phút** | Chống reconnect loop, script |
| L3 — bảo vệ dung lượng | toàn cục/pod | admission control | Xem R-10 |

Ngưỡng L1 phải đặt theo **số học sinh tối đa của một trường**, không theo trực giác về IP.
Nếu có trường lớn hơn 300 học sinh cùng lúc, nâng L1 hoặc bỏ hẳn L1 và chỉ dựa vào L2 + L3.

---

## R-10 · Connection storm đầu giờ chưa được thiết kế

**v2.1 không có**. Mục 23.1 chỉ tính steady state (5.000 WS/pod duy trì).

**Sai ở đâu**: tải EdTech không phẳng. 09:00 giáo viên bấm Bắt đầu →
**54.000 kết nối trong ~15 giây**:

```
54.000 TLS handshake        ← đắt nhất, ~1-3ms CPU mỗi lượt
54.000 JWT chữ ký verify
 4.500 RoomActor spawn
 4.500 Redis write (lease + epoch)
```

Đây là thời điểm khó nhất của hệ thống. 54.000 TLS handshake trong 15s = 3.600/s; chia cho
10 GW pod = 360 handshake/s/pod trên 2 vCPU. Riêng TLS đã có thể chiếm hết CPU, trong khi
giả thuyết H1 (R-04) chỉ đo steady state.

**Thay bằng — 4 cơ chế:**

**1. Pre-spawn actor lúc tạo phòng, không lúc học sinh vào**
Giáo viên tạo phiên → actor được spawn ngay và ở trạng thái `LOBBY`. Tới 09:00 chỉ còn
chi phí kết nối, không còn 4.500 lượt spawn dồn cục.

**2. Staggered join do server điều phối**
Response của `POST /session/{id}/join` trả kèm `connect_after_ms` (jitter 0–5.000ms).
Client đợi đúng khoảng đó rồi mới mở WebSocket. Trải 54.000 kết nối ra 5 giây thay vì dồn
vào 1 giây. Học sinh không cảm nhận được vì màn hình chờ vẫn đang hiển thị.

**3. Admission control ở Gateway**
Mỗi GW pod có ngưỡng `max_handshake_per_sec` (đo được từ H1). Vượt ngưỡng → trả
`503 + Retry-After`, KHÔNG phải 429 (429 khiến client hiểu nhầm là bị phạt). Client backoff
và thử lại. Thà chậm 3 giây còn hơn pod chết.

**4. Session resumption TLS**
Bật TLS session ticket / session ID resumption. Reconnect sau đứt mạng bỏ qua full
handshake — giảm phần lớn chi phí CPU của kịch bản R-03 (hàng nghìn client nối lại cùng lúc
sau khi Engine pod phục hồi).

**Bổ sung vào H1**: phải đo *handshake rate* riêng, không chỉ steady-state connection count.

---

## R-11 · `ByteBuf.retain()` dùng sai — client thứ 2 trở đi nhận 0 byte

**Mục 23.4 viết:**
> Gateway Pod A nhận gói tin → Dùng `ByteBuf.retain()` bắn cho 6 WebSocket clients cục bộ.

**Mục 2.1.3.A viết đúng**: `ByteBuf.retainedDuplicate()`.

**Sai ở đâu**: `retain()` chỉ tăng reference count — nó **không tạo reader index riêng**.
Nhiều `writeAndFlush` trên *cùng một* `ByteBuf`: client đầu tiên đọc hết buffer đẩy
`readerIndex` tới cuối, các client sau ghi **0 byte**. Đây là bug thật, không phải khác biệt
văn phong, và nó biểu hiện dưới dạng "một số học sinh không nhận được update" — cực khó truy.

**Thay bằng:**

```java
// SAI — v2.1 Mục 23.4
ByteBuf frame = buildFrame(payload);
for (Channel ch : roomChannels) {
    ch.writeAndFlush(new BinaryWebSocketFrame(frame.retain()));  // client 2+ nhận 0 byte
}

// ĐÚNG
ByteBuf frame = buildFrame(payload);
try {
    for (Channel ch : roomChannels) {
        if (!ch.isWritable()) { dropNonCritical(ch); continue; }   // R-24 / Mục 14.4
        ch.writeAndFlush(new BinaryWebSocketFrame(frame.retainedDuplicate()));
    }
} finally {
    frame.release();     // nhả bản gốc — thiếu dòng này là rò rỉ direct memory
}
```

`retainedDuplicate()` chia sẻ vùng nhớ (vẫn zero-copy, đúng như ý đồ v2.1) nhưng mỗi bản có
reader/writer index độc lập.

**Kiểm tra bắt buộc**: bật `-Dio.netty.leakDetection.level=paranoid` trên môi trường staging.
Không có nó, rò rỉ direct memory chỉ lộ ra sau vài giờ chạy tải.

**Sửa cả hai chỗ**: Mục 22.2 (sơ đồ) và Mục 23.4 (bước 2, 3) phải dùng cùng một API với Mục 2.1.3.A.

---

## R-12 · Watchdog 10ms "ngắt" logic đang chạy — không làm được

**Mục 22.1.4 viết:**
> Nếu 1 logic chạy quá 10ms, Watchdog Timer sẽ **ngắt** và đẩy warning log.

**Sai ở đâu**: không có cách an toàn nào để ngắt code Java đang chạy trên một thread khác.
`Thread.stop()` đã bị gỡ bỏ. `Thread.interrupt()` chỉ có tác dụng tại các điểm blocking —
một vòng lặp CPU sẽ phớt lờ nó hoàn toàn. Nếu Game Definition có vòng lặp vô tận, actor
**sẽ treo** bất chấp watchdog.

**Thay bằng — ba cơ chế thật, thay cho một cơ chế không tồn tại:**

**1. Đo sau, cảnh báo (thay cho "ngắt")**
```java
long t0 = System.nanoTime();
state.handle(msg);
long durUs = (System.nanoTime() - t0) / 1000;
actorProcessingTimer.record(durUs, MICROSECONDS);
if (durUs > 10_000) {
    log.warn("room={} msg={} over budget {}us", roomId, msg.type(), durUs);
}
```
Đây là *quan sát*, và nó đủ dùng cho mục đích thật của watchdog: phát hiện logic chậm để dev sửa.

**2. Chặn ở đầu vào, không chặn lúc chạy (phòng ngừa thật sự)**
Vòng lặp vô tận phải bị chặn khi **nạp Game Definition**, không phải khi thực thi:
- Validate DAG các step lúc upload — phát hiện chu trình bằng duyệt đồ thị.
- Cấm biểu thức tùy ý; công thức điểm dùng DSL giới hạn (không Turing-complete), không script engine.
- `MAX_TRANSITIONS` của Mục 21.1.2 giữ nguyên — nó đúng và nó là hàng rào cuối.

**3. Cách ly ở tầng pod (hàng rào cuối cùng)**
Nếu một actor vẫn treo được, thứ cứu hệ thống là **dispatcher riêng** cho logic từ Game
Definition, và liveness probe fail → K8s restart pod. Chấp nhận mất pod, không mất cluster.

```hocon
game-logic-dispatcher {
  type = Dispatcher
  executor = "fork-join-executor"
  fork-join-executor { parallelism-min = 4, parallelism-max = 16 }
  throughput = 1
}
```

**Viết lại Mục 22.1.4** cho đúng: watchdog **đo và cảnh báo**, nó không ngắt được. Việc
phòng ngừa nằm ở validation lúc upload Definition.

---

## R-13 · Circuit breaker "chuyển sang giao tiếp dự phòng" — không tồn tại

**Mục 13.2 viết:**
> Khi Engine Pod không phản hồi trong 500ms, Gateway lập tức ngắt kết nối (Open Circuit) và
> **chuyển sang giao tiếp dự phòng**.

**Sai ở đâu**: "giao tiếp dự phòng" là gì? Không có. Room state là stateful và chỉ tồn tại
ở đúng một nơi — không có Engine pod thay thế để chuyển sang. Đây là câu chữ mượn từ pattern
stateless service, dán vào chỗ không áp dụng được.

Circuit breaker chỉ có ý nghĩa khi **có nơi khác để đi**. Ở đây không có.

**Thay bằng — nói đúng hành vi thật:**

```
Engine pod không phản hồi:

1. GW KHÔNG mở circuit breaker cho room traffic — không có fallback.
2. GW gửi CONNECTION_DEGRADED tới các client thuộc phòng trên pod đó.
3. Client hiển thị "Đang kết nối lại…", GIỮ NGUYÊN đáp án đã chọn trong buffer (R-07).
4. GW giữ nguyên WebSocket của client — KHÔNG ngắt. Ngắt socket sẽ tạo ra 54.000 lượt
   reconnect + TLS handshake đồng loạt, biến sự cố một pod thành sự cố toàn hệ thống (R-10).
5. Khi Cluster Sharding tái tạo actor ở pod khác, GW học lại route (R-19) và nối lại luồng.
6. Client gửi RESYNC → không mất đáp án nào.
```

**Bulkhead thread isolation ở Mục 13.2.1 giữ nguyên — phần đó đúng và cần.** Chỉ bỏ phần
circuit breaker + "giao tiếp dự phòng".

Circuit breaker **vẫn dùng** cho các lời gọi *stateless* thật sự (Auth service, Profile
service) — không dùng cho kênh Gateway↔Engine.

---

# PHẦN C — MÂU THUẪN NỘI TẠI

## R-14 · Netty vs Vert.x vs WebFlux — ba câu trả lời

**v2.1 nói ba điều khác nhau:**
- Mục 2 + banner đầu tài liệu: **Netty**
- Mục 16.2 + 25.2.6: khuyến nghị **Vert.x**
- File MVP plan, bảng Mục 2: "**Netty hoặc Vert.x**"

**Chốt: Netty trực tiếp cho Gateway.**

Lý do — và phải thừa nhận đây là lựa chọn đắt hơn về công sức:
1. ADR-1 yêu cầu kiểm soát chính xác framing, watermark, `retainedDuplicate()` fan-out và
   backpressure một tầng. Đây đúng là những API mà Vert.x bọc lại — và bọc lại là chính thứ
   ta không muốn ở đây.
2. Kênh nội bộ (ADR-1) và biên WebSocket dùng chung một mô hình `ChannelPipeline` — một
   mô hình tinh thần, không phải hai.
3. Vert.x là Netty được bọc; ở tầng này lớp bọc thêm khoảng cách chẩn đoán mà không bớt việc.

**Xóa Mục 16 khỏi tài liệu** (so sánh Vert.x vs WebFlux) hoặc chuyển thành phụ lục
"phương án đã cân nhắc và loại". Giữ nó ở dạng khuyến nghị mâu thuẫn là nguồn nhầm lẫn cho dev.

**Xóa Mục 25.2.6** (lộ trình tiến hóa sang Go Gateway) khỏi phạm vi hiện tại. Nó là suy đoán
cho mốc CCU > 100.000, gấp đôi target, và nó làm loãng quyết định của phase 1.

---

## R-15 · Pekko + Virtual Threads — hai mô hình concurrency đánh nhau

**Mục 14.7 viết:**
> Mỗi Actor Mailbox được thực thi bởi 1 **Virtual Thread**
> (`Executors.newVirtualThreadPerTaskExecutor()`) thay vì OS Platform Thread.

**Sai ở đâu**: Pekko không hoạt động như vậy. Actor **không sở hữu thread** — chúng được
dispatcher lập lịch lên một pool nhỏ (thường = số core). 1.125 actor trên một pod đã chạy
tốt trên ~8 platform thread. Ép mỗi mailbox lên một virtual thread không tiết kiệm gì (không
có thread nào đang bị lãng phí) và sẽ đánh nhau với dispatcher.

Con số "1MB → 2KB mỗi thread" là đúng về virtual thread nói chung, nhưng **không áp dụng ở
đây** vì mô hình Pekko chưa bao giờ tốn 1 thread mỗi actor.

**Thay bằng — nói rõ virtual thread dùng ở đâu:**

| Thành phần | Mô hình | Lý do |
|---|---|---|
| **RoomActor** | Pekko dispatcher (platform thread) | Actor đã ghép kênh sẵn; virtual thread không thêm gì |
| **Netty EventLoop** (GW + kênh nội bộ) | Platform thread, cố định = cores × 2 | Non-blocking; virtual thread phản tác dụng |
| **Redis snapshot I/O, Postgres ghi kết quả** | **Virtual thread** ✅ | Blocking I/O, nhiều tác vụ đồng thời — đúng chỗ dùng |
| **Kafka producer** (khi có) | Virtual thread hoặc async client | Blocking I/O |

**Giữ nguyên và làm nổi bật Mục 22.1.3** (chống thread pinning: dùng `ReentrantLock` thay
`synchronized`) — nó vẫn đúng và vẫn quan trọng, nhưng chỉ áp dụng cho tầng I/O ở bảng trên,
không cho actor.

**Viết lại tiêu đề Mục 14.7** thành "Phân bổ mô hình thực thi theo tầng" và bỏ tuyên bố
"1MB → 2KB cho hàng chục ngàn Actor".

---

## R-16 · ClickHouse vừa có vừa bị cắt

- Mục 2 (sơ đồ) + Mục 2.1.6: ClickHouse nằm trong tầng dữ liệu
- Mục 26: "**CẮT BỎ HOÀN TOÀN**"
- Mục 25.2.4: "tạm hoãn cho tới khi log vượt 10GB/ngày"

**Chốt: cắt.** Xóa ClickHouse khỏi sơ đồ Mục 2 và khỏi Mục 2.1.6. Giữ đúng một dòng ở
Mục 25.2.4 làm ghi chú tương lai: *analytics ghi vào PostgreSQL partitioned theo tháng;
cân nhắc lại kho OLAP khi vượt 10 GB/ngày.*

Sơ đồ kiến trúc là thứ dev nhìn đầu tiên — để thành phần đã bị cắt nằm trong đó nghĩa là
sẽ có người triển khai nó.

---

## R-17 · MVP cắt Kafka nhưng recovery phụ thuộc Kafka

- MVP plan Mục 1.2: "Apache Kafka — Hoãn sang Giai đoạn 2"
- v2.1 Mục 8.1 bước 4: recovery = Kafka Replay từ `last_kafka_offset + 1`

→ **MVP không có recovery path.**

**Giải quyết bằng R-07**: recovery dựa trên client-side replay + snapshot, không phụ thuộc
Kafka. Sau khi áp R-07, mâu thuẫn này biến mất và Kafka trở thành thành phần thuần
analytics/audit — hoãn được thật sự, không phải hoãn trên giấy.

**Viết lại Mục 8.1:**

```
1. Actor được Cluster Sharding tạo lại trên pod còn sống.
2. Giành lease + epoch:  INCR room:epoch:{room_id}   (R-06)
3. Nạp snapshot Redis, kiểm CRC32 (Mục 14.3 giữ nguyên).
   Checksum sai → bắt đầu từ state rỗng, dựa hoàn toàn vào bước 5.
4. Actor vào trạng thái RESYNCING, chấp nhận RESYNC, chưa broadcast.
5. Client gửi RESYNC { last_acked_seq, pending[] } → áp qua LastSeenSequenceTable.
6. Gia hạn deadline câu hỏi hiện tại đúng bằng khoảng gián đoạn.
7. Chuyển về PLAYING, broadcast state đầy đủ một lần cho toàn phòng.
```

Kafka không xuất hiện trong luồng này. Đó là điểm mấu chốt.

---

## R-18 · gRPC → kênh nội bộ TCP + Protobuf

Áp dụng **ADR-1**. Các mục cần sửa: **2.1.3**, **4** (bảng kênh giao tiếp), **13.2**,
**23.2 bước 2–4**, **MVP plan bảng Mục 2**.

Thay mọi chỗ "gRPC Bi-directional Stream" bằng:

> **Internal Frame Channel** — TCP dài hạn, length-prefixed Protobuf, một connection dùng
> chung cho mọi phòng giữa mỗi cặp (GW pod, Engine pod), multiplex bằng `room_id` trong envelope.

**Sửa Mục 4** (bảng Kênh Giao tiếp), dòng "Internal Routing":

| Kênh | Giao thức | Tầng | Mục đích |
|---|---|---|---|
| Internal Routing | **TCP + length-prefixed Protobuf** | GW ↔ Engine | Định tuyến tin nhắn tới đúng RoomActor, backpressure một tầng |

---

## R-19 · Consistent hashing ở Gateway xung đột với Cluster Sharding

**Mục 14.1 viết**: GW tính `hash(room_id)` trên Consistent Hashing Ring để định tuyến thẳng
tới Engine pod giữ phòng đó.

**Sai ở đâu**: với Cluster Sharding, **Pekko quyết định placement, không phải hàm hash của
Gateway**. Sau một lần rebalance (thêm/bớt pod, hoặc node down), vòng hash ở GW sẽ trỏ sai
và không có cách nào biết. Hai nguồn sự thật về vị trí phòng.

**Thay bằng — lazy-learned routing (không Redis, tự lành):**

```
1. GW giữ connection tới TẤT CẢ Engine pod (số pod nhỏ: 12–16).
2. Chưa biết room → gửi tới bất kỳ pod nào (round-robin).
   ShardRegion tự forward nội bộ tới đúng node.
3. Response mang header owner_pod_id (do Engine đóng dấu).
   GW cache room_id -> pod. Từ đó gửi thẳng, không qua hop nội bộ.
4. Rebalance làm cache sai → pod nhận được trả NOT_OWNER kèm owner hiện tại,
   hoặc đơn giản là forward tiếp và đóng dấu lại → GW tự cập nhật.
5. Connection tới một Engine pod đứt → xóa mọi entry cache trỏ tới pod đó.
```

Ưu điểm so với Mục 14.1: không cần Redis trên hot path, không cần TTL, và **không thể lệch
khỏi sự thật** vì sự thật do chính Engine đóng dấu vào response.

**Xóa Mục 14.1**. Thay bằng mô tả trên.

Ghi chú: cache này thay luôn cả `Redis Session Registry` (`room:routing:{id}`) ở Mục 2.1.6
và 23.1 **cho mục đích định tuyến**. Redis vẫn giữ registry cho mục đích khác (dashboard
giáo viên, vận hành), nhưng không nằm trên đường đi của gói tin.

---

# PHẦN D — THIẾU HOÀN TOÀN

## R-20 · Tầng World / Teacher dashboard — bài toán fan-in bị bỏ trống

**Mục 1.2 định nghĩa** `World` = phiên điều phối của giáo viên (`teacher_id`, `session_id`),
rồi **không có mục nào khác nhắc tới nó**. Toàn bộ kiến trúc dừng ở tầng Room.

**Vấn đề bị bỏ sót**: giáo viên/quản trị cần dashboard theo dõi **nhiều phòng đồng thời**
(một kỳ thi = hàng nghìn phòng). Đây là bài toán **fan-in** — gom trạng thái từ N actor nằm
rải trên nhiều pod về một nơi — và nó *khó hơn* fan-out mà tài liệu đã dành nhiều mục để giải.

Nếu làm ngây thơ (dashboard poll từng phòng, hoặc mỗi actor push mỗi 200ms lên dashboard),
5.000 phòng × 5 lần/giây = 25.000 update/s đổ vào một service — tự tạo ra một
broadcast storm ở chiều ngược lại.

**Thiết kế bổ sung — tổng hợp phân tầng:**

```
   4.500 RoomActor
        │  RoomSummary mỗi 2s, CHỈ khi có thay đổi
        │  (~120 bytes: room_id, state, step_index, avg_score,
        │   students_connected, students_answered)
        ▼
   SessionAggregator (Cluster Singleton per session_id)
        │  gom theo session, tự throttle 1 Hz
        ▼
   Teacher WebSocket (qua chính GW, scope = SESSION)
```

Quy tắc bắt buộc:
- **Không gói tin nào của học sinh đi tới dashboard.** Dashboard chỉ nhận bản tóm tắt phòng.
- Tần suất tóm tắt (2s) độc lập hoàn toàn với tick trong phòng (200ms). Giáo viên không cần
  và không dùng được độ phân giải 200ms trên 5.000 phòng.
- Dashboard mở >200 phòng → chuyển sang **chỉ số tổng hợp + danh sách ngoại lệ**
  (phòng bị kẹt, phòng có học sinh mất kết nối), không phải lưới 5.000 ô.
- `SessionAggregator` là Cluster Singleton theo `session_id`, không phải singleton toàn cục.

**Lệnh từ giáo viên (`TEACHER_PAUSE` toàn phiên)** đi chiều ngược lại: Aggregator phát tán
tới các ShardRegion theo lô, có jitter, không đồng loạt.

**Cần bổ sung một mục mới vào v2.2** (đề xuất: Mục 3.6 hoặc mục riêng sau Mục 10).

---

## R-21 · Late join / backfill giữa câu hỏi

**v2.1 không có**. Mục 9.1 chỉ xử lý *reconnect* (học sinh đã từng ở trong phòng).

Thiếu: học sinh **vào lần đầu** khi phòng đã ở giữa câu hỏi thứ 3 — đi muộn, đổi thiết bị,
hoặc được giáo viên thêm vào giữa chừng.

**Thay bằng — quy định rõ trong FSM:**

```
JOIN khi phòng ở PLAYING:
  1. Actor cấp student_index còn trống. Nếu hết (đủ 12) → từ chối ROOM_FULL.
  2. Trả về ROOM_STATE_FULL: step hiện tại, deadline còn lại, điểm mọi người,
     KHÔNG kèm đáp án của các câu đã qua.
  3. Chính sách điểm các câu đã bỏ lỡ — phải do Game Definition quyết định, không hardcode:
       missed_step_policy: ZERO | SKIP | ALLOW_LATE
     • ZERO       — tính 0 điểm cho câu đã qua (mặc định, công bằng nhất)
     • SKIP       — không tính vào mẫu số khi xếp hạng
     • ALLOW_LATE — cho làm bù (chỉ hợp lý với bài tự học, không hợp lý khi thi)
  4. Broadcast STUDENT_JOINED cho cả phòng.
```

Điều 3 là **quyết định nghiệp vụ, không phải kỹ thuật** — cần Product chốt trước khi code.

---

## R-22 · Không có ước tính chi phí hạ tầng

**v2.1 không có một con số USD nào**, dù đề xuất đầy đủ 10–12 GW pod, 4 Engine pod,
Redis Cluster 3+3, Kafka 3 broker, RDS, MongoDB, ClickHouse.

Với EdTech, chi phí/CCU thường là ràng buộc kiến trúc *cứng hơn* latency. Một kiến trúc
đạt p99 < 100ms nhưng lỗ trên mỗi học sinh là kiến trúc sai.

**Cần bổ sung — khung ước tính (điền số thật theo region AWS đang dùng):**

| Thành phần | Cấu hình @54k CCU | Ghi chú |
|---|---|---|
| Gateway pods | 10–12 × (2 vCPU / 4 GB) | |
| Engine pods | 12–16 × (2 vCPU / 4 GB) — theo R-05 | |
| Redis (ElastiCache) | 3 shard + replica | |
| PostgreSQL (RDS) | Multi-AZ | |
| Kafka (MSK) | 3 broker | Hoãn được sau R-07/R-17 |
| Network egress | ~26.000 pkt/s × ~150 B | **Thường bị quên — cần tính riêng** |
| **Chi phí / CCU / giờ** | — | **Chỉ số cần theo dõi liên tục** |

Hai câu hỏi phải trả lời trước khi chốt kiến trúc:
1. **Chi phí ở tải nền** (ngoài giờ học, ~0 CCU) là bao nhiêu? Nếu hệ thống chỉ chạy
   4 tiếng/ngày, chi phí *nhàn rỗi* mới là khoản chi phối — và điều đó nghiêng mạnh về
   scale-to-zero, ngược với thiết kế cluster luôn-bật hiện tại.
2. **Redis Cluster 3+3 và Kafka 3 broker có cần từ đầu không?** Sau R-07, Kafka rời khỏi
   đường đi quan trọng — hoãn được. Redis một node có replica có thể đủ cho phase 1.

---

## R-23 · Không có phương pháp load test

**MVP plan ngày 17–18**: "Load test 2.000–3.000 CCU". Không nói bằng công cụ gì.

Sinh 3.000 client WebSocket nói Protobuf, có state phiên (join → nhận câu hỏi → nộp đáp án
đúng sequence) **tự nó là một dự án con**. Không phải một dòng trong lịch trình.

**Cần bổ sung:**

| Hạng mục | Quyết định |
|---|---|
| Công cụ | **Gatling** (Scala/Java — dùng lại Protobuf class đã codegen) hoặc **k6** với extension WS nhị phân |
| Kịch bản | Bắt buộc gồm **connection storm** (R-10): toàn bộ CCU nối trong 15s |
| Thời lượng | ≥ 30 phút để lộ rò rỉ direct memory (R-11) và GC drift |
| Chỉ số nghiệm thu | p99 end-to-end, `actor_mailbox_queue_depth`, RSS, GC pause, packet/s thực đo |
| Đối chiếu | So sánh với H1/H2 (R-04) — **đây mới là thứ xác nhận số pod** |
| Công sức | **3–5 ngày cho riêng harness**, tách khỏi ngày chạy test |

Không có harness này thì mọi con số capacity trong tài liệu vẫn là giả thuyết.

---

## R-24 · 5 msg/s/client xung đột với draft co-editing

**Mục 8.3.2**: Bucket4j, capacity 5, refill 5 token/s, áp cho cả `SUBMIT_ANSWER` **và**
`UPDATE_DRAFT`.

**Xung đột**: `UPDATE_DRAFT` (Tier 2, gõ nháp chung trong nhóm) sinh sự kiện theo nhịp gõ
phím — dễ vượt 5/s. Học sinh gõ nhanh sẽ bị `RATE_LIMIT_EXCEEDED` giữa lúc đang thảo luận.

**Thay bằng — bucket riêng theo loại thông điệp:**

| Loại | Capacity | Refill | Ghi chú |
|---|---|---|---|
| `SUBMIT_ANSWER` | 3 | 1/s | Hành động hiếm; siết chặt được |
| `UPDATE_DRAFT` | 10 | 10/s | Client **bắt buộc debounce 150ms** trước khi gửi |
| `HEARTBEAT` | 2 | 1/30s | |
| Tổng mọi loại | 15 | 15/s | Hàng rào cuối |

**Bổ sung ràng buộc phía client** vào Mục 9: `UPDATE_DRAFT` phải debounce 150ms và gửi
**delta**, không gửi toàn bộ nội dung nháp. Không có ràng buộc này, không ngưỡng server nào đúng.

---

## R-25 · Chất lượng tài liệu

| Vấn đề | Xử lý |
|---|---|
| **Macro LaTeX hỏng toàn tài liệu** — `	ext{...}` (ký tự tab thay vì `\t`) | Tìm/thay toàn bộ. Nếu đây là tài liệu bàn giao cho đối tác thì không phải lỗi hình thức |
| **"Đánh giá độc lập 8.2/10"** (Mục 25) không nguồn, tự tham chiếu | Xóa, hoặc ghi rõ ai đánh giá theo tiêu chí nào |
| **Mục 26 và 27 lặp nguyên văn** file MVP plan | Giữ một nơi, nơi kia trỏ link |
| **Recovery SLA xuất hiện 3 giá trị** | Đã xử lý ở R-03 — một con số duy nhất toàn tài liệu |
| **"Java 25+ / Generational ZGC / Compact Object Headers"** lặp như khẩu hiệu ở banner | Với heap 4–8 GB và state/phòng nhỏ, **G1 hoàn toàn đủ** và dễ tune hơn; ZGC đánh đổi ~10–15% throughput. Bỏ khỏi banner, chuyển thành một dòng trong mục vận hành: *"đo cả G1 và ZGC ở H2, chọn theo số liệu"* |

---

# 2. Bảng SLA thay thế (thay Mục 14.6)

| Chỉ số | v2.1 | v2.2 | Lý do đổi |
|---|---|---|---|
| Actor processing time (p99) | < 15ms | **< 15ms** | Giữ — hợp lý |
| Snapshot serialize + compress (p99) | < 5ms | **< 5ms** | Giữ |
| Snapshot payload size | < 5KB | **< 5KB** | Giữ |
| Kafka produce latency | < 10ms | *(bỏ khỏi SLA cốt lõi)* | Kafka rời hot path sau R-07 |
| Gateway fan-out latency (p99) | < 5ms | **< 5ms** | Giữ |
| **Full recovery time (p99)** | < 50–100ms | **< 30 s** | R-03 — đo đúng giai đoạn |
| **Mất dữ liệu đáp án khi sập pod** | *(không có)* | **0** | R-07 — chỉ số quan trọng nhất, v2.1 thiếu |
| **End-to-end submit → ACK (p99)** | *(không có)* | **< 100 ms** | Chỉ số học sinh thực sự cảm nhận |
| **Handshake rate chịu được / GW pod** | *(không có)* | **đo ở H1** | R-10 — thời điểm khó nhất của hệ thống |
| **Chi phí / CCU / giờ** | *(không có)* | **theo dõi liên tục** | R-22 |

---

# 3. Những gì v2.1 làm đúng — giữ nguyên

Bản điều chỉnh này tập trung vào chỗ sai, nên cần nói rõ phần đúng để không bị sửa nhầm:

1. **1 Room = 1 Actor đơn luồng** (Mục 1.3.2) — lựa chọn cốt lõi, đúng, và đúng vì lý do đúng.
2. **Bỏ Redis Pub/Sub khỏi hot path** (Mục 2.1.3, cảnh báo IMPORTANT) — bớt một broker hop
   và xóa cả một lớp bug cross-room.
3. **Gateway không chứa business logic** (Mục 2.1.3) — quy tắc này phải giữ tuyệt đối.
4. **Native WebSocket + Protobuf thay Socket.io** (Mục 17) — đúng cho mobile.
5. **Idempotency hai tầng** (Mục 13.4) — ý hay nhất tài liệu; R-07 còn nâng nó lên thành
   nền tảng của toàn bộ cơ chế recovery.
6. **Runtime Guardrails cho Game Definition** (Mục 21) — giữ cả 4; chỉ sửa cách phát biểu
   watchdog (R-12).
7. **Phân loại Critical / Non-Critical** (Mục 13.3, 14.2) — giữ nguyên, và nó càng quan
   trọng hơn sau khi chuyển sang coalescing tick.
8. **Snapshot versioning + checksum** (Mục 14.3) — giữ; bổ sung epoch của R-06 vào header.
9. **Backpressure mailbox → gateway** (Mục 14.4) — giữ; nay chạy trên kênh nội bộ của ADR-1.
10. **Phần "CẮT BỎ" của MVP plan** (Mục 1.2) — phần chất lượng nhất trong cả hai tài liệu.

---

# 4. Thứ tự áp dụng đề xuất

| Đợt | Điều chỉnh | Vì sao trước |
|---|---|---|
| **1 — Chặn lỗi** | R-09, R-11, R-08 | Ba lỗi sẽ vào production nếu code theo v2.1 nguyên trạng |
| **2 — Nền tảng đúng đắn** | R-06, R-07, R-17 | Quyết định hình dạng recovery và persistence; sửa sau rất đắt |
| **3 — Mô hình tải** | R-01, R-02, R-04, R-05, R-10 | Quyết định protocol + capacity; R-02 ảnh hưởng cả client |
| **4 — Chốt mâu thuẫn** | R-14, R-15, R-16, R-18, R-19, R-13 | Dev cần một câu trả lời duy nhất trước khi bắt đầu |
| **5 — Bổ sung thiếu** | R-20, R-21, R-22, R-23, R-24, R-12 | Cần trước khi công bố phạm vi/timeline |
| **6 — Tài liệu** | R-25, R-03 | Trước khi bàn giao ra ngoài |

---

## Việc còn mở — cần người quyết định, không phải kỹ thuật

| # | Câu hỏi | Ai quyết |
|---|---|---|
| 1 | `missed_step_policy` cho late join (R-21) | Product |
| 2 | Ngân sách hạ tầng / chi phí trên mỗi CCU (R-22) | Business |
| 3 | Hệ thống chạy mấy giờ/ngày? (quyết định scale-to-zero — R-22) | Business |
| 4 | Danh mục game thật của phase 1 → quyết định `tick_mode` (R-02) và capacity | Product |
| 5 | Trường lớn nhất có bao nhiêu học sinh đồng thời sau một IP? (R-09 ngưỡng L1) | Business |

---

*Bản điều chỉnh v2.2 — áp dụng lên `EdTech_Game_Realtime_Architecture_v2.1.md`.*
