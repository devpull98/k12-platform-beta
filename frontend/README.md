# Realtime 4-Choice Quiz Game Frontend

Ứng dụng Frontend (Single Page App - SPA) hỗ trợ game quiz chọn 4 đáp án tương tác thời gian thực, tích hợp trực tiếp với Backend Spring Boot + Netty + Pekko Protobuf WebSocket Gateway.

---

## 🌟 Tính năng chính

1. **4 Đáp án Tương tác (Kahoot Style)**:
   - Giao diện 4 nút lựa chọn sắc màu: 🟥 **A**, 🟦 **B**, 🟨 **C**, 🟩 **D**.
   - Đếm ngược thời gian trực quan theo server timestamp.
   - Phản hồi kết quả tức thì (`ANSWER_ACK`) hiển thị điểm thưởng và thời gian phản hồi (ms).

2. **Protobuf & Binary WebSocket Channel**:
   - Giao thức binary WebSocket trực tiếp tới Netty Gateway (`ws://localhost:9000`).
   - Mã hoá & giải mã gói tin Protobuf `GameMessage` (`JOIN_ROOM`, `SUBMIT_ANSWER`, `ROOM_STATE_SNAPSHOT`, `ANSWER_ACK`, `QUESTION_STARTED`, v.v.).

3. **Dev Join Token HMAC Generator**:
   - Tích hợp sẵn bộ mint Dev Join Token HMAC-SHA256 chuẩn mã nguồn Java `DevJoinTokenCodec.java`.

4. **Chế độ Thử nghiệm Linh hoạt**:
   - **Real BE Mode**: Tích hợp trực tiếp với Backend Java trên cổng WebSocket 9000.
   - **Mock Server Mode**: Cho phép chạy thử giao diện ngay cả khi chưa khởi chạy Backend Java.

---

## 🚀 Hướng dẫn Sử dụng

### 1. Khởi chạy Frontend Web Server

Mở Terminal tại thư mục gốc của dự án và chạy:

```bash
node frontend/server.js
```

Sau đó truy cập trình duyệt tại địa chỉ: **`http://localhost:3000`**

---

### 2. Khởi chạy Backend Java (Để test tích hợp Real BE)

1. **Khởi chạy Engine**:
   ```bash
   mvn -pl :uni-game-engine spring-boot:run
   ```
2. **Khởi chạy Gateway** (với profile `dev-docker` để bật dev join-token):
   ```bash
   mvn -pl :uni-websocket-gateway spring-boot:run -Dspring-boot.run.profiles=dev-docker
   ```

---

### 3. Thử nghiệm trên Trang Web

1. **Vào phòng game**: Nhập Tên hiển thị (ví dụ: `Nguyễn Văn A`) và Mã phòng (ví dụ: `room-101`) -> Bấm **Vào Phòng Game**.
2. **Điều khiển câu hỏi**: Bấm nút **⚙️ Cấu hình Dev** ở góc trên bên phải để:
   - Gửi lệnh `START_GAME` của Giáo viên.
   - Hoặc bấm **⚡ Mock: Phát câu hỏi mới** để phát câu hỏi thử nghiệm lập tức.
3. **Trả lời & Xem bảng xếp hạng**: Chọn đáp án A/B/C/D và quan sát điểm số cập nhật thời gian thực trên Bảng xếp hạng bên phải.
