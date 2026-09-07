# language: vi
Tính năng: Game Tập Thể Đánh Boss Rồng (INCLASS-GAME-001)
  Là một Học sinh trong lớp học Inclass
  Tôi muốn cùng các bạn trong phòng trả lời câu hỏi đúng để tích lũy phần trăm tiến trình
  Để lớp học cùng nhau hạ gục Boss và hoàn thành ván game

  Bối cảnh:
    Giả sử Hệ thống uni-realtime đang hoạt động với 1 Engine Pod và 1 Gateway Pod
    Và Một phòng game được tạo với ID "room-boss-101" chế độ "COOPERATIVE"
    Và Cấu hình GameDefinition có progress_target = 10 câu đúng và staged_visual = [0%, 30%, 70%, 100%]
    Và 12 học sinh từ "student-01" đến "student-12" đã hoàn tất kết nối WebSocket và tham gia phòng "room-boss-101"

  Kịch bản: Cả phòng đóng góp câu trả lời đúng để tăng tiến trình và hoàn thành Game
    Khi Học sinh "student-01", "student-02", "student-03" đồng thời gửi đáp án đúng SubmitAnswer cho câu số 1
    Thực hiện Server RoomActor xử lý tuần tự 3 câu trả lời
    Thì Tiến trình phòng đạt 3 câu đúng tương đương 30% progress
    Và Server broadcast gói tin RoomStateSnapshot tới 12 học sinh với progress_percentage = 30% và stage_index = 1
    Và Độ trễ phản hồi AnswerAck cho 3 học sinh có latency p99 < 100ms

    Khi Thêm 4 học sinh từ "student-04" đến "student-07" gửi đáp án đúng SubmitAnswer cho câu số 2
    Thì Tiến trình phòng đạt 7 câu đúng tương đương 70% progress
    Và Server broadcast gói tin RoomStateSnapshot với progress_percentage = 70% và stage_index = 2

    Khi 3 học sinh cuối "student-08", "student-09", "student-10" gửi đáp án đúng SubmitAnswer
    Thì Tiến trình phòng đạt 10/10 câu đúng tương đương 100% progress (progress_completed)
    Và Server RoomActor tự động chuyển trạng thái FSM sang FINISHED
    Và Broadcast thông điệp GameOver chiến thắng cho toàn bộ 12 học sinh trong phòng

  Kịch bản: Trả lời sai bị trừ tài nguyên thời gian dùng chung (Shared Resource Penalty)
    Giả sử Cấu hình shared_resource có type = "TIME" và penalty_seconds = 5
    Và Thời hạn câu hỏi step_deadline_at là 30 giây
    Khi Học sinh "student-05" gửi đáp án sai SubmitAnswer
    Thì Server RoomActor phát hiện câu trả lời sai
    Và Trừ trực tiếp 5 giây vào step_deadline_at của phòng
    Và Broadcast gói tin RoomStateSnapshot cập nhật thời gian còn lại giảm 5 giây cho toàn phòng
