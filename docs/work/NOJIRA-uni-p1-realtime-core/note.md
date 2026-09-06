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

## Task 5 — Gateway: FrameChannelClient + RouteCache (lazy-learned routing)

- **Trạng thái:** done (2026-09-06)
- **Verification:** `mvn -pl :uni-gateway test -Dtest=RouteCacheTest` → 5/5 pass (plain JUnit,
  không Netty). `mvn -pl :uni-gateway test -Dtest=FrameChannelClientTest` → 3/3 pass (thêm
  ngoài yêu cầu plan.md — 2 "fake Engine pod" thật trên socket loopback, chứng minh
  round-robin → learn → gửi thẳng → evict-khi-đứt-kết-nối hoạt động đúng cùng nhau).
  Toàn module 15/15, toàn reactor `mvn test` xanh, không leak, không deprecation warning.
- **Phạm vi đã làm:**
  - `RouteCache`: `room_id → engine_pod_id` trong `ConcurrentHashMap` (truy cập từ nhiều Netty
    event-loop thread khác nhau — mỗi pod connection có thể chạy trên thread riêng trong group
    dùng chung). Không TTL: `learn()` ghi đè tự do, `evictPod()` xoá đúng và chỉ những entry
    trỏ tới pod đó (test xác nhận entry của pod khác không bị đụng).
  - `InternalFrameCodec`: bản sao nhỏ của `FrameCodec` (Task 4) riêng cho phía Gateway — cùng
    format wire (length-prefixed + protobuf) nhưng không share code Netty giữa 2 service, chỉ
    share `uni-protocol`.
  - `FrameChannelClient`: giữ 1 connection/pod (`connect(podId, host, port)`), `send()` tra
    `RouteCache` trước — biết thì gửi thẳng, không biết thì round-robin (`AtomicInteger` cursor,
    chỉ tăng khi thật sự round-robin, không tăng khi gửi thẳng theo cache — test
    `should_learnRouteFromResponse_and_sendDirectlyWithoutAdvancingRoundRobin` xác nhận đúng
    hành vi này). Nhận response → đọc `InternalHeader.owner_pod_id` → `routeCache.learn(...)`.
    `channelInactive` → gỡ pod khỏi danh sách connection + round-robin + `routeCache.evictPod(...)`.
  - Dùng chung `EventLoopGroup` truyền từ ngoài vào (không tự tạo) — giữ đúng bất biến GĐ1
    "EventLoop cố định = cores × 2" cho toàn bộ pod Gateway (Task 6), không tạo thêm thread pool
    riêng cho phần kết nối ra Engine.
- **Cố ý chưa làm (thuộc task khác):**
  - Backpressure một tầng xuyên suốt mailbox↔socket — Task 9.
  - RoomRegistry/fan-out nhiều client cùng phòng ở Gateway — Task 8.
  - Route cache chưa được nối vào `RoomRouteHandler` (Task 6) hay Gateway pipeline thật — việc
    "gắn dây" toàn bộ luồng end-to-end là Task 13 (walking skeleton).
