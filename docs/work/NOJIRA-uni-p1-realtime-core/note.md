# Progress log — NOJIRA-uni-p1-realtime-core

<!-- templates/progress-log-template.md không tồn tại trong kit đã cài (2026-09-06) — file này
     tự tạo theo đúng cấu trúc mà skill progress-logging mô tả (task / trạng thái / timestamp /
     verification / file đụng tới), không có gì tự bịa thêm ngoài đó. -->

## Task 2 — RoomActor: FSM, server timestamp, dedupe, watchdog

- **Trạng thái:** done (2026-09-06)
- **Verification:** `mvn -pl :uni-engine test -Dtest=RoomActorTest` → 8/8 pass. `mvn -pl :uni-engine test` (toàn module, gồm `EngineApplicationTests`) → 9/9 pass, `-Dio.netty.leakDetection.level=paranoid` sạch.
- **Phạm vi đã làm:**
  - FSM `LOBBY → PLAYING → FINISHED`; `EndGame` kết thúc bằng `Behaviors.stopped()` (test: `should_stopActor_when_gameEndsAfterPlaying`).
  - `server_received_at` đóng dấu bằng `Clock` tiêm qua constructor, **trước** mọi validate/tra bảng trong `RoomState.submitAnswer`.
  - `response_time_ms = server_received_at − server_question_started_at`, test với `MutableClock` (test double) xác nhận đúng con số.
  - Từ chối `PAST_DEADLINE` khi `server_received_at > deadline + 500ms`, có test biên (đúng deadline+500 vẫn accept, +501 mới reject).
  - `LastSeenSequenceTable` trong `RoomState`: `sequence ≤ last_seen` → gửi lại đúng `GameMessage` ack cũ (object y hệt), không tính lại điểm.
  - `client_timestamp_ms` **không** xuất hiện ở đâu trên đường chấm điểm — cấu trúc `SubmitAnswer` command tách field này khỏi mọi thứ `RoomState.submitAnswer` nhận vào; grep xác nhận `.clientTimestampMs()` không được gọi ở bất kỳ đâu trong `uni-engine`. Test `should_ignoreClientTimestampMs_when_computingScoreAndResponseTime` xác nhận forge giá trị này không đổi điểm/response time.
  - Watchdog: đo `System.nanoTime()` quanh mỗi handler, ghi Micrometer `Timer` (`engine.room.actor.processing.time`), `log.warn` khi > 10ms, **không** cố ngắt actor (test dùng `ScoreCalculator` giả lập chậm 15ms).
- **Cố ý chưa làm (ngoài phạm vi Task 2, không phải thiếu sót):**
  - `ScoreCalculator` thật — dùng `PlaceholderScoreCalculator` (flat 100 điểm, đánh dấu rõ TEMPORARY) vì công thức điểm Product chưa chốt (tech-design.md §9.2 câu 1). Không được lặng lẽ trở thành default production.
  - Join room / `student_index` / broadcast / tick coalescing — thuộc Task 3, 8, 11, không phải Task 2.
- **File đụng tới:**
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomActor.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomState.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/scoring/ScoreCalculator.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/scoring/PlaceholderScoreCalculator.java` (mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/room/RoomActorTest.java` (mới)

## Task 4 — Internal Frame Channel: FrameCodec + FrameChannelServer (Engine)

- **Trạng thái:** done (2026-09-06)
- **Verification:** `mvn -pl :uni-engine test -Dtest=FrameCodecTest` → 4/4 pass (EmbeddedChannel,
  không mạng thật). `mvn -pl :uni-engine test -Dtest=FrameChannelServerTest` → 1/1 pass (thêm
  ngoài yêu cầu của plan.md — client Netty thật nối qua socket ephemeral để chứng minh
  `FrameChannelServer` bind/nhận gói được, không chỉ đúng ở mức codec). Toàn module: 14/14 pass,
  `-Dio.netty.leakDetection.level=paranoid` sạch, không còn warning deprecation.
- **Phạm vi đã làm:**
  - `FrameCodec.newHandlers()`: `LengthFieldBasedFrameDecoder(1MB, 0, 4, 0, 4)` +
    `LengthFieldPrepender(4)` (đúng tham số plan.md ghi), cộng `GameMessageDecoder`/`GameMessageEncoder`
    (parse/serialize trực tiếp bằng `GameMessage.parseFrom`/`toByteArray`, không dùng
    `netty-codec-protobuf` dù có sẵn transitively — đơn giản và type-safe hơn) và
    `ChannelFaultHandler` (đóng channel + `log.warn` khi decode lỗi, kể cả oversized frame).
  - Test xác nhận: round-trip qua `EmbeddedChannel`; ráp đúng khi gói bị chia **từng byte một**
    (`should_reassembleFrame_when_deliveredOneByteAtATime`); đóng channel không throw khi
    frame vượt `MAX_FRAME_LENGTH`; biên đúng-bằng-giới-hạn vẫn mở (phát hiện Netty tính
    "adjusted frame length" = giá trị khai báo + 4 byte header, không phải chỉ payload — chỉnh
    lại test cho khớp thực tế Netty 4.2, không phải bug ở code chính).
  - `FrameChannelServer`: bootstrap Netty server thật, nhận `GameMessage` đã decode và forward
    qua callback `Consumer<GameMessage>`. Dùng `MultiThreadIoEventLoopGroup` +
    `NioIoHandler.newFactory()` (API mới của Netty 4.2, thay `NioEventLoopGroup` đã deprecated).
- **Cố ý chưa làm (thuộc task khác):**
  - Định tuyến gói theo `room_id` tới đúng `RoomActor` — Task 10 (`RoomOwnership`).
  - Phía Gateway (client kết nối tới Engine, route cache) — Task 5.
  - Test tích hợp nhiều phòng / nhiều pod thật — Task 13 (walking skeleton).
- **File đụng tới:**
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/net/FrameCodec.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/net/FrameChannelServer.java` (mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/net/FrameCodecTest.java` (mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/net/FrameChannelServerTest.java` (mới)

## Tóm tắt tiến độ

- **1/12 task done trước đó (Task 1 — protocol), nay 3/12 (+ Task 2 — RoomActor, + Task 4 — Frame Channel).**
- **Đang làm tiếp:** SPIKE Pekko timer (bắt buộc trước Task 3), hoặc Task 6 (Gateway pipeline —
  phần không đụng ký ticket), hoặc Task 5 (frame channel phía Gateway, phụ thuộc Task 4 vừa xong).
- **Block:**
  - Task 3 (tick coalescing) vẫn chờ G2a/G2b (tech-design.md §9.1) — N lần flush và cách mã hoá delta chưa chốt.
  - Task 6 phần ký ticket vẫn chờ G1a/G1c (thuật toán ký + dung sai đồng hồ) — phần pipeline còn lại (không đụng `TicketAuthHandler` xác thực chữ ký) làm được.
  - `ScoreCalculator` thật vẫn chờ Product (câu 1, §9.2 / system-architecture.md §7.5).
