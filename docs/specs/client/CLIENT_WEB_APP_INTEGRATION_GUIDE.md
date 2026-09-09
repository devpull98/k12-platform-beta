# Tài Liệu Tích Hợp Client Web / Mobile App (Realtime Game WebSocket)

**Dành cho:** Đội phát triển Frontend (Web Client Next.js/React, Embedded WebView, Mobile App Flutter/Dart).

---

## 1. Tổng Quan về Giao Thức Kết Nối

* **Kênh kết nối:** Native WebSocket qua TLS (`wss://gateway.domain.com/ws`).
* **Định dạng dữ liệu:** Protobuf 3 Nhị phân (Binary Envelope `GameMessage`).
* **Quy tắc tuyệt đối:**
  * ❌ **KHÔNG** dùng JSON, Socket.io, SignalR hay STOMP.
  * ❌ **KHÔNG** dùng `client_timestamp_ms` để tự tính điểm ở Client (Server là Authoritative quyết định thời gian nộp bài).
  * ✅ Dùng thư viện Official **Protobuf JS / ts-proto** (Web/Next.js/WebView) hoặc **Google Protobuf** (Flutter/Dart) để parse/encode binary frame `ArrayBuffer`.

---

## 2. Quy Trình Khởi Tạo Kết Nối & Xác Thực (Handshake Flow)

```text
Client Web/App               POST /session/{id}/join           REST Auth API
     │ ──────────────────────────────────────────────────────────────>│
     │ <──────────────────────────────────────────────────────────────│
     │                      trả về join_token (JWT)                   │
     │                                                                │
     │               wss://gateway.domain.com/ws                      │
     │ ──────────────────────────────────────────────────────────────>│ Gateway Edge
     │              WebSocket Connected (Binary ArrayBuffer)          │
     │                                                                │
     │ ──► JOIN_ROOM { join_token, display_name } (Message Type 1) ──►│
     │                                                                │ Verify Auth Token
     │ <── ROOM_STATE_SNAPSHOT (full = true) (Message Type 20) ───────│ Bind Channel Attributes
```

1. **Bước 1 (Lấy Token)**: Client gọi API REST HTTP `POST /session/{session_id}/join` nhận `join_token`.
2. **Bước 2 (Mở WebSocket)**: Kết nối tới URL Gateway WebSocket (định dạng Binary `arraybuffer`).
3. **Bước 3 (Gửi Join)**: Ngay khi socket ở trạng thái `OPEN`, gửi ngay gói `GameMessage` có `type = JOIN_ROOM` mang theo `join_token`.
4. **Bước 4 (Nhận Snapshot)**: Gateway xác thực token thành công $\rightarrow$ Server trả về gói `ROOM_STATE_SNAPSHOT` có `full = true` chứa toàn bộ trạng thái hiện tại của phòng.

---

## 3. Cấu Trúc Envelope Dùng Chung (`GameMessage`)

Mọi thông điệp gửi/nhận qua WebSocket **đều phải bọc trong Envelope Protobuf `GameMessage`**:

```protobuf
message GameMessage {
  MessageType type = 1;               // Enum định danh loại thông điệp
  string room_id = 2;                 // UUID phòng thi/game
  string student_id = 3;              // ID học sinh
  uint32 student_index = 4;           // Chỉ số ngắn (0-11) đại diện học sinh trong phòng
  uint64 sequence = 5;                // Số đếm tăng đơn điệu do Client quản lý (1, 2, 3...)
  int64 client_timestamp_ms = 6;      // Mốc thời gian máy client (Telemetry/Log UI only)

  oneof payload {
    // Client -> Server
    JoinRoom join_room = 20;
    SubmitAnswer submit_answer = 21;
    Resync resync = 22;
    TeacherCommand teacher_command = 23;

    // Server -> Client
    RoomStateSnapshot room_state_snapshot = 40;
    AnswerAck answer_ack = 41;
    QuestionStarted question_started = 42;
    StudentJoined student_joined = 43;
    GameOver game_over = 44;
    ConnectionDegraded connection_degraded = 45;
    CommittedSeq committed_seq = 46;
  }
}
```

---

## 4. Quản Lý Kết Nối & Tự Phục Hồi (Client Resilience & Resync)

