# language: vi
Tính năng: Game Chia Nhóm Thi Đấu Tốc Độ (INCLASS-GAME-002)
  Là các Học sinh trong phòng game Inclass
  Chúng tôi muốn được chia thành các đội nhóm thi đấu tốc độ và chia sẻ bản nháp gõ chung
  Để đội hoàn thành tiến trình nhanh nhất giành chiến thắng

  Bối cảnh:
    Giả sử Một phòng game được tạo với ID "room-team-202" chế độ "TEAM"
    Và Phòng được chia thành 4 đội: Team A (HS 1-3), Team B (HS 4-6), Team C (HS 7-9), Team D (HS 10-12)
    Và Cấu hình win_condition = "first_to_finish", progress_target = 5 câu đúng mỗi đội

  Kịch bản: Đồng bộ bản nháp gõ chung giữa các thành viên trong cùng nhóm (Scoped Draft Sync)
    Khi Học sinh "student-01" thuộc Team A gõ đáp án "Phương trình bậc 2" và client phát tin nhắn UpdateDraft
    Thì Server RoomActor nhận tin nhắn UpdateDraft
    Và Server chỉ broadcast tin nhắn DraftUpdated tới "student-02" và "student-03" (các thành viên Team A)
    Và Các học sinh thuộc Team B, C, D tuyệt đối không nhận được tin nhắn DraftUpdated của Team A

  Kịch bản: Đội hoàn thành tiến trình trước giành chiến thắng
    Khi Tất cả 3 học sinh của Team A trả lời đúng đủ 5 câu hỏi trong vòng 15 giây
    Thì Tiến trình của Team A đạt 100% (progress_completed)
    Và Server RoomActor kiểm tra điều kiện win_condition = first_to_finish
    Thì Server công bố Team A là Đội Thắng Cuộc (TeamWinner)
    Và Chuyển trạng thái phòng sang FINISHED
    Và Broadcast thông điệp GameOver chứa bảng xếp hạng thứ tự hoàn thành của 4 đội
