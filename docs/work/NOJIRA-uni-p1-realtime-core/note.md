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

## Tóm tắt tiến độ

- **1/12 task done trước đó (Task 1 — protocol), nay 2/12 (+ Task 2 — RoomActor).**
- **Đang làm tiếp:** SPIKE Pekko timer (bắt buộc trước Task 3) hoặc chạy song song Task 4 (frame codec) / Task 6 (Gateway pipeline) — cả hai độc lập với Task 2 theo dependency graph của `plan.md`.
- **Block:**
  - Task 3 (tick coalescing) vẫn chờ G2a/G2b (tech-design.md §9.1) — N lần flush và cách mã hoá delta chưa chốt.
  - Task 6 phần ký ticket vẫn chờ G1a/G1c (thuật toán ký + dung sai đồng hồ) — phần pipeline còn lại (không đụng `TicketAuthHandler` xác thực chữ ký) làm được.
  - `ScoreCalculator` thật vẫn chờ Product (câu 1, §9.2 / system-architecture.md §7.5).