### 4.1. Vòng Lặp Heartbeat (Ping/Pong)
* Client phải chủ động gửi `GameMessage` với `type = HEARTBEAT` định kỳ **mỗi 15–20 giây**.
* Tần suất tối đa cho phép: 2 gói / 30 giây (gửi quá nhanh sẽ bị Rate Limit).

### 4.2. Quản Lý Ring Buffer (Ring Buffer N=10 trên Client)
* Client duy trì 1 mảng tạm (Ring Buffer) chứa các gói `SUBMIT_ANSWER` đã gửi nhưng **chưa được dọn dẹp**.
* **Quy tắc dọn dẹp Ring Buffer**: 
  * Gói `ANSWER_ACK` chỉ là phản hồi tạm thời của Server. 
  * Client **chỉ được xóa gói `sequence N` khỏi Ring Buffer** khi nhận được gói **`COMMITTED_SEQ`** chứa `sequence >= N` cho học sinh đó (báo hiệu dữ liệu đã được lưu an toàn xuống Valkey).

### 4.3. Quy Trình Reconnect & Resync Khi Rớt Mạng
1. Khi WebSocket bị đứt (`onclose` / `onerror`), Client thực hiện tự động kết nối lại (Exponential Backoff: 1s, 2s, 4s, tối đa 10s).
2. Khi kết nối lại thành công, Client **KHÔNG gửi `JOIN_ROOM`**, mà gửi gói **`RESYNC`**:
   ```protobuf
   message Resync {
     uint64 last_acked_seq = 1;          // Sequence cuối cùng đã nhận ACK thành công
     repeated GameMessage pending = 2;   // Toàn bộ các gói SUBMIT_ANSWER đang chờ trong Ring Buffer
   }
   ```
3. Engine sẽ tự động chống trùng câu nộp qua `LastSeenSequenceTable` và chấm điểm bù chính xác cho học sinh mà không bị mất đáp án.

### 4.4. Xử Lý Cảnh Báo `CONNECTION_DEGRADED`
* Khi nhận thông điệp `CONNECTION_DEGRADED` từ Server (do máy chủ Engine đang khôi phục sự cố):
* ⚠️ **CLIENT TUYỆT ĐỐI KHÔNG ĐƯỢC TỰ TẮT SOCKET HOẶC THỬ RECONNECT!**
* **Hành động đúng của Client**: Giữ nguyên kết nối WebSocket, hiển thị Banner cảnh báo trên giao diện: *"Hệ thống đang kết nối lại phía máy chủ, vui lòng giữ nguyên màn hình..."*.

---

## 5. Danh Sách Chi Tiết Các Thông Điệp (Messages)

### 📤 A. Messages Client GỬI -> Server

| Type Enum | Message Payload | Mô tả |
| :--- | :--- | :--- |
| **`JOIN_ROOM` (1)** | `JoinRoom` | Gửi ngay sau khi mở Socket để xác thực token vào phòng. |
| **`SUBMIT_ANSWER` (2)** | `SubmitAnswer` | Nộp đáp án (`question_id`, `answer_ids[]`, `free_text`). Cần tăng `sequence`. |
| **`RESYNC` (3)** | `Resync` | Gửi sau khi Reconnect để gửi bù các gói tin chưa commit trong Ring Buffer. |
| **`HEARTBEAT` (5)** | *(Không có payload)* | Ping duy trì kết nối định kỳ 15–20 giây. |
| **`UPDATE_DRAFT` (6)** | *(Không có payload)* | Đồng bộ bản nháp gõ chung trong game nhóm/đồng đội. |
| **`TEACHER_COMMAND` (4)**| `TeacherCommand` | Lệnh của Giáo viên (`START_GAME`, `NEXT_STEP`, `PAUSE`, `END_GAME`, `KICK_STUDENT`). |

### 📥 B. Messages Server GỬI -> Client