- **File đụng tới:**
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/routing/RouteCache.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/routing/InternalFrameCodec.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/routing/FrameChannelClient.java` (mới)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/routing/RouteCacheTest.java` (mới)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/routing/FrameChannelClientTest.java` (mới)

## Task 10 — Engine: RoomOwnership + đóng dấu owner_pod_id

- **Trạng thái:** done (2026-09-06)
- **Verification:** `mvn -pl :uni-engine test -Dtest=RoomOwnershipTest` → 5/5 pass (plain
  JUnit — thuật toán thuần). `mvn -pl :uni-engine test -Dtest=RoomOwnershipHandlerTest` → 2/2
  pass (thêm ngoài yêu cầu, `EmbeddedChannel` — chứng minh handler thật forward đúng khi sở
  hữu và trả `NOT_OWNER` đúng khi không sở hữu, không chỉ đúng thuật toán độc lập).
  `grep -rn "% N\|modulo" modules/uni-engine/src/main --include=*.java` chỉ khớp
  `ModuloRoomOwnership.java` — đúng yêu cầu plan.md ("không class nào khác biết tới phép % N").
  Toàn module 21/21, toàn reactor `mvn test` xanh.
- **Phạm vi đã làm:**
  - `RoomOwnership` (interface) + `ModuloRoomOwnership`: danh sách pod được sort ổn định,
    `Math.floorMod(roomId.hashCode(), N)` (không dùng `%` trực tiếp — `hashCode()` có thể âm,
    `%` sẽ ra index âm và lỗi). `isOwner`/`ownerPodId` đồng nhất giữa các pod khác nhau cho
    cùng room_id (test xác nhận). Constructor fail-fast nếu `selfPodId` không nằm trong danh
    sách pod.
  - `RoomOwnershipHandler` (`engine.net`, mới): phòng thuộc pod này → forward qua callback;
    không thuộc → trả `GameMessage` với `InternalHeader{owner_pod_id, routing_status=NOT_OWNER}`
    đúng `room_id`/`student_id`/`sequence` gốc, không forward Engine-to-Engine (đúng cơ chế
    §4.5 đã tài liệu — Gateway tự sửa `RouteCache` từ response này, khớp với cách
    `FrameChannelClient.handleResponse` (Task 5) đã đọc `owner_pod_id` sẵn).
  - `FrameChannelServer` (sửa): constructor nhận thêm `RoomOwnership`, dùng
    `RoomOwnershipHandler` thay cho handler ẩn danh cũ. `FrameChannelServerTest` (Task 4) cập
    nhật theo, dùng `ModuloRoomOwnership` 1-pod (sở hữu mọi phòng) để giữ nguyên ý nghĩa test cũ.
- **Cố ý chưa làm (thuộc task khác):**
  - Kết nối `RoomOwnershipHandler` → mailbox `RoomActor` thật (spawn/lookup theo `room_id`) —
    chưa có registry actor nào trong `FrameChannelServer`; đây là phần "gắn dây" của Task 13.
  - Forward Engine-to-Engine khi không sở hữu — không cần vì §4.5 chỉ cần `NOT_OWNER` + Gateway
    tự định tuyến lại.
- **File đụng tới:**
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomOwnership.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/ModuloRoomOwnership.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/net/RoomOwnershipHandler.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/net/FrameChannelServer.java` (sửa)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/room/RoomOwnershipTest.java` (mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/net/RoomOwnershipHandlerTest.java` (mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/net/FrameChannelServerTest.java` (sửa)

## Task 7 — Gateway: Rate limiting theo student_id (một phần)

- **Trạng thái:** một phần xong (2026-09-06) — xem "Cố ý chưa làm" bên dưới.
- **Verification:** `mvn -pl :uni-gateway test -Dtest=RateLimitHandlerTest` → 5/5 pass
  (`EmbeddedChannel`, `Clock.fixed` — không sleep thời gian thật, không flaky).
  `mvn -pl :uni-gateway test -Dtest=TokenBucketTest` → 4/4 pass (logic thuần, `MutableClock`
  test double giống style dùng ở `RoomActorTest`/`FrameChannelClientTest`). Toàn module 24/24,
  toàn reactor xanh.
- **Phạm vi đã làm:**
  - `TokenBucket`: fixed-window counter (`capacity` permit, refill toàn bộ mỗi `refillPeriod`) —
    chọn window thay vì token trickle vì cách viết "3/refill 1s" của AC đọc tự nhiên như vậy và
    dễ test tất định hơn.
  - `RateLimitHandler`: thay hẳn placeholder của Task 6. 3 bucket riêng theo `type`:
    `SUBMIT_ANSWER` 3/1s, `UPDATE_DRAFT` 10/10s, `HEARTBEAT` 2/30s. Mỗi connection có đúng 1
    instance handler (do `GatewayPipeline` tạo mới mỗi channel) → bucket state per-instance
    tự nhiên chính là per-student_id, không cần `Map` chia sẻ giữa các connection.
  - `SUBMIT_ANSWER` vượt ngưỡng → trả `AnswerAck{accepted=false, reject_reason=RATE_LIMIT_EXCEEDED}`
    thật qua `ctx.writeAndFlush`, không forward tiếp, **không đóng channel** (test xác nhận
    `channel.isOpen()` vẫn true).
  - Case bắt buộc "500 client sau cùng 1 IP đều kết nối được": test dựng 500
    `RateLimitHandler` độc lập (mô phỏng 500 connection thật, đúng kiến trúc 1 instance/connection)
    và xác nhận từng client đều gửi được — đúng vì code **không hề đọc IP ở đâu cả**, không phải
    vì có logic đặc biệt "bỏ qua IP".
- **Cố ý CHƯA làm — không phải thiếu sót:**
  - **L1 IP-based admission control (300 handshake/phút)**: đây là control ở tầng
    handshake/ingress (giới hạn số kết nối MỚI/phút theo IP), khác hẳn cơ chế per-message theo
    `student_id` mà `RateLimitHandler` làm. Chưa có điểm gắn nào trong repo (không có admission
    control component). AC ghi "nếu bật" — nghĩa là optional, nhưng để trung thực, đánh dấu task
    này "một phần xong" thay vì "xong" vì mục AC đó chưa tick.
  - `UPDATE_DRAFT`/`HEARTBEAT` vượt ngưỡng: drop im lặng, không có phản hồi `RATE_LIMIT_EXCEEDED`
    thật trên dây vì schema không có payload ack cho 2 loại này (`UPDATE_DRAFT` thiếu payload
    trong oneof — tech-design.md §G3). Không tự thêm field/message mới vào `.proto` (phải đi PR
    riêng theo quy ước).
  - "Tổng 15/15s" trong AC gốc: không hiện thực thành bucket thứ 4 — 3 cửa sổ 1s/10s/30s không
    gộp thành 1 cửa sổ chung có nghĩa; đọc là ước lượng thô, không phải cơ chế cần code.
- **File đụng tới:**
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/TokenBucket.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/RateLimitHandler.java` (thay hẳn placeholder)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/net/TokenBucketTest.java` (mới)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/net/RateLimitHandlerTest.java` (mới)

## Task 8 — Gateway: RoomRegistry + Broadcaster (fan-out zero-copy)

- **Trạng thái:** done (2026-09-06)
- **Verification:** `mvn -pl :uni-gateway test -Dtest=FanoutTest` → 4/4 pass, 12 `EmbeddedChannel`
  đúng như plan.md bắt buộc. `RoomRegistryTest` → 5/5 pass. `GatewayPipelineTest` (thêm 2 case
  mới) → xác nhận add-on-join/remove-on-channelInactive qua pipeline thật, không chỉ qua
  `RoomRegistry` cô lập. Toàn module 35/35, toàn reactor xanh.
  **Prove-it trên chính task này** (không phải bug fix, nhưng cùng tinh thần): đổi tạm
  `Broadcaster` sang `.retain()`, chạy `FanoutTest` → fail đúng ngay ở client thứ 2 (mảng byte
  rỗng) với thông điệp lỗi khớp chính xác lý do đã viết trong assertion message; trả lại
  `.retainedDuplicate()` → xanh lại. Xác nhận test thật sự "sẽ đỏ nếu code sai", không phải
  test vô nghĩa pass sẵn.
- **Phạm vi đã làm:**
  - `RoomRegistry`: `ConcurrentHashMap<String, Set<Channel>>` (set con cũng concurrent) — an
    toàn khi `channelInactive` của channel A chạy trên event-loop thread của A cùng lúc một
    broadcast khác đang duyệt cùng room trên thread khác. `remove()` quét **mọi** room (không
    giả định 1 channel chỉ thuộc 1 room, đúng chữ "mọi set" của AC).
  - `Broadcaster`: `retainedDuplicate()` + `BinaryWebSocketFrame` mới cho mỗi client,
    `frame.release()` trong `finally` (chạy dù list rỗng hay write ném lỗi), bỏ qua channel đã
    `!isActive()` (phòng race giữa lúc channel chết và lúc `channelInactive` kịp chạy).
  - Nối dây thật: `TicketAuthHandler` gọi `roomRegistry.add(...)` ngay sau khi bind
    `ChannelAttributes` (chỗ duy nhất biết `room_id`) — nhưng handler này tự gỡ khỏi pipeline
    sau đó nên không thể lo phần gỡ đăng ký. `RoomRouteHandler` (sống suốt đời connection)
    override `channelInactive` để gọi `roomRegistry.remove(...)`. `GatewayPipeline`/
    `GatewayBootstrap` truyền `RoomRegistry` — **một instance dùng chung cho cả pod**, khác hẳn
    `TicketAuthHandler`/`RateLimitHandler` (mỗi channel một instance riêng).
- **Cố ý chưa làm (thuộc task khác):**
  - Nối `Broadcaster.broadcast(...)` với phản hồi thật từ Engine (`FrameChannelClient.onResponse`,
    Task 5) — dây nối end-to-end Engine→Gateway→client là Task 13.
  - `!isWritable()`/backpressure khi buffer đầy — Task 9.
- **File đụng tới:**
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/fanout/RoomRegistry.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/fanout/Broadcaster.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/auth/TicketAuthHandler.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/RoomRouteHandler.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayPipeline.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayBootstrap.java` (sửa)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/fanout/RoomRegistryTest.java` (mới)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/fanout/FanoutTest.java` (mới)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/net/GatewayPipelineTest.java` (sửa)

## Task 9 — Backpressure một tầng (một phần)

- **Trạng thái:** một phần xong (2026-09-06) — xem "Cố ý chưa làm" bên dưới.
- **Verification:** `mvn -pl :uni-gateway,:uni-engine test -Dtest=BackpressureTest` → gateway
  5/5 pass, engine 2/2 pass. Case bắt buộc "client chậm không kéo tụt client khác cùng phòng"
  pass. **Prove-it**: đảo ngược tạm điều kiện Critical/Best-effort trong `Broadcaster`, chạy lại
  → 3/5 test fail đúng như dự đoán (drop-thay-vì-close và ngược lại, cộng test "không ảnh hưởng
  client khác"), rồi trả lại đúng logic → xanh lại. Toàn `uni-gateway` 40/40, toàn `uni-engine`
  23/23, toàn reactor xanh, không leak, không deprecation warning.
- **Phạm vi đã làm:**
  - `BackpressureHandler` (gateway, mới): MỘT class dùng lại ở mọi hop — override
    `channelWritabilityChanged`, toggle `autoRead(writable)` của chính channel đó, tăng Counter
    `channel_not_writable_total` khi chuyển sang không writable. Áp dụng ở: WS client channel
    (`GatewayBootstrap` — thêm đầu tiên trong `GatewayPipeline`, trước cả `HttpServerCodec`, vì
    writability là chuyện tầng transport không liên quan trạng thái auth), GW→Engine
    (`FrameChannelClient.connect()`), và Engine's accepted channel (`FrameChannelServer` — bản
    nested `private static final class` riêng vì plan.md không liệt kê file mới bên engine,
    chỉ "sửa FrameChannelServer.java"; expose qua `static newBackpressureHandler(...)`
    package-private để test được qua `EmbeddedChannel`).
  - `WRITE_BUFFER_WATER_MARK(32KB, 64KB)` set qua `childOption`/`option` ở cả 3 bootstrap trên.
  - `Broadcaster` (Task 8) mở rộng: thêm tham số `DeliveryClass`. `!isWritable()` +
    `BEST_EFFORT` → bỏ qua channel đó (không queue lại, không retry); `!isWritable()` +
    `CRITICAL` → đóng channel đó. Cả hai đường đều KHÔNG ảnh hưởng các channel khác trong cùng
    vòng lặp fan-out — đây chính là cơ sở của case bắt buộc "client chậm không kéo tụt client
    khác cùng phòng".
  - Kỹ thuật test đáng chú ý: `EmbeddedChannel.writeAndFlush()` hoàn tất đồng bộ (không có
    socket thật để nghẽn) nên KHÔNG BAO GIỜ tự nhiên trip watermark — phải dùng `write()`
    **không flush** để giữ byte "pending" thật sự trong `ChannelOutboundBuffer`, mới ép được
    `isWritable()` về `false` đúng cách (đã tự viết chương trình nhỏ xác nhận hành vi này trước
    khi viết test, tránh đoán mò).
- **Cố ý CHƯA làm — giới hạn kiến trúc thật, không phải thiếu sót:**
  - **Chuỗi cụ thể "mailbox RoomActor đầy → Engine tự dừng đọc kết nối đó" chưa nối được.**
    Pekko typed không có API đọc độ sâu mailbox đồng bộ tiện dùng, và quan trọng hơn: một
    internal connection mang traffic của NHIỀU phòng multiplex chung (ADR-001) nên "mailbox 1
    phòng đầy" không map 1-1 vào "channel nào cần autoRead(false)". `BackpressureHandler` hiện
    tại phản ứng theo writability CỦA CHÍNH channel đó (Engine ghi phản hồi ra không kịp) — đây
    là backpressure Netty chuẩn và đúng ở TỪNG hop, nhưng không phải cùng một cơ chế
    "mailbox-depth-driven" y hệt như câu chữ AC mô tả. Bịa thêm hạ tầng đo mailbox depth ở đây
    sẽ là suy đoán ngoài phạm vi mọi task đã giao.
  - Việc nối toàn chuỗi thật (nếu cần đúng nghĩa đen của AC) là quyết định kiến trúc riêng hoặc
    thuộc Task 13.
- **File đụng tới:**
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/BackpressureHandler.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/fanout/Broadcaster.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayPipeline.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayBootstrap.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/routing/FrameChannelClient.java` (sửa)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/net/FrameChannelServer.java` (sửa)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/net/BackpressureTest.java` (mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/net/BackpressureTest.java` (mới)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/fanout/FanoutTest.java` (sửa — thêm `DeliveryClass`)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/net/GatewayPipelineTest.java` (sửa)
  - `modules/uni-gateway/src/test/java/com/uni/realtime/gateway/routing/FrameChannelClientTest.java` (sửa)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/net/FrameChannelServerTest.java` (sửa)

## Tóm tắt tiến độ

- **7/12 task done đầy đủ (T1, T2, T4, T5, T8, T10) + T6, T7, T9 một phần.**
- **Đang làm tiếp:** SPIKE Pekko timer (bắt buộc trước Task 3), Task 11 (Game Definition tối
  giản, phụ thuộc T2), hoặc quay lại chốt G1a/G1c/G2a/G2b để đóng hẳn T3/T6.
- **Block:**
  - Task 3 (tick coalescing) vẫn chờ G2a/G2b (tech-design.md §9.1) — N lần flush và cách mã hoá delta chưa chốt.
  - Task 6 **không đóng hẳn được** — chờ G1a/G1c (thuật toán ký + dung sai đồng hồ) từ đội dịch vụ nền tảng.
  - Task 7 thiếu L1 IP admission control (optional theo AC, nhưng chưa có điểm gắn trong repo).
  - Task 9 thiếu chuỗi mailbox-depth-driven cụ thể (giới hạn kiến trúc 1-connection-nhiều-phòng, không phải bug).
  - `ScoreCalculator` thật vẫn chờ Product (câu 1, §9.2 / system-architecture.md §7.5).
