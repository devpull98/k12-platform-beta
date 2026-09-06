# Spike: Pekko hashed-wheel scheduler chịu được bao nhiêu timer đồng thời?

<!--
Time-box:  4 giờ (hard stop)
Output:    benchmark | recommendation
Owner:     Backend / Engine
-->

## Problem Statement
ADR-4 (tick coalescing) khiến **mỗi phòng dirty tự hẹn một single-shot timer** để bù đúng phần
thời gian còn lại trong cửa sổ 200ms. Pekko dùng hashed-wheel scheduler (`tick-duration` mặc
định 10ms). Câu hỏi cụ thể: **~1.000 `startSingleTimer` đồng thời/pod ở độ phân giải 200ms có
giữ được độ chính xác và không ăn hết CPU không?** Nếu không, Task 3 phải đổi sang một "flush
wheel" gom lô ở tầng pod thay vì timer mỗi actor.

## Success Criteria
- [x] Câu hỏi được trả lời đủ để ra quyết định
- [x] Rủi ro kỹ thuật chính được xác định (độ lệch timer, CPU)
- [x] Recommendation được viết thành văn bản

## Approach
1. Viết harness thật (không mock): 1.000 actor Pekko typed rỗng
   (`modules/uni-engine/src/test/java/.../spike/SchedulerCapacitySpike.java`), mỗi actor tự hẹn
   **một single-shot timer** đúng cơ chế ADR-4 (không dùng `startTimerAtFixedRate`, vì nhịp cố
   định sẽ tự che giấu độ trôi thời gian mà spike cần đo).
2. Nhịp khởi động jitter ngẫu nhiên trong `[0, 200ms)` để mô phỏng các phòng trở "dirty" không
   đồng loạt; sau lần bắn đầu, mỗi actor tự hẹn lại đúng 200ms cho chu kỳ kế tiếp.
3. Đo độ lệch (`actual_delay_ms − requested_delay_ms`) qua `System.nanoTime()` ở từng lần bắn,
   gom vào hàng đợi lock-free, tính p50/p90/p99 sau khi chạy.
4. Đo CPU tiến trình bằng `com.sun.management.OperatingSystemMXBean.getProcessCpuLoad()`, lấy
   mẫu mỗi 500ms trong suốt 20 giây chạy.
5. Ép JVM chỉ thấy **2 vCPU** (`-XX:ActiveProcessorCount=2`) để khớp đúng ngưỡng đánh giá của
   plan.md ("CPU < 30% ở 2 vCPU") — máy chạy spike có nhiều lõi hơn môi trường mục tiêu, nếu
   không ép thì `getProcessCpuLoad()` sẽ chuẩn hoá theo số lõi thật, cho kết quả sai lệch.
6. Không dùng Gateway, không dùng mạng — đúng phạm vi spike ("Không cần Gateway, không cần mạng").

## Constraints
- Không đo hành vi khi nhiều pod chạy song song (out of scope — spike chỉ đo 1 pod/JVM).
- Không đo dưới tải nền khác (không có traffic Netty/Gateway thật chạy cùng lúc).
- Máy chạy spike là máy dev (Windows, GraalVM JDK 25), không phải container 2 vCPU thật trên
  K8s — `-XX:ActiveProcessorCount=2` mô phỏng số lõi JVM thấy được, không mô phỏng CPU quota/
  throttling thật của cgroup.

## Findings

### What works
- **Chạy 2 lần độc lập, kết quả nhất quán:**

  | Lần | Mẫu | p50 lệch (ms) | p90 lệch (ms) | p99 lệch (ms) | Max lệch (ms) | CPU trung bình | CPU đỉnh |
  |---|---|---|---|---|---|---|---|
  | 1 | 90.899 | 16 | 28 | **37** | 54 | 1.1% | 5.0% |
  | 2 | 91.251 | 15 | 28 | **36** | 44 | 1.9% | 6.1% |

- **p99 độ lệch ≈ 36–37ms, dưới ngưỡng 50ms** yêu cầu trong plan.md — kể cả actor bắn đúng lúc
  timer wheel đang bận xử lý 1.000 timer khác, độ trễ tăng thêm vẫn nhỏ.