| Type Enum | Message Payload | Loại Tin | Mô tả & Cách Xử Lý Ở Client |
| :--- | :--- | :--- | :--- |
| **`ROOM_STATE_SNAPSHOT` (20)** | `RoomStateSnapshot` | Best-effort | Cập nhật trạng thái phòng, danh sách điểm `players[]`, `broadcast_seq`, `deadline_ms`. Nếu `full = true` $\rightarrow$ render lại toàn bộ UI phòng. |
| **`ANSWER_ACK` (21)** | `AnswerAck` | **CRITICAL** | Phản hồi kết quả nộp bài tức thì (`accepted`, `awarded_points`, `total_score`, `reject_reason`, `response_time_ms`). Render hiệu ứng cộng điểm/thông báo. |
| **`QUESTION_STARTED` (22)** | `QuestionStarted` | **CRITICAL** | Bắt đầu câu hỏi mới (`question_id`, `prompt`, `choices[]`, `duration_ms`). Bắt đầu đếm ngược đồng hồ câu hỏi. |
| **`STUDENT_JOINED` (23)** | `StudentJoined` | Best-effort | Thông báo có học sinh mới vào phòng (`student_id`, `display_name`, `student_index`). |
| **`GAME_OVER` (24)** | `GameOver` | **CRITICAL** | Kết thúc game. Trả về bảng xếp hạng chung cuộc `final_standings[]`. |
| **`CONNECTION_DEGRADED` (25)**| `ConnectionDegraded`| **CRITICAL** | Server thông báo sự cố tạm thời. **Giữ nguyên kết nối**, hiển thị degraded banner UI. |
| **`COMMITTED_SEQ` (26)** | `CommittedSeq` | Best-effort | Danh sách `sequence` đã ghi đĩa an toàn. Client đối chiếu và **xóa các item $\le sequence$ khỏi Ring Buffer**. |
| **`STUDENT_KICKED` (27)** | *(Không có payload)* | **CRITICAL** | Thông báo học sinh bị giáo viên Kick out. Server sẽ đóng socket ngay sau gói này. |

---

## 6. Danh Sách Mã Lỗi Phản Hồi (`RejectReason`)

Khi nhận `ANSWER_ACK` với `accepted = false`, kiểm tra `reject_reason`:

| RejectReason Enum | Ý Nghĩa | Hướng Xử Lý Ở UI Client |
| :--- | :--- | :--- |
| `PAST_DEADLINE` (2) | Nộp quá hạn giờ câu hỏi. | Hiển thị: *"Rất tiếc, đã hết thời gian nộp bài!"*. |
| `DUPLICATE_SEQUENCE` (3) | Gói nộp bị trùng `sequence`. | Bỏ qua (Server đã ghi nhận lần nộp trước). |
| `UNKNOWN_QUESTION` (4) | `question_id` không hợp lệ. | Cảnh báo lỗi dữ liệu câu hỏi. |
| `WRONG_PHASE` (5) | Phòng chưa ở trạng thái `PLAYING`. | Hiển thị: *"Vui lòng chờ giáo viên bắt đầu trận đấu!"*. |
| `RATE_LIMIT_EXCEEDED` (6) | Gửi quá 3 nộp bài / 1 giây. | Hiển thị: *"Thao tác quá nhanh, vui lòng thử lại sau 1 giây!"*. |

---

## 7. Hướng Dẫn Tích Hợp Chi Tiết Theo Nền Tảng Client

### 🌐 A. Next.js Web App (TypeScript / Client Component)

```typescript
'use client';

import { useEffect, useRef } from 'react';
import { GameMessage, MessageType } from '@/proto/game_message';

export function useGameWebSocket(joinToken: string) {
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    const ws = new WebSocket(process.env.NEXT_PUBLIC_WS_URL!);
    ws.binaryType = 'arraybuffer'; // BẮT BUỘC

    ws.onopen = () => {
      const joinEnvelope = GameMessage.encode({
        type: MessageType.JOIN_ROOM,
        joinRoom: { joinToken, displayName: 'Học Sinh Web', clientVersion: '1.0.0' }
      }).finish();
      
      ws.send(joinEnvelope);
    };

    ws.onmessage = (event: MessageEvent) => {
      const bytes = new Uint8Array(event.data as ArrayBuffer);
      const message = GameMessage.decode(bytes);

      switch (message.type) {
        case MessageType.ROOM_STATE_SNAPSHOT:
          console.log('Room Snapshot:', message.roomStateSnapshot);
          break;
        case MessageType.ANSWER_ACK:
          console.log('Answer Ack:', message.answerAck);
          break;
      }
    };

    wsRef.current = ws;
    return () => ws.close();
  }, [joinToken]);
}
```

---

