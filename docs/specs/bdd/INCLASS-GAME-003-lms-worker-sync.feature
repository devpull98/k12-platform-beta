# language: vi
Tính năng: Tích hợp và Tương thích với LMS Worker (INCLASS-GAME-003)
  Là hệ thống uni-realtime mới
  Tôi muốn phát các sự kiện kết quả thi đấu nhóm và thảo luận qua Kafka topic game.events.v1
  Để ứng dụng lms-worker hiện tại ghi nhận cúp thành tích và lịch sử bài tập của học sinh mà không làm chậm Hot Path

  Bối cảnh:
    Giả sử Hệ thống Kafka broker đang hoạt động và listening topic "game.events.v1"
    Và Dịch vụ lms-worker đang chạy các event listener: SubmitExerciseListener, ActiveGroupDiscussionListener, VoteGroupNameListener

  Kịch bản: Đẩy sự kiện hoàn thành bài tập nhóm sang Kafka sau khi ván đấu kết thúc
    Giả sử Team A hoàn thành ván đấu nhóm trong phòng "room-team-202"
    Khi Server RoomActor xử lý xong sự kiện GameOver
    Thì GameEventPublisher đẩy sự kiện TeamSubmitExerciseEvent dạng Protobuf/JSON sang Kafka topic "game.events.v1"
    Và Tiến trình nộp bài trên Hot Path không bị block (Kafka Producer max.block.ms = 0)
    Thì Listener SubmitExerciseListener trong lms-worker nhận được event từ Kafka
    Và lms-worker cập nhật cúp danh dự và điểm số tích lũy vào CSDL PostgreSQL thành công

  Kịch bản: Đẩy sự kiện Bầu chọn tên nhóm (Vote Group Name) sang LMS Worker
    Khi Các thành viên Team A gửi tin nhắn VoteGroupName với tên nhóm "Biệt Đội Siêu Anh Hùng"
    Thì RoomActor chốt tên nhóm và gửi VoteGroupNameEvent sang Kafka topic "game.events.v1"
    Thì Listener VoteGroupNameListener trong lms-worker nhận event và lưu tên nhóm vào phiên học trong DB