- **CPU trung bình 1–2%, đỉnh ~5–6%** ở 2 vCPU — rất xa ngưỡng 30%. 1.000 timer × 5 lần/giây
  (chu kỳ 200ms) chỉ tạo ~5.000 message/giây tổng cộng, một tải nhẹ so với năng lực scheduler.
- Cơ chế **single-shot tự hẹn lại** (đúng thiết kế ADR-4, không phải periodic timer) hoạt động
  ổn định suốt 20 giây × 2 lần chạy, không có actor nào "rớt nhịp" (mất timer, không bắn lại).

### What doesn't / risks
- Kết quả đo trên máy dev nhiều lõi hơn 2, dùng `-XX:ActiveProcessorCount` để giới hạn JVM thấy
  2 lõi — đây là mô phỏng gần đúng, **không phải** đo trên container K8s có CPU quota/throttling
  thật (cgroup CFS quota có thể gây stall khác với việc JVM chỉ "thấy" ít lõi hơn). PH-1 (load
  test harness thật) vẫn là nơi cần xác nhận số liệu này ở môi trường gần production hơn.
- Spike đo phòng "rỗng" (actor không xử lý business logic thật — không chấm điểm, không encode
  protobuf, không ghi Redis snapshot). Ở tải thật, CPU actor còn phải cạnh tranh với các việc đó
  — biên độ an toàn (30% ngưỡng, thực đo dưới 6%) đủ lớn để hấp thụ phần chênh lệch này, nhưng
  đây vẫn là một giả định chưa kiểm chứng bằng số đo thật.
- Chưa đo kịch bản 1.000 phòng **đồng loạt** dirty cùng một thời điểm (spike dùng jitter ngẫu
  nhiên để trải nhịp, đúng theo cách làm plan.md yêu cầu — nhưng đây cũng là kịch bản THỰC TẾ
  hay xảy ra nhất theo §6.4/§6.5, "Connection Storm 09:00", chứ không phải trường hợp xấu nhất
  lý thuyết là toàn bộ 1.000 timer cùng hết hạn trong 1 tick 10ms).

### Open questions remaining
- Số liệu CPU/độ lệch ở quy mô lớn hơn 1.000 timer/pod (ví dụ khi một pod phải gánh nhiều hơn
  ~300–375 phòng dự kiến do lệch tải) chưa được đo — vẫn thuộc PH-1.
- Hành vi khi actor xử lý business logic thật (không rỗng) cạnh tranh CPU với scheduler chưa
  được đo trong spike này.

## Decision / Recommendation

**Decision: GO** — giữ nguyên thiết kế single-shot timer mỗi actor (ADR-4), **không cần** đổi
sang flush-wheel gom lô ở tầng pod.

**Reasoning:** Cả hai tiêu chí "đạt" của plan.md đều vượt qua rõ ràng và nhất quán qua 2 lần
chạy độc lập: p99 độ lệch 36–37ms (< 50ms) và CPU đỉnh 5–6% (< 30%) ở 2 vCPU. Biên độ an toàn
đủ lớn (CPU đỉnh chỉ bằng 1/5 ngưỡng) để hấp thụ chi phí xử lý business logic thật mà spike
chưa đo tới. Không có tín hiệu nào cho thấy hashed-wheel scheduler của Pekko là nút thắt ở quy
mô 1.000 timer/pod.

**Next step nếu go:** Tiếp tục Task 3 (tick coalescing) theo đúng thiết kế đã tài liệu hoá ở
ADR-4/§6.2 — dùng `startSingleTimer` tự hẹn lại mỗi phòng, không cần thiết kế thay thế. Ghi chú
lại kết quả spike này khi review Task 3 để người review không phải tự hỏi "sao lại tin timer
mỗi actor mà không đo".

**Estimated effort nếu go:** không đổi ước lượng Task 3 trong plan.md — spike xác nhận thiết kế
hiện tại, không phát sinh việc thiết kế lại.