### 📱 B. Embedded WebView (Mobile App Hybrid Container)

```typescript
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') {
    // App active trở lại -> Kiểm tra nếu socket đóng thì Reconnect & gửi RESYNC ngay
    checkConnectionAndTriggerResync();
  }
});
```

---

### 💙 C. Flutter Mobile App (Dart Native)

```dart
import 'package:flutter/widgets.dart';
import 'package:web_socket_channel/io.dart';
import 'package:generated/game_message.pb.dart';

class GameWebSocketService with WidgetsBindingObserver {
  IOWebSocketChannel? _channel;
  bool _isConnected = false;

  void initWebSocket(String wsUrl, String joinToken) {
    WidgetsBinding.instance.addObserver(this);

    _channel = IOWebSocketChannel.connect(Uri.parse(wsUrl));
    _isConnected = true;

    _channel!.stream.listen((dynamic data) {
      final message = GameMessage.fromBuffer(data as List<int>);
      
      if (message.type == MessageType.ROOM_STATE_SNAPSHOT) {
        print('Snapshot: ${message.roomStateSnapshot}');
      } else if (message.type == MessageType.ANSWER_ACK) {
        print('Ack: ${message.answerAck}');
      }
    }, onDone: () {
      _isConnected = false;
    });

    final joinMessage = GameMessage()
      ..type = MessageType.JOIN_ROOM
      ..joinRoom = (JoinRoom()
        ..joinToken = joinToken
        ..displayName = 'Học sinh Flutter'
        ..clientVersion = '1.0.0');

    _channel!.sink.add(joinMessage.writeToBuffer());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_isConnected) {
      reconnectAndResync();
    }
  }

  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _channel?.sink.close();
  }
}
```

---

## 8. Ví Dụ Thực Tế 1: Tích Hợp Game Trắc Nghiệm 4 Đáp Án (4-Choice Quiz Game)

Kịch bản: Học sinh tham gia một trận đấu Quiz Trắc Nghiệm. Mỗi câu hỏi có 4 đáp án (A, B, C, D) và thời gian đếm ngược 15 giây.

```text
[ Giáo Viên Bấm Bắt Đầu ]
          │
          ▼ (Server gửi QUESTION_STARTED)
┌─────────────────────────────────────────────────────────┐
| Câu 1: Thủ đô của Việt Nam là gì? (15s)                  |
|  [A] Đà Nẵng   [B] Hà Nội   [C] TP.HCM   [D] Hải Phòng  |
└─────────────────────────────────────────────────────────┘
          │ (Học sinh chọn [B])
          ▼
   Client tăng sequence = 1
   Gửi SUBMIT_ANSWER { question_id: "q_101", answer_ids: ["ans_b"] }
   Lưu gói tin vào Ring Buffer [seq_1]
          │
          ├──────────────────────────────────────────┐
          ▼ (Server phản hồi tức thì)                 ▼ (Server xác nhận sau snapshot)
   Nhận ANSWER_ACK { seq: 1, accepted: true }  Nhận COMMITTED_SEQ { seq: 1 }
   👉 Render UI: "+100 điểm!" màu xanh lá      👉 Xóa seq_1 khỏi Ring Buffer
```

### 📋 Checklist Các Bước Cần Làm Ở Client:

1. **Khởi tạo State Ban Đầu**:
   * Biến `sequence = 0` (tăng 1 mỗi lần học sinh gửi câu trả lời).
   * Mảng `ringBuffer = []` (lưu các câu đã gửi nhưng chưa commit).
   * Biến `selectedAnswerId = null` (đánh dấu đáp án đã chọn trong câu hiện tại).

2. **Bước 1: Lắng nghe `QUESTION_STARTED` (Bắt đầu câu hỏi mới)**:
   * Khi nhận gói `QUESTION_STARTED`:
     * Reset `selectedAnswerId = null`.
     * Lưu `currentQuestionId = message.questionStarted.questionId`.
     * Render giao diện 4 nút A, B, C, D từ danh sách `choices[]`.
     * Khởi chạy đồng hồ đếm lùi UI: `timeLeft = message.questionStarted.durationMs / 1000` (15 giây).

