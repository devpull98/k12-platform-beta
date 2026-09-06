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

## Task 6 — Gateway: Netty pipeline + WS handshake + ticket auth (một phần)

- **Trạng thái:** một phần xong (2026-09-06) — xem "Cố ý chưa làm" bên dưới, đây không phải task đóng hoàn toàn.
- **Verification:** `mvn -pl :uni-gateway test -Dtest=GatewayPipelineTest` → 6/6 pass
  (`EmbeddedChannel`, không socket thật — HTTP/WS handshake machinery của Netty không cần
  test lại, chỉ test các handler ứng dụng sau khi handshake xong). Toàn module: 7/7 pass,
  không leak, không deprecation warning.
- **Phạm vi đã làm:**
  - `GatewayPipeline.addTo(...)`: thứ tự cố định đúng như AC —
    `HttpServerCodec → HttpObjectAggregator(8KB) → WebSocketServerProtocolHandler →
    TicketAuthHandler → RateLimitHandler → GameMessageDecoder → RoomRouteHandler`. Không có
    `SslHandler` (test xác nhận `pipeline.get(SslHandler.class)` null).
  - `TicketAuthHandler`: đọc frame WS đầu tiên, bắt buộc phải là `JOIN_ROOM` (sai loại → đóng
    channel), gọi `TicketVerifier.verify(ticket)`, ticket bị từ chối → đóng channel + log; ticket
    hợp lệ → bind `ChannelAttributes{student_id, room_id, session_id, roles}` rồi **tự gỡ khỏi
    pipeline** và forward message JOIN_ROOM đã decode sẵn (không parse lại 2 lần).
  - `RoomRouteHandler`: `room_id` trong payload rỗng → chấp nhận và ghi đè bằng giá trị đã bind;
    khác giá trị đã bind (không rỗng) → đóng channel + `log.warn` (test bắt buộc theo plan.md
    đã pass: `should_closeChannel_when_payloadRoomIdDisagreesWithBoundRoomId`).
  - `RateLimitHandler`: placeholder pass-through, đúng vị trí trong pipeline — logic thật là Task 7.
  - `GatewayBootstrap`: bootstrap Netty thật, `MultiThreadIoEventLoopGroup` cỡ `cores * 2` (một
    group duy nhất, đúng AC "Netty EventLoop cố định = cores × 2"), dùng `NioIoHandler` (API
    mới của Netty 4.2, không dùng `NioEventLoopGroup` đã deprecated).
- **Cố ý CHƯA làm — không phải thiếu sót:**
  - **Thuật toán ký ticket thật (G1a) và dung sai lệch đồng hồ (G1c) — KHÔNG hiện thực.**
    `TicketVerifier` chỉ là interface (`modules/uni-gateway/.../auth/TicketVerifier.java`),
    **không có implementation nào trong main code**. Test dùng lambda fake verifier. Đây là
    ranh giới cố ý — tech-design.md nói rõ "không tự thiết kế, đi hỏi đội dịch vụ nền tảng".
    Bất kỳ ai định "tạm" viết một verifier giả trong main code để demo/staging đều đang vi phạm
    ranh giới này.
  - Ràng buộc ingress (passthrough WS upgrade, idle-timeout > 30s) chưa ghi vào
    `docker-compose.dev.yml` vì file đó chưa tồn tại — thuộc Task 13.
  - Rate limiting thật (Task 7), fan-out/RoomRegistry (Task 8), route cache tới Engine (Task 5),
    backpressure (Task 9) — đều chưa đụng tới, đúng ranh giới Task 6.
- **File đụng tới:**
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/auth/TicketVerifier.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/auth/TicketClaims.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/auth/TicketRejectedException.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/auth/TicketAuthHandler.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/ChannelAttributes.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GameMessageDecoder.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/RateLimitHandler.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/RoomRouteHandler.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayPipeline.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayBootstrap.java` (mới)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/net/GatewayPipelineTest.java` (mới)

## Tóm tắt tiến độ

- **1/12 task done trước đó (Task 1 — protocol), nay 3/12 done đầy đủ (+ Task 2, + Task 4)
  và Task 6 một phần** (pipeline + trust boundary xong; ký ticket thật + ingress manifest còn treo).
- **Đang làm tiếp:** SPIKE Pekko timer (bắt buộc trước Task 3), hoặc Task 5 (frame channel phía
  Gateway, phụ thuộc Task 4 đã xong), hoặc Task 7 (rate limit thật, phụ thuộc Task 6 vừa xong).
- **Block:**
  - Task 3 (tick coalescing) vẫn chờ G2a/G2b (tech-design.md §9.1) — N lần flush và cách mã hoá delta chưa chốt.
  - Task 6 **không đóng hẳn được** — chờ G1a/G1c (thuật toán ký + dung sai đồng hồ) từ đội dịch vụ nền tảng.
  - `ScoreCalculator` thật vẫn chờ Product (câu 1, §9.2 / system-architecture.md §7.5).
