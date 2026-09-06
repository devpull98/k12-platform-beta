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
  - ~~`ScoreCalculator` thật — dùng `PlaceholderScoreCalculator` (flat 100 điểm, đánh dấu rõ TEMPORARY) vì công thức điểm Product chưa chốt (tech-design.md §9.2 câu 1). Không được lặng lẽ trở thành default production.~~
    **Đã đóng 2026-09-06** — xem mục "ScoreCalculator thật" bên dưới.
  - Join room / `student_index` / broadcast / tick coalescing — thuộc Task 3, 8, 11, không phải Task 2.
- **File đụng tới:**
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomActor.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomState.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/scoring/ScoreCalculator.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/scoring/PlaceholderScoreCalculator.java` (mới, **đã xoá 2026-09-06** — xem bên dưới)
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

## Task 11 — Engine: GameDefinition + DefinitionLoader

- **Trạng thái:** done (2026-09-06)
- **Verification:** `mvn -pl :uni-engine test -Dtest=DefinitionLoaderTest` → 8/8 pass.
  `ScoringFormulaTest` → 6/6 pass (thêm ngoài yêu cầu). **Prove-it**: tạm comment-out lời gọi
  `rejectCycles(...)` trong `DefinitionLoader.load()`, chạy lại → đúng 2/8 test (2 case chu
  trình) fail, không hơn không kém, rồi bật lại → xanh. Toàn module 37/37, toàn reactor xanh.
- **Phạm vi đã làm:**
  - `GameDefinition` (record): `steps`, `startStepId`, `tickMode`, `scoringFormula`,
    `missedStepPolicy` (mặc định dùng `ZERO` theo đúng câu chữ AC — dù bản thân "ZERO có phải
    default đúng về nghiệp vụ" vẫn là câu hỏi Product mở ở system-architecture.md §7.5), `maxTransitions`.
  - `Step` (record): `id`, `durationMs`, `nextStepIds` — chính `nextStepIds` là thứ biến danh
    sách step thành một ĐỒ THỊ (không chỉ chuỗi tuyến tính), cần thiết để "Validate DAG" có ý
    nghĩa thật.
  - `ScoringFormula`: cây biểu thức `sealed interface` đóng — 7 record
    (`Constant`/`IsCorrect`/`ResponseTimeMs`/`Add`/`Subtract`/`Multiply`/`Divide`/`Min`/`Max`),
    `evaluate(isCorrect, responseTimeMs)` đệ quy. Không `eval`, không reflection — an toàn bằng
    cấu trúc (compile-time closed set), không phải danh sách đen runtime.
  - `DefinitionLoader.load(...)`: từ chối (a) steps rỗng, (b) `tickMode == FIXED`, (c)
    `maxTransitions <= 0`, (d) `startStepId` không tồn tại, (e) `nextStepIds` trỏ tới step
    không tồn tại (dangling reference), (f) chu trình trong step graph (DFS 3 màu
    trắng/xám/đen chuẩn, phát hiện back-edge tới node đang nằm trên đường đi hiện tại).
- **Cố ý chưa làm (thuộc phạm vi khác):**
  - `MAX_TRANSITIONS` mới là giá trị cấu hình được validate — chưa có nơi nào THỰC THI đếm
    transition thật lúc chạy (chưa có consumer nào cho `GameDefinition`, kể cả `RoomActor`).
  - Công thức điểm quiz THẬT vẫn chờ Product quyết (tech-design.md §9.2 câu 1).
  - Không có tầng deserialize JSON/YAML cho definition "upload bởi người vận hành" — chưa có
    quyết định định dạng dây, tự bịa sẽ là phát minh hạ tầng ngoài phạm vi.
- **File đụng tới:**
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/definition/TickMode.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/definition/MissedStepPolicy.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/definition/Step.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/definition/ScoringFormula.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/definition/GameDefinition.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/definition/DefinitionRejectedException.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/definition/DefinitionLoader.java` (mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/definition/DefinitionLoaderTest.java` (mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/definition/ScoringFormulaTest.java` (mới)

## SPIKE — Pekko scheduler capacity (1.000 timer đồng thời)

- **Trạng thái:** ĐẠT (2026-09-06). Báo cáo đầy đủ: `spike-pekko-timer.md`.
- **Cách đo:** harness thật (không mock) — 1.000 actor Pekko rỗng, mỗi actor tự hẹn lại đúng 1
  single-shot timer/chu kỳ 200ms (đúng cơ chế ADR-4, không dùng `startTimerAtFixedRate` vì
  periodic timer sẽ tự che độ trôi cần đo). Ép `-XX:ActiveProcessorCount=2` để khớp đúng ngưỡng
  "CPU < 30% ở 2 vCPU" của plan.md (máy dev có nhiều lõi hơn môi trường mục tiêu).
- **Kết quả (2 lần chạy độc lập, mỗi lần 20 giây, ~91.000 mẫu):**
  - p99 độ lệch: 36–37ms (ngưỡng đạt: < 50ms)
  - CPU đỉnh: 5–6% (ngưỡng đạt: < 30%)
  - Nhất quán giữa 2 lần chạy — không phải kết quả may rủi.
- **Quyết định: GO.** Giữ nguyên thiết kế single-shot timer mỗi actor (ADR-4) cho Task 3, không
  cần đổi sang "flush wheel" gom lô ở tầng pod.
- **Giới hạn đã ghi rõ trong báo cáo:** đo trên actor rỗng (chưa cạnh tranh CPU với business
  logic thật), đo trên máy dev mô phỏng 2 vCPU (không phải cgroup quota thật của K8s) — cả hai
  vẫn thuộc PH-1 (load test harness thật) để xác nhận lại ở quy mô/môi trường gần production hơn.
- **File đụng tới:**
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/spike/SchedulerCapacitySpike.java` (mới —
    có `main()`, tên không khớp pattern Surefire nên không chạy trong `mvn test` thường; xác
    nhận 37/37 test `uni-engine` không đổi thời gian chạy sau khi thêm file này)
  - `docs/work/NOJIRA-uni-p1-realtime-core/spike-pekko-timer.md` (mới)

## Task 12 — Nền quan sát: EngineMetrics + GatewayMetrics

- **Trạng thái:** done (2026-09-06) — verify **bằng app chạy thật**, không chỉ unit test.
- **Verification:** `mvn -pl :uni-gateway spring-boot:run` + `curl localhost:8080/actuator/prometheus`
  → thấy `channel_not_writable_total`, `fanout_latency_seconds{...}`, `handshake_rate_total`.
  `mvn -pl :uni-engine spring-boot:run` + `curl localhost:8090/actuator/prometheus` → thấy
  `actor_mailbox_depth`, `actor_processing_latency_seconds{...}`, `channel_not_writable_total`.
  Cả hai process đã tắt sạch sau khi xác nhận (kiểm tra lại bằng curl health → connection
  refused). `EngineMetricsTest` 5/5, `GatewayMetricsTest` 4/4. Toàn `uni-gateway` 46/46, toàn
  `uni-engine` 42/42, toàn reactor xanh.
- **Hai lỗ hổng phát hiện giữa chừng (chỉ lộ ra khi chạy app thật, không unit test nào bắt được):**
  1. **Đăng ký metric kiểu lazy (di sản Task 9)**: `BackpressureHandler` cũ tự gọi
     `MeterRegistry.register()` mỗi lần constructor chạy — tức mỗi khi có connection mới. Pod
     mới khởi động, chưa có connection nào → metric không tồn tại trong scrape → vi phạm đúng
     lời hứa "không chờ tới cuối mới gắn" của chính Task 12. Sửa: `BackpressureHandler` (cả 2
     phía) giờ nhận `GatewayMetrics`/`EngineMetrics` đã đăng ký sẵn, không tự đăng ký nữa.
  2. **`GatewayMetrics`/`EngineMetrics` chưa hề là Spring bean** — dù class đúng, không có gì
     trong `GatewayApplication`/`EngineApplication` từng khởi tạo chúng, nên một app chạy thật
     sẽ KHÔNG hiện các metric này dù toàn bộ unit test xanh. Đây chính xác là lý do phải chạy
     `spring-boot:run` + `curl` thật để verify — nếu chỉ tin `mvn test` thì lỗ hổng này không
     bao giờ lộ ra. Sửa: thêm `MetricsConfiguration` (`@Configuration`/`@Bean`) ở cả hai module.
- **Phạm vi đã làm:**
  - `EngineMetrics`/`GatewayMetrics`: đăng ký TẤT CẢ metric ngay trong constructor (eager),
    pod-wide (không gắn tag `room_id`/per-connection) — vì tag động sẽ khiến metric không thể
    tồn tại trước khi đối tượng đầu tiên (phòng/connection) xuất hiện, phá vỡ đúng yêu cầu
    "hiện diện từ lúc khởi động".
  - `RoomActor` (Task 2) đổi từ tự tạo Timer riêng (`engine.room.actor.processing.time`,
    tag theo room_id) sang dùng `EngineMetrics.processingLatencyTimer()` — đúng tên
    `actor_processing_latency` theo AC, pod-wide.
  - `Broadcaster` (Task 8/9) đo `fanout_latency` quanh toàn bộ vòng lặp fan-out.
  - `TicketAuthHandler`: sinh `trace_id` (`UUID.randomUUID()`) lúc handshake thành công, lưu
    vào `ChannelAttributes.TRACE_ID` (key mới), gọi `GatewayMetrics.recordHandshake()`.
  - `RoomRouteHandler`: luôn đóng dấu `InternalHeader.trace_id` từ `ChannelAttributes` vào
    MỌI message trước khi forward (gộp luôn phần sửa `room_id` cũ vào cùng 1 `toBuilder()`).
- **Cố ý chưa làm (thuộc phạm vi khác):**
  - `EngineMetrics.recordMessageEnqueued/Dequeued` (nuôi `actor_mailbox_depth`) có unit test
    nhưng **không gọi ở đâu trong code chính** — chưa có nơi nào gửi message vào `RoomActor` từ
    bên ngoài (Task 13). Không wire nửa vời để tránh gauge chạy âm khi `RoomActorTest` tự gửi
    message trong test của chính nó.
  - `trace_id` mới tới biên Gateway (đóng dấu vào `InternalHeader` trước khi forward) — chưa
    có gì gọi `FrameChannelClient.send(...)` với message đã chuẩn bị sẵn đó (Task 13), nên
    "truyền xuống actor" theo đúng nghĩa đen của AC chưa hoàn thành, chỉ mới nửa đường (đã ghi
    rõ, không tự nhận full).
- **File đụng tới:**
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/metrics/EngineMetrics.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/metrics/MetricsConfiguration.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/metrics/GatewayMetrics.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/metrics/MetricsConfiguration.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomActor.java` (sửa)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/net/FrameChannelServer.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/BackpressureHandler.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayPipeline.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayBootstrap.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/ChannelAttributes.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/RoomRouteHandler.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/auth/TicketAuthHandler.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/routing/FrameChannelClient.java` (sửa)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/fanout/Broadcaster.java` (sửa)
  - Cùng các file test tương ứng (thêm mới `EngineMetricsTest`/`GatewayMetricsTest`, sửa
    `RoomActorTest`/`FrameChannelServerTest`/`BackpressureTest` (2 module)/`GatewayPipelineTest`/
    `FanoutTest`/`FrameChannelClientTest` theo chữ ký constructor mới).

## ScoreCalculator thật — công thức điểm Quiz Phase 1 (bổ sung Task 2, 2026-09-06)

- **Trạng thái:** done (2026-09-06)
- **Bối cảnh:** Product chốt công thức điểm Quiz 2026-09-06 (system-architecture.md §2.5): trắc
  nghiệm 1-trong-4 đáp án, nhị phân đúng/sai — đúng = 100 điểm, sai = 0, không bonus theo tốc độ.
  Đóng nốt "Ghi chú còn treo" của Task 2 (`PlaceholderScoreCalculator`).
- **Verification:** `mvn -pl :uni-engine test` (toàn module, target xoá sạch trước khi chạy) →
  46/46 pass, không leak, không deprecation warning. `mvn test` toàn reactor từ root → BUILD
  SUCCESS (uni-protocol/uni-observability/uni-gateway/uni-engine đều xanh). Grep bắt buộc:
  `grep -rn "client_timestamp_ms\|clientTimestampMs" modules/uni-engine/src/main --include=*.java`
  → chỉ khớp doc-comment + field truyền qua (không đụng đường chấm điểm);
  `grep -rn "\.retain()" modules/uni-gateway/src/main --include=*.java` → rỗng.
- **Phạm vi đã làm:**
  - `ScoreCalculator.award(...)` mở rộng nhận thêm `List<String> correctAnswerIds` (chữ ký cũ chỉ
    có `answerIds` + `responseTimeMs`, không đủ để biết đúng/sai).
  - Xoá `PlaceholderScoreCalculator` — không còn lý do tồn tại khi công thức thật đã có.
  - Viết `FormulaScoreCalculator` (bọc `ScoringFormula` của Task 11) thay vì một class chấm điểm
    đứng riêng — quyết định thiết kế: ban đầu viết thử `BinaryChoiceScoreCalculator` độc lập rồi
    **tự nhận ra và xoá**, vì nó nhân đôi đúng cơ chế `ScoringFormula` (cây biểu thức đóng) đã
    xây ở Task 11 và mâu thuẫn với chính câu đã ghi trong system-architecture.md §2.5 ("biểu diễn
    đúng bằng tập toán tử giới hạn ở guardrail — không cần mở rộng"). `FormulaScoreCalculator`
    tính đúng/sai bằng so khớp tập hợp chính xác (`Set.copyOf` — không phải superset/subset),
    rồi giao `isCorrect`/`responseTimeMs` cho `ScoringFormula.evaluate(...)`.
    `FormulaScoreCalculator.binaryChoice()` = `Multiply(IsCorrect(), Constant(100))`, đúng công
    thức Phase 1.
  - Nối `correctAnswerIds` qua `RoomState.startQuestion(...)` (3 tham số, thêm field
    `currentCorrectAnswerIds`) và `RoomActor.StartQuestion` (3 field).
  - `RoomActorTest` cập nhật dùng `FormulaScoreCalculator.binaryChoice()` thay placeholder, thêm
    test `should_award0_when_answerDoesNotMatchTheCorrectChoice`.
  - Test mới `FormulaScoreCalculatorTest` (3 case): đúng/sai theo exact-match độc lập với công
    thức; công thức khác (Constant 50) chứng minh tính tổng quát, không hardcode 100/0 trong
    Java; `binaryChoice()` khớp đúng quyết định Phase 1 (100/0, không bonus tốc độ).
  - Cập nhật kèm `GatewayPipeline.MAX_HTTP_AGGREGATED_CONTENT_BYTES`: 8KB → 50KB, khớp quyết định
    "trần chung mọi gói WS" cùng ngày 2026-09-06 (system-architecture.md §1.1/§7.5) — sửa cùng
    lúc để tránh code lệch tài liệu, dù đây không phải một phần trực tiếp của công thức điểm.
- **Cố ý chưa làm:** không có gì mới ngoài phạm vi Task 2/11 đã ghi trước đó (join room,
  broadcast, tick coalescing vẫn thuộc Task 3/8).
- **File đụng tới:**
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/scoring/ScoreCalculator.java` (sửa)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/scoring/PlaceholderScoreCalculator.java` (xoá)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/scoring/FormulaScoreCalculator.java` (mới)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomState.java` (sửa)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomActor.java` (sửa)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/room/RoomActorTest.java` (sửa)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/scoring/FormulaScoreCalculatorTest.java` (mới)
  - `modules/uni-gateway/src/main/java/com/uni/realtime/gateway/net/GatewayPipeline.java` (sửa)

## Task 3 — Tick coalescing trong RoomActor (ADR-4)

- **Trạng thái:** done (2026-09-07)
- **Chốt trước khi code (tech-design.md §9.1, chặn T3):**
  - **G2b** (mã hoá delta): chọn D1–D4 — delta ở mức người chơi (`RoomStateSnapshot.full=false`,
    `players` chỉ chứa học sinh đổi từ lần flush trước, mỗi `PlayerState` là bản đầy đủ, vắng mặt
    = không đổi, định danh bằng `student_index`). Không đụng `.proto`.
  - **G2a** (chu kỳ full snapshot): `N = 10` lần flush thì gửi 1 full snapshot thay vì delta —
    lưới an toàn khi một delta best-effort bị drop dưới backpressure (§5.4), vì PH-3 (client
    resync thật) chưa tồn tại.
  - Cả hai là quyết định kỹ thuật "quyết được trong đội" theo đúng phân loại của tech-design.md
    §9.1 (khác G1a — phải hỏi đội dịch vụ nền tảng), không phải câu hỏi Product/Business.
- **Verification:** `mvn -pl :uni-engine test -Dtest=TickCoalescingTest` → 5/5 pass. Toàn module
  51/51, toàn reactor `mvn clean install` xanh, không leak (`-Dio.netty.leakDetection.level=paranoid`
  bật sẵn qua Surefire toàn cục).
- **Phạm vi đã làm:**
  - **Roster chưa từng tồn tại trước Task 3** (Task 2 note đã ghi rõ: join/student_index/broadcast
    thuộc Task 3). Thêm `RoomActor.JoinRoom(studentId, displayName, replyTo)` và
    `RoomState.players: Map<String, PlayerRecord>` (index gán 1 lần lúc join, không đổi lại kể cả
    rejoin — D4). `score` KHÔNG lưu trùng trong `PlayerRecord`: `buildPlayerState()` đọc thẳng từ
    `totalScoreByStudent` đã có sẵn từ Task 2, tránh hai nguồn sự thật.
  - `RoomState.isDirty()` / `flush()`: đúng khuôn mẫu pseudocode ADR-4 ở tech-design v3.0 §6.2
    (`dirty`, `flushScheduled`, `lastFlushAt`, `MIN_INTERVAL_MS=200`) — `dirty`/`flushScheduled`/
    `lastFlushAt` là state của `RoomActor` (cần `TimerScheduler` + `Clock`, không Pekko-free được);
    `isDirty()`/`flush()`/đếm N lần flush là state của `RoomState` (thuần dữ liệu, không cần actor).
  - `RoomActor.create(...)` thêm `TickMode` + `ActorRef<GameMessage> broadcastTarget`. Sai
    `TickMode` (khác `COALESCE`) → `IllegalArgumentException` ngay lúc gọi `create()`, không chờ
    tới lúc actor chạy — `DefinitionLoader` (Task 11) đã chặn `FIXED` lúc nạp definition, đây là
    lớp fail-fast thứ hai phòng ai đó gọi thẳng bỏ qua loader.
  - `onSubmitAnswer`/`onJoinRoom`/`onStartQuestion` đều gọi `scheduleFlushIfDirty()` sau khi đổi
    state — bất kỳ thay đổi roster nào (điểm, answered_current, join mới, reset answered_current
    khi câu hỏi mới bắt đầu) đều đi qua đúng MỘT con đường coalescing, không có đường tắt nào khác.
  - Dùng `ActorTestKit` + `ManualTime` thật cho `TickCoalescingTest` (không phải `BehaviorTestKit`
    như `RoomActorTest`) vì cơ chế cần kiểm là timer thật sự chạy — `BehaviorTestKit` không chạy
    timer. `Clock` test double và `ManualTime` là hai đồng hồ độc lập phải tiến cùng nhau thủ công
    trong test (`advanceTime()`) — ở production cả hai đều là đồng hồ thật nên tự động khớp.
  - **Prove-it**: tạm đổi `buildDeltaSnapshot()` sang duyệt toàn bộ `players.keySet()` thay vì chỉ
    `dirtyStudentIds` — xác nhận đúng 1/5 test Red (`should_includeOnlyChangedPlayers...`), các
    test khác vẫn Green (đúng — chúng không kiểm nội dung delta), rồi trả lại code đúng để Green
    lại toàn bộ.
- **Cố ý chưa làm (thuộc Task 13, không phải thiếu sót Task 3):**
  - `broadcastTarget` là một `ActorRef<GameMessage>` đơn — đúng khuôn `replyTo` đã dùng cho
    `SubmitAnswer`/`JoinRoom`, giữ RoomActor không biết gì về transport. Nối nó với
    `FrameChannelServer`/kênh nội bộ thật (và multi-pod fan-out theo quyết định B1) là Task 13.
  - `QUESTION_STARTED`, `GAME_OVER`, `TEACHER_COMMAND`, `CONNECTION_DEGRADED`, `StudentJoined`
    **chưa được RoomActor phát ra ở đâu cả** — không riêng Task 3, chưa task nào implement việc
    phát các message này. AC "bypass hoàn toàn coalescing" đúng cấu trúc vì chúng không đụng
    đường flush, nhưng bản thân việc phát chúng ra ngoài vẫn là việc chưa làm.
  - Không có luồng "rời phòng" (`channelInactive` → `connected=false`) — Gateway (Task 6/8) chưa
    có cách báo điều này cho Engine. `PlayerRecord.connected` tồn tại trong schema/roster nhưng
    chỉ được set `true` (lúc join), chưa bao giờ bị set `false`.
- **File đụng tới:**
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomState.java` (sửa — roster, dirty, flush, snapshot builders)
  - `modules/uni-engine/src/main/java/com/uni/realtime/engine/room/RoomActor.java` (sửa — `JoinRoom`, `Flush` timer, `TickMode`, `broadcastTarget`)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/room/RoomActorTest.java` (sửa — constructor mới)
  - `modules/uni-engine/src/test/java/com/uni/realtime/engine/room/TickCoalescingTest.java` (mới)
  - `docs/specs/tech-design/NOJIRA-uni-p1-tech-design.md` (sửa — G2a/G2b đánh dấu đã chốt)

## Tóm tắt tiến độ

- **10/12 task done đầy đủ (T1, T2, T3, T4, T5, T8, T10, T11, T12) + T6, T7, T9 một phần. SPIKE đạt.**
- **Đang làm tiếp:** Với T3 xong, task "sạch" duy nhất còn lại không bị chặn bởi câu hỏi kỹ
  thuật/Product treo là Task 13 (walking skeleton) — nhưng Task 13 tự nó phụ thuộc Sync checkpoint
  (chờ T7/T9 đóng hẳn — T3/T11 đã xong). Lựa chọn thực tế: quay lại chốt G1a/G1c (Task 6) hoặc
  L1 IP admission control (Task 7) để mở khoá thêm, hoặc dừng ở đây chờ quyết định bên ngoài.
- **Block:**
  - Task 6 **không đóng hẳn được** — chờ G1a/G1c (thuật toán ký + dung sai đồng hồ) từ đội dịch vụ nền tảng.
  - Task 7 thiếu L1 IP admission control (optional theo AC, nhưng chưa có điểm gắn trong repo).
  - Task 9 thiếu chuỗi mailbox-depth-driven cụ thể (giới hạn kiến trúc 1-connection-nhiều-phòng, không phải bug).
  - ~~`ScoreCalculator` thật và công thức điểm trong `GameDefinition` đều chờ chung 1 quyết định Product (§9.2 câu 1).~~
    **Đã đóng 2026-09-06** — xem mục "ScoreCalculator thật" ở trên. Không còn block nào cho Task 2.