3. **Bước 2: Học sinh bấm chọn đáp án B ("ans_b")**:
   * Kiểm tra điều kiện: Nếu `selectedAnswerId != null` (đã chọn rồi) thì skip không cho bấm đúp.
   * Đánh dấu `selectedAnswerId = "ans_b"`.
   * Tăng `sequence = sequence + 1` (ví dụ `sequence = 1`).
   * Tạo gói tin `GameMessage`:
     ```typescript
     const submitMsg = GameMessage.encode({
       type: MessageType.SUBMIT_ANSWER,
       sequence: BigInt(sequence),
       submitAnswer: {
         questionId: currentQuestionId,
         answerIds: ['ans_b']
       }
     }).finish();
     ```
   * **Đưa vào Ring Buffer**: `ringBuffer.push({ sequence: 1, msg: submitMsg })`.
   * **Gửi qua WebSocket**: `ws.send(submitMsg)`.

4. **Bước 3: Lắng nghe `ANSWER_ACK` từ Server**:
   * Đối chiếu `message.answerAck.ackedSequence == 1`.
   * Nếu `accepted == true`:
     * Hiệu ứng UI: Đổi màu nút B thành xanh lá, hiển thị popup: `+100 điểm` (`awarded_points`).
     * Cập nhật tổng điểm trên UI: `total_score`.
   * Nếu `accepted == false`:
     * Kiểm tra `reject_reason` (ví dụ `PAST_DEADLINE`): Hiển thị thông báo *"Đã hết giờ nộp bài!"*.

5. **Bước 4: Lắng nghe `COMMITTED_SEQ` (Xác nhận lưu an toàn)**:
   * Khi Server gửi `COMMITTED_SEQ` chứa `entry.sequence >= 1`:
   * Xóa item `sequence 1` ra khỏi `ringBuffer`. Mảng `ringBuffer` trở về rỗng.

---

## 9. Ví Dụ Thực Tế 2: Tích Hợp Sự Kiện Game Nhóm — Bầu Chọn Tên Nhóm (Team Vote Event)

Kịch bản: Phòng 12 học sinh được chia làm 4 Nhóm (mỗi nhóm 3 học sinh). Mở sự kiện bầu chọn Tên Nhóm từ 4 lựa chọn (A: *Chiến Binh Thép*, B: *Biệt Đội Rồng*, C: *Phượng Hoàng Lửa*, D: *Đại Đại Cát*) trong **10 giây**.

```text
[ Server Engine ]                    [ Gateway Edge ]               [ 3 Học Sinh Nhóm 1 ]
       │                                     │                                │
       │── 1. Broadcast QUESTION_STARTED ───>│───────────────────────────────>│
       │    (Choices: A, B, C, D | 10s)      │                                │ (Hiện 4 nút chọn)
       │                                     │                                │
       │                                     │<── 2. SUBMIT_ANSWER (opt_b) ───│ (HS A chọn B)
       │<── Forward SUBMIT_ANSWER (HS A) ────│                                │
       │    (Team 1: opt_b = 1 vote)         │                                │
       │                                     │                                │
       │── 3. Broadcast Delta SNAPSHOT ─────>│───────────────────────────────>│
       │    (Team 1: opt_b = 1 vote)         │                                │ (HS B & C thấy nút B
       │                                     │                                │  nhảy 1/3 phiếu!)
       │                                     │                                │
       │── 4. 10s Deadline Reached ──────────│                                │
       │    (Calculate Winner: opt_b)        │                                │
       │── 5. Broadcast SNAPSHOT (Final) ───>│───────────────────────────────>│ (Cả nhóm chốt tên
       │    (team_1_name = "Biệt Đội Rồng")  │                                │  "Biệt Đội Rồng")
```

### 📋 Mẫu File Cấu Hình Game (`game_definition.json`):

```json
{
  "game_id": "inclass_group_quiz_v2.1",
  "start_step_id": "step_01_vote_team_name",
  "tick_mode": "COALESCE",
  "missed_step_policy": "ZERO",
  "max_transitions": 50,
  "scoring_formula": {
    "type": "ADD",
    "left": {
      "type": "MULTIPLY",
      "left": { "type": "IS_CORRECT" },
      "right": { "type": "CONSTANT", "value": 100 }
    },
    "right": { "type": "CONSTANT", "value": 0 }
  },
  "steps": [
    {
      "step_id": "step_01_vote_team_name",
      "step_type": "TEAM_VOTE",
      "duration_ms": 10000,
      "vote_rule": "MAJORITY_WINS",
      "next_step_ids": ["step_02_quiz_q1"]
    },
    {
      "step_id": "step_02_quiz_q1",
      "step_type": "QUIZ_4_CHOICE",
      "duration_ms": 15000,
      "next_step_ids": []
    }
  ]
}
```

### 📊 Bảng Tóm Tắt Trạng Thái Giữa Các Bên (Timeline):

| Mốc Thời Gian | Thao Tác Học Sinh A | Thao Tác Học Sinh B & C | Data Server Lưu Trữ (RAM Actor) | UI Hiển Thị Tại Client |
| :--- | :--- | :--- | :--- | :--- |
| **t = 0s** | Nhìn 4 lựa chọn | Nhìn 4 lựa chọn | Vote Count: `[A:0, B:0, C:0, D:0]` | Đếm ngược 10s, 4 nút chưa ai chọn. |
| **t = 2.0s** | Bấm chọn **B** | Đang suy nghĩ | Vote Count: `[A:0, B:1, C:0, D:0]` | HS A thấy nút B sáng lên. |
| **t = 2.2s** | Chờ đồng đội | Thấy nút B nhảy số | Vote Count: `[A:0, B:1, C:0, D:0]` | HS B & C thấy Option B hiện **1/3 phiếu**. |
| **t = 4.5s** | Chờ đồng đội | HS B bấm chọn **B** | Vote Count: `[A:0, B:2, C:0, D:0]` | Cả nhóm thấy Option B hiện **2/3 phiếu**. |
| **t = 10s** | Hết giờ | Hết giờ | **Chốt Tên Nhóm 1**: *"Biệt Đội Rồng"* | Đổi tên Nhóm 1 thành **"Biệt Đội Rồng"**! |

---

## 10. Mẫu Code Client Hoàn Chỉnh Quản Lý Kết Nối WebSocket (Production-Ready Class)

Dưới đây là class `RealtimeGameClient.ts` viết bằng TypeScript hoàn chỉnh, xử lý đầy đủ: Khởi tạo kết nối, Heartbeat timer, Tự động Reconnect (Exponential Backoff), Quản lý Ring Buffer và Parse Protobuf Nhị phân:

```typescript
import { GameMessage, MessageType, RejectReason } from '@/proto/game_message';

export interface GameClientConfig {
  wsUrl: string;
  joinToken: string;
  displayName: string;
  onSnapshot?: (snapshot: any) => void;
  onAnswerAck?: (ack: any) => void;
  onQuestionStarted?: (question: any) => void;
  onConnectionDegraded?: (reason: string) => void;
}

interface PendingItem {
  sequence: bigint;
  messageBytes: Uint8Array;
}

export class RealtimeGameClient {
  private ws: WebSocket | null = null;
  private config: GameClientConfig;
  private sequence: bigint = 0n;
  private lastAckedSeq: bigint = 0n;
  private ringBuffer: PendingItem[] = [];
  private heartbeatInterval: any = null;
  private isReconnecting = false;
  private reconnectDelay = 1000;

  constructor(config: GameClientConfig) {
    this.config = config;
  }

  // 1. Kết nối tới WebSocket Gateway
  public connect() {
    this.ws = new WebSocket(this.config.wsUrl);
    this.ws.binaryType = 'arraybuffer'; // BẮT BUỘC CHO PROTOBUF BINARY

    this.ws.onopen = () => this.handleOpen();
    this.ws.onmessage = (event) => this.handleMessage(event);
    this.ws.onerror = (err) => console.error('WebSocket Error:', err);
    this.ws.onclose = () => this.handleClose();
  }

  // 2. Xử lý khi WebSocket vừa mở
  private handleOpen() {
    console.log('WebSocket Connected!');
    this.reconnectDelay = 1000; // Reset delay

    if (!this.isReconnecting) {
      // Kết nối lần đầu -> Gửi JOIN_ROOM
      this.sendJoinRoom();
    } else {
      // Reconnect lại sau khi rớt mạng -> Gửi RESYNC mang theo Ring Buffer
      this.sendResync();
      this.isReconnecting = false;
    }

    // Khởi chạy Heartbeat Ping định kỳ 15 giây
    this.startHeartbeat();
  }

  // 3. Gửi thông điệp JOIN_ROOM
  private sendJoinRoom() {
    const joinMessage = GameMessage.encode({
      type: MessageType.JOIN_ROOM,
      joinRoom: {
        joinToken: this.config.joinToken,
        displayName: this.config.displayName,
        clientVersion: '1.0.0'
      }
    }).finish();

    this.ws?.send(joinMessage);
  }

  // 4. Gửi nộp đáp án (SUBMIT_ANSWER) + Đưa vào Ring Buffer
  public submitAnswer(questionId: string, answerIds: string[], freeText: string = '') {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('Cannot submit answer: WebSocket is not open.');
      return;
    }

    this.sequence += 1n; // Tăng sequence đơn điệu

    const messageBytes = GameMessage.encode({
      type: MessageType.SUBMIT_ANSWER,
      sequence: this.sequence,
      submitAnswer: { questionId, answerIds, freeText }
    }).finish();

    // Thêm vào Ring Buffer để bảo vệ rủi ro đứt mạng
    this.ringBuffer.push({ sequence: this.sequence, messageBytes });
    if (this.ringBuffer.length > 10) this.ringBuffer.shift(); // Giữ max N=10 items

    // Gửi ra dây
    this.ws.send(messageBytes);
  }

  // 5. Xử lý dữ liệu nhị phân nhận từ Server
  private handleMessage(event: MessageEvent) {
    const bytes = new Uint8Array(event.data as ArrayBuffer);
    const message = GameMessage.decode(bytes);

    switch (message.type) {
      case MessageType.ROOM_STATE_SNAPSHOT:
        this.config.onSnapshot?.(message.roomStateSnapshot);
        break;

      case MessageType.QUESTION_STARTED:
        this.config.onQuestionStarted?.(message.questionStarted);
        break;

      case MessageType.ANSWER_ACK:
        this.lastAckedSeq = message.answerAck.ackedSequence;
        this.config.onAnswerAck?.(message.answerAck);
        break;

      case MessageType.COMMITTED_SEQ:
        // Server xác nhận đã ghi đĩa an toàn -> Dọn dẹp Ring Buffer
        this.handleCommittedSeq(message.committedSeq);
        break;

      case MessageType.CONNECTION_DEGRADED:
        // Cảnh báo server đang khôi phục -> Giữ nguyên kết nối, hiện Banner
        this.config.onConnectionDegraded?.(message.connectionDegraded.message);
        break;
    }
  }

  // 6. Xóa các item <= committed sequence khỏi Ring Buffer
  private handleCommittedSeq(committedSeq: any) {
    if (!committedSeq || !committedSeq.committed) return;
    
    for (const entry of committedSeq.committed) {
      const seq = entry.sequence;
      this.ringBuffer = this.ringBuffer.filter(item => item.sequence > seq);
    }
  }

  // 7. Gửi RESYNC sau khi Reconnect
  private sendResync() {
    const pendingMessages = this.ringBuffer.map(item => GameMessage.decode(item.messageBytes));

    const resyncBytes = GameMessage.encode({
      type: MessageType.RESYNC,
      resync: {
        lastAckedSeq: this.lastAckedSeq,
        pending: pendingMessages
      }
    }).finish();

    this.ws?.send(resyncBytes);
  }

  // 8. Vòng lặp Heartbeat Ping 15 giây
  private startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeatInterval = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        const hb = GameMessage.encode({ type: MessageType.HEARTBEAT }).finish();
        this.ws.send(hb);
      }
    }, 15000);
  }

  private stopHeartbeat() {
    if (this.heartbeatInterval) clearInterval(this.heartbeatInterval);
  }

  // 9. Xử lý Reconnect khi socket bị đóng
  private handleClose() {
    this.stopHeartbeat();
    console.warn(`WebSocket closed. Reconnecting in ${this.reconnectDelay}ms...`);
    
    this.isReconnecting = true;
    setTimeout(() => {
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, 10000); // Max 10s
      this.connect();
    }, this.reconnectDelay);
  }

  // 10. Ngắt kết nối thủ công
  public disconnect() {
    this.stopHeartbeat();
    if (this.ws) {
      this.ws.onclose = null; // Tránh trigger reconnect
      this.ws.close();
    }
  }
}
```
