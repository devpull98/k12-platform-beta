/**
 * Realtime 4-Choice Quiz Game - Frontend Application Logic
 * Integrates with Spring Boot Netty Protobuf WebSocket Gateway
 */

// Embedded Protobuf Definition (uni.realtime.v1.GameMessage)
const PROTO_DEF = `
syntax = "proto3";
package uni.realtime.v1;

message GameMessage {
  MessageType type = 1;
  string room_id = 2;
  string student_id = 3;
  uint32 student_index = 4;
  uint64 sequence = 5;
  int64 client_timestamp_ms = 6;

  oneof payload {
    JoinRoom join_room = 20;
    SubmitAnswer submit_answer = 21;
    Resync resync = 22;
    TeacherCommand teacher_command = 23;

    RoomStateSnapshot room_state_snapshot = 40;
    AnswerAck answer_ack = 41;
    QuestionStarted question_started = 42;
    StudentJoined student_joined = 43;
    GameOver game_over = 44;
    ConnectionDegraded connection_degraded = 45;
    CommittedSeq committed_seq = 46;
  }
}

enum MessageType {
  MESSAGE_TYPE_UNSPECIFIED = 0;
  JOIN_ROOM = 1;
  SUBMIT_ANSWER = 2;
  RESYNC = 3;
  TEACHER_COMMAND = 4;
  HEARTBEAT = 5;
  UPDATE_DRAFT = 6;
  STUDENT_LEFT = 7;

  ROOM_STATE_SNAPSHOT = 20;
  ANSWER_ACK = 21;
  QUESTION_STARTED = 22;
  STUDENT_JOINED = 23;
  GAME_OVER = 24;
  CONNECTION_DEGRADED = 25;
  COMMITTED_SEQ = 26;
  STUDENT_KICKED = 27;
}

enum GamePhase {
  GAME_PHASE_UNSPECIFIED = 0;
  LOBBY = 1;
  PLAYING = 2;
  FINISHED = 3;
  RESYNCING = 4;
}

enum RejectReason {
  REJECT_REASON_UNSPECIFIED = 0;
  NONE = 1;
  PAST_DEADLINE = 2;
  DUPLICATE_SEQUENCE = 3;
  UNKNOWN_QUESTION = 4;
  WRONG_PHASE = 5;
  RATE_LIMIT_EXCEEDED = 6;
}

message JoinRoom {
  string join_token = 1;
  string display_name = 2;
  string client_version = 3;
}

message SubmitAnswer {
  string question_id = 1;
  repeated string answer_ids = 2;
  string free_text = 3;
}

message Resync {
  uint64 last_acked_seq = 1;
  repeated GameMessage pending = 2;
}

message TeacherCommand {
  enum Command {
    COMMAND_UNSPECIFIED = 0;
    START_GAME = 1;
    NEXT_STEP = 2;
    PAUSE = 3;
    END_GAME = 4;
    KICK_STUDENT = 5;
  }
  Command command = 1;
  string target_student_id = 2;
}

message RoomStateSnapshot {
  bool full = 1;
  GamePhase phase = 2;
  repeated PlayerState players = 3;
  string current_question_id = 4;
  int64 server_question_started_at_ms = 5;
  int64 deadline_ms = 6;
  uint64 broadcast_seq = 7;
}

message PlayerState {
  string student_id = 1;
  uint32 student_index = 2;
  string display_name = 3;
  uint32 score = 4;
  bool answered_current = 5;
  bool connected = 6;
}

message AnswerAck {
  string question_id = 1;
  uint64 acked_sequence = 2;
  bool accepted = 3;
  RejectReason reject_reason = 4;
  uint32 awarded_points = 5;
  uint32 total_score = 6;
  int64 server_received_at_ms = 7;
  uint32 response_time_ms = 8;
}

message QuestionStarted {
  string question_id = 1;
  uint32 step_index = 2;
  string prompt = 3;
  repeated Choice choices = 4;
  int64 server_question_started_at_ms = 5;
  uint32 duration_ms = 6;
}

message Choice {
  string answer_id = 1;
  string text = 2;
}

message StudentJoined {
  string student_id = 1;
  uint32 student_index = 2;
  string display_name = 3;
}

message GameOver {
  repeated PlayerState final_standings = 1;
  string reason = 2;
}

message ConnectionDegraded {
  enum Reason {
    REASON_UNSPECIFIED = 0;
    ENGINE_UNREACHABLE = 1;
    ROOM_UNAVAILABLE = 2;
    OVERLOADED = 3;
  }
  Reason reason = 1;
  string message = 2;
  bool retryable = 3;
}

message CommittedSeq {
  message Entry {
    string student_id = 1;
    uint64 sequence = 2;
  }
  repeated Entry committed = 1;
}
`;

// Application State
const MOCK_QUESTIONS_10 = [
    {
        questionId: 'q-1',
        stepIndex: 1,
        prompt: '1. Thủ đô của Việt Nam là thành phố nào?',
        durationMs: 15000,
        correctAnswer: 'a',
        choices: [
            { answerId: 'a', text: 'Hà Nội' },
            { answerId: 'b', text: 'TP. Hồ Chí Minh' },
            { answerId: 'c', text: 'Đà Nẵng' },
            { answerId: 'd', text: 'Hải Phòng' }
        ]
    },
    {
        questionId: 'q-2',
        stepIndex: 2,
        prompt: '2. Ngọn núi cao nhất Việt Nam tên là gì?',
        durationMs: 15000,
        correctAnswer: 'b',
        choices: [
            { answerId: 'a', text: 'Núi Bà Đen' },
            { answerId: 'b', text: 'Phan Xi Păng' },
            { answerId: 'c', text: 'Mẫu Sơn' },
            { answerId: 'd', text: 'Núi Yên Tử' }
        ]
    },
    {
        questionId: 'q-3',
        stepIndex: 3,
        prompt: '3. Đơn vị tiền tệ của Việt Nam là gì?',
        durationMs: 15000,
        correctAnswer: 'c',
        choices: [
            { answerId: 'a', text: 'Dollar' },
            { answerId: 'b', text: 'Yên' },
            { answerId: 'c', text: 'Đồng (VND)' },
            { answerId: 'd', text: 'Baht' }
        ]
    },
    {
        questionId: 'q-4',
        stepIndex: 4,
        prompt: '4. Con sông dài nhất chảy qua đất nước Việt Nam là sông nào?',
        durationMs: 15000,
        correctAnswer: 'a',
        choices: [
            { answerId: 'a', text: 'Sông Mê Kông (Sông Cửu Long)' },
            { answerId: 'b', text: 'Sông Hồng' },
            { answerId: 'c', text: 'Sông Đồng Nai' },
            { answerId: 'd', text: 'Sông Hương' }
        ]
    },
    {
        questionId: 'q-5',
        stepIndex: 5,
        prompt: '5. Nguyên tố hóa học nào có ký hiệu là "O"?',
        durationMs: 15000,
        correctAnswer: 'd',
        choices: [
            { answerId: 'a', text: 'Vàng' },
            { answerId: 'b', text: 'Sắt' },
            { answerId: 'c', text: 'Hydro' },
            { answerId: 'd', text: 'Oxy' }
        ]
    },
    {
        questionId: 'q-6',
        stepIndex: 6,
        prompt: '6. Hành tinh nào nằm gần Mặt Trời nhất trong Hệ Mặt Trời?',
        durationMs: 15000,
        correctAnswer: 'a',
        choices: [
            { answerId: 'a', text: 'Sao Thủy (Mercury)' },
            { answerId: 'b', text: 'Sao Kim (Venus)' },
            { answerId: 'c', text: 'Trái Đất' },
            { answerId: 'd', text: 'Sao Hỏa' }
        ]
    },
    {
        questionId: 'q-7',
        stepIndex: 7,
        prompt: '7. Tác phẩm "Truyện Kiều" do đại danh nhân nào sáng tác?',
        durationMs: 15000,
        correctAnswer: 'c',
        choices: [
            { answerId: 'a', text: 'Nam Cao' },
            { answerId: 'b', text: 'Xuân Diệu' },
            { answerId: 'c', text: 'Nguyễn Du' },
            { answerId: 'd', text: 'Tố Hữu' }
        ]
    },
    {
        questionId: 'q-8',
        stepIndex: 8,
        prompt: '8. Tỉnh thành nào có diện tích lớn nhất Việt Nam?',
        durationMs: 15000,
        correctAnswer: 'b',
        choices: [
            { answerId: 'a', text: 'Thanh Hóa' },
            { answerId: 'b', text: 'Nghệ An' },
            { answerId: 'c', text: 'Sơn La' },
            { answerId: 'd', text: 'Đắk Lắk' }
        ]
    },
    {
        questionId: 'q-9',
        stepIndex: 9,
        prompt: '9. 1 Gigabyte (GB) tương đương với bao nhiêu Megabyte (MB)?',
        durationMs: 15000,
        correctAnswer: 'c',
        choices: [
            { answerId: 'a', text: '100 MB' },
            { answerId: 'b', text: '512 MB' },
            { answerId: 'c', text: '1024 MB' },
            { answerId: 'd', text: '2048 MB' }
        ]
    },
    {
        questionId: 'q-10',
        stepIndex: 10,
        prompt: '10. Trận đại thắng Điện Biên Phủ lịch sử diễn ra vào năm nào?',
        durationMs: 15000,
        correctAnswer: 'a',
        choices: [
            { answerId: 'a', text: '1954' },
            { answerId: 'b', text: '1945' },
            { answerId: 'c', text: '1968' },
            { answerId: 'd', text: '1975' }
        ]
    }
];

const state = {
    ws: null,
    protoRoot: null,
    GameMessage: null,
    studentId: 'student-1',
    displayName: 'Nguyễn Văn A',
    roomId: 'room-101',
    sequence: 0,
    currentQuestionId: null,
    currentQuestionDeadline: 0,
    timerInterval: null,
    players: [],
    myScore: 0,
    mode: 'mock', // 'real' or 'mock'
    connected: false
};

// UI Element References
const elements = {
    connectionStatus: document.getElementById('connectionStatus'),
    statusText: document.getElementById('statusText'),
    devSettingsPanel: document.getElementById('devSettingsPanel'),
    toggleDevSettingsBtn: document.getElementById('toggleDevSettingsBtn'),
    wsUrl: document.getElementById('wsUrl'),
    hmacSecret: document.getElementById('hmacSecret'),
    studentIdInput: document.getElementById('studentId'),
    modeSelect: document.getElementById('modeSelect'),
    degradedBanner: document.getElementById('degradedBanner'),
    degradedMsg: document.getElementById('degradedMsg'),
    appModeBadge: document.getElementById('appModeBadge'),

    // Views
    viewJoin: document.getElementById('viewJoin'),
    viewLobby: document.getElementById('viewLobby'),
    viewQuestion: document.getElementById('viewQuestion'),
    viewGameOver: document.getElementById('viewGameOver'),

    // Join Form
    joinForm: document.getElementById('joinForm'),
    displayNameInput: document.getElementById('displayName'),
    roomIdInput: document.getElementById('roomId'),

    // Lobby
    lobbyRoomId: document.getElementById('lobbyRoomId'),
    lobbyStudentName: document.getElementById('lobbyStudentName'),
    lobbyStudentId: document.getElementById('lobbyStudentId'),
    playerAvatar: document.getElementById('playerAvatar'),
    playerCount: document.getElementById('playerCount'),
    rosterGrid: document.getElementById('rosterGrid'),

    // Question
    timerBar: document.getElementById('timerBar'),
    timerText: document.getElementById('timerText'),
    questionStep: document.getElementById('questionStep'),
    questionPrompt: document.getElementById('questionPrompt'),
    btnChoiceA: document.getElementById('btnChoiceA'),
    btnChoiceB: document.getElementById('btnChoiceB'),
    btnChoiceC: document.getElementById('btnChoiceC'),
    btnChoiceD: document.getElementById('btnChoiceD'),
    textChoiceA: document.getElementById('textChoiceA'),
    textChoiceB: document.getElementById('textChoiceB'),
    textChoiceC: document.getElementById('textChoiceC'),
    textChoiceD: document.getElementById('textChoiceD'),
    answerFeedback: document.getElementById('answerFeedback'),
    feedbackCard: document.getElementById('feedbackCard'),
    feedbackIcon: document.getElementById('feedbackIcon'),
    feedbackTitle: document.getElementById('feedbackTitle'),
    feedbackDetail: document.getElementById('feedbackDetail'),

    // Leaderboard
    leaderboardList: document.getElementById('leaderboardList'),
    myScoreBadge: document.getElementById('myScoreBadge'),

    // Console
    consoleBody: document.getElementById('consoleBody'),
    btnClearLogs: document.getElementById('btnClearLogs'),

    // GameOver
    gameOverReason: document.getElementById('gameOverReason'),
    podiumContainer: document.getElementById('podiumContainer'),

    // Dev buttons
    btnSendTeacherStart: document.getElementById('btnSendTeacherStart'),
    btnSendTeacherEnd: document.getElementById('btnSendTeacherEnd'),
    btnTriggerMockQ: document.getElementById('btnTriggerMockQ'),
    btnPlayAgain: document.getElementById('btnPlayAgain'),
    btnLobbyStartGame: document.getElementById('btnLobbyStartGame')
};

// Application State Timeout
let autoAdvanceTimeout = null;

// Initialize Application
document.addEventListener('DOMContentLoaded', async () => {
    initProtobuf();
    bindEvents();
    if (elements.appModeBadge) {
        elements.appModeBadge.textContent = state.mode === 'mock' ? 'MOCK MODE (STANDALONE)' : 'REAL BE INTEGRATION';
    }
});

// Parse Protobuf Schema
function initProtobuf() {
    try {
        if (typeof protobuf !== 'undefined') {
            state.protoRoot = protobuf.parse(PROTO_DEF).root;
            state.GameMessage = state.protoRoot.lookupType('uni.realtime.v1.GameMessage');
            logConsole('Protobuf schema đã nạp thành công!', 'info');
        } else {
            logConsole('Lỗi: Thư viện protobufjs chưa nạp!', 'error');
        }
    } catch (e) {
        logConsole('Protobuf init error: ' + e.message, 'error');
    }
}

// Bind Event Listeners
function bindEvents() {
    elements.toggleDevSettingsBtn.addEventListener('click', () => {
        elements.devSettingsPanel.classList.toggle('hidden');
    });

    elements.btnClearLogs.addEventListener('click', () => {
        elements.consoleBody.innerHTML = '';
    });

    elements.modeSelect.addEventListener('change', (e) => {
        state.mode = e.target.value;
        elements.appModeBadge.textContent = state.mode === 'mock' ? 'MOCK MODE (STANDALONE)' : 'REAL BE INTEGRATION';
        logConsole('Chuyển chế độ: ' + state.mode, 'info');
    });

    elements.joinForm.addEventListener('submit', (e) => {
        e.preventDefault();
        state.displayName = elements.displayNameInput.value.trim();
        state.roomId = elements.roomIdInput.value.trim();
        state.studentId = elements.studentIdInput.value.trim() || 'student-1';

        connectAndJoin();
    });

    // 4 Choice buttons
    [elements.btnChoiceA, elements.btnChoiceB, elements.btnChoiceC, elements.btnChoiceD].forEach(btn => {
        btn.addEventListener('click', () => {
            const choiceId = btn.getAttribute('data-choice');
            submitAnswer(choiceId);
        });
    });

    // Lobby Start Game button
    if (elements.btnLobbyStartGame) {
        elements.btnLobbyStartGame.addEventListener('click', () => {
            if (state.mode === 'mock') {
                triggerMockQuestion();
            } else {
                sendTeacherCommand(1); // START_GAME to Java Engine FSM
                triggerMockQuestion();  // Launch question UI so student plays seamlessly
            }
        });
    }

    // Dev action buttons
    elements.btnSendTeacherStart.addEventListener('click', () => sendTeacherCommand(1)); // START_GAME
    elements.btnSendTeacherEnd.addEventListener('click', () => sendTeacherCommand(4));   // END_GAME
    elements.btnTriggerMockQ.addEventListener('click', triggerMockQuestion);
    elements.btnPlayAgain.addEventListener('click', () => {
        if (state.timerInterval) clearInterval(state.timerInterval);
        if (autoAdvanceTimeout) clearTimeout(autoAdvanceTimeout);
        mockStepIndex = 0;
        state.myScore = 0;
        elements.myScoreBadge.textContent = 'Điểm của bạn: 0';
        state.players.forEach(p => p.score = 0);
        updateRoster(state.players);
        updateLeaderboard(state.players);
        showView(elements.viewLobby);
    });
}

// Helper: Logger
function logConsole(msg, type = 'info') {
    const div = document.createElement('div');
    div.className = `log-entry ${type}`;
    const time = new Date().toLocaleTimeString();
    div.textContent = `[${time}] ${msg}`;
    elements.consoleBody.appendChild(div);
    elements.consoleBody.scrollTop = elements.consoleBody.scrollHeight;
}

// HMAC-SHA256 Dev JoinToken Minting (matches DevJoinTokenCodec.java)
async function mintDevJoinToken(studentId, roomId, secret, roles = 'STUDENT', ttlMs = 300000) {
    const expEpochMs = Date.now() + ttlMs;
    const jti = crypto.randomUUID();
    const sessionId = 'session-' + Math.floor(Math.random() * 1000);
    const payload = `${studentId}|${roomId}|${sessionId}|${roles}|${expEpochMs}|${jti}`;

    function base64UrlEncodeStr(str) {
        const bytes = new TextEncoder().encode(str);
        let binary = '';
        bytes.forEach(b => binary += String.fromCharCode(b));
        return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    function bufferToBase64Url(buffer) {
        const bytes = new Uint8Array(buffer);
        let binary = '';
        bytes.forEach(b => binary += String.fromCharCode(b));
        return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    const encodedPayload = base64UrlEncodeStr(payload);
    const enc = new TextEncoder();
    const key = await crypto.subtle.importKey(
        "raw",
        enc.encode(secret),
        { name: "HMAC", hash: "SHA-256" },
        false,
        ["sign"]
    );

    const signatureBuffer = await crypto.subtle.sign("HMAC", key, enc.encode(encodedPayload));
    const encodedSignature = bufferToBase64Url(signatureBuffer);

    return `${encodedPayload}.${encodedSignature}`;
}

// Connect to Backend or Start Mock
async function connectAndJoin() {
    updateStatus('connecting', 'Connecting...');
    
    // Update Lobby Profile
    elements.lobbyRoomId.textContent = state.roomId;
    elements.lobbyStudentName.textContent = state.displayName;
    elements.lobbyStudentId.textContent = `ID: ${state.studentId}`;
    elements.playerAvatar.textContent = state.displayName.charAt(0).toUpperCase();

    if (state.mode === 'mock') {
        setTimeout(() => {
            updateStatus('connected', 'Connected (Mock)');
            showView(elements.viewLobby);
            updateRoster([
                { student_id: state.studentId, display_name: state.displayName, score: 0 },
                { student_id: 'bot-1', display_name: 'Minh Anh (Bot)', score: 0 },
                { student_id: 'bot-2', display_name: 'Quang Huy (Bot)', score: 0 }
            ]);
            logConsole('[Mock] Đã tham gia phòng ' + state.roomId, 'info');
        }, 500);
        return;
    }

    // Real Mode - WebSocket to Netty Gateway
    let wsUrl = elements.wsUrl.value.trim();
    if (!wsUrl.includes('/ws')) {
        wsUrl = wsUrl.replace(/\/+$/, '') + '/ws';
    }
    const secret = elements.hmacSecret.value.trim();

    try {
        const joinToken = await mintDevJoinToken(state.studentId, state.roomId, secret);
        logConsole('MINT JOIN TOKEN: ' + joinToken.substring(0, 30) + '...', 'info');

        state.ws = new WebSocket(wsUrl);
        state.ws.binaryType = 'arraybuffer';

        state.ws.onopen = () => {
            updateStatus('connected', 'Connected');
            logConsole('WebSocket Connected to ' + wsUrl, 'info');
            sendJoinRoom(joinToken);
            showView(elements.viewLobby);
        };

        state.ws.onmessage = (event) => {
            handleBinaryMessage(new Uint8Array(event.data));
        };

        state.ws.onclose = (event) => {
            updateStatus('disconnected', 'Disconnected');
            logConsole(`WebSocket Closed (code: ${event.code})`, 'error');
            if (event.code === 1006) {
                logConsole('👉 GỢI Ý: Cổng WebSocket 9000 chưa bật Backend Java. Bạn có thể mở "⚙️ Cấu hình Dev" -> chọn "Mock Server Mode" để test giao diện game lập tức!', 'info');
            }
        };

        state.ws.onerror = (err) => {
            logConsole('Lỗi kết nối WebSocket tới ' + wsUrl, 'error');
        };

    } catch (e) {
        updateStatus('disconnected', 'Join Token Error');
        logConsole('Mint joinToken error: ' + e.message, 'error');
    }
}

// Send Protobuf Messages
function sendJoinRoom(joinToken) {
    state.sequence++;
    const payload = {
        type: 1, // JOIN_ROOM
        roomId: state.roomId,
        studentId: state.studentId,
        sequence: state.sequence,
        clientTimestampMs: Date.now(),
        joinRoom: {
            joinToken: joinToken,
            displayName: state.displayName,
            clientVersion: '1.0.0'
        }
    };
    sendProtobuf(payload, 'JOIN_ROOM');
}

function submitAnswer(choiceId) {
    if (!state.currentQuestionId) return;

    // Highlight selected button
    [elements.btnChoiceA, elements.btnChoiceB, elements.btnChoiceC, elements.btnChoiceD].forEach(btn => {
        btn.disabled = true;
        if (btn.getAttribute('data-choice') === choiceId) {
            btn.classList.add('selected');
        }
    });

    // Show Feedback Box
    elements.answerFeedback.classList.remove('hidden');
    elements.feedbackCard.className = 'feedback-card';
    elements.feedbackIcon.textContent = '⏳';
    elements.feedbackTitle.textContent = 'Đã chọn: ' + choiceId.toUpperCase();
    elements.feedbackDetail.textContent = 'Đang chờ xác nhận chấm điểm từ Engine...';

    if (state.mode === 'mock') {
        setTimeout(() => {
            const currentQ = MOCK_QUESTIONS_10.find(q => q.questionId === state.currentQuestionId);
            const isCorrect = currentQ ? choiceId === currentQ.correctAnswer : choiceId === 'a';
            const pts = isCorrect ? 100 : 0;
            state.myScore += pts;

            // Update player list with new score in mock mode
            const me = state.players.find(p => (p.studentId || p.student_id) === state.studentId);
            if (me) me.score = state.myScore;
            // Add random bot score updates for dynamic leaderboard feel
            state.players.forEach(p => {
                if ((p.studentId || p.student_id) !== state.studentId) {
                    if (Math.random() > 0.3) p.score = (p.score || 0) + 100;
                }
            });
            updateLeaderboard(state.players);

            showAnswerAck({
                accepted: true,
                awardedPoints: pts,
                totalScore: state.myScore,
                responseTimeMs: Math.floor(Math.random() * 400 + 150)
            });
        }, 400);
        return;
    }

    state.sequence++;
    const payload = {
        type: 2, // SUBMIT_ANSWER
        roomId: state.roomId,
        studentId: state.studentId,
        sequence: state.sequence,
        clientTimestampMs: Date.now(),
        submitAnswer: {
            questionId: state.currentQuestionId,
            answerIds: [choiceId],
            freeText: ''
        }
    };
    sendProtobuf(payload, `SUBMIT_ANSWER (${choiceId})`);
}

function sendTeacherCommand(cmdEnum) {
    if (state.mode === 'mock') {
        if (cmdEnum === 1) { // START_GAME
            triggerMockQuestion();
        } else if (cmdEnum === 4) { // END_GAME
            handleGameOver({
                reason: 'Giáo viên đã kết thúc trò chơi!',
                finalStandings: state.players
            });
        }
        return;
    }

    state.sequence++;
    const payload = {
        type: 4, // TEACHER_COMMAND
        roomId: state.roomId,
        studentId: state.studentId,
        sequence: state.sequence,
        clientTimestampMs: Date.now(),
        teacherCommand: {
            command: cmdEnum,
            targetStudentId: ''
        }
    };
    sendProtobuf(payload, `TEACHER_COMMAND (${cmdEnum})`);
}

function sendProtobuf(msgObj, label) {
    if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
        logConsole(`Cannot send ${label}: WS not open`, 'error');
        return;
    }
    const err = state.GameMessage.verify(msgObj);
    if (err) {
        logConsole(`Proto verify error: ${err}`, 'error');
        return;
    }
    const buffer = state.GameMessage.encode(state.GameMessage.create(msgObj)).finish();
    state.ws.send(buffer);
    logConsole(`[SEND] ${label} (${buffer.length} bytes)`, 'sent');
}

// LZ4 Framing Decoder (matches Gateway WireCompression.java)
function decompressLZ4Block(compressed, uncompressedSize) {
    const out = new Uint8Array(uncompressedSize);
    let i = 0, o = 0;
    while (i < compressed.length && o < uncompressedSize) {
        const token = compressed[i++];
        let literalLen = token >> 4;
        if (literalLen === 15) {
            let b;
            while (i < compressed.length && (b = compressed[i++]) === 255) {
                literalLen += 255;
            }
            literalLen += b;
        }
        for (let l = 0; l < literalLen; l++) {
            out[o++] = compressed[i++];
        }
        if (i >= compressed.length || o >= uncompressedSize) break;

        const offset = compressed[i] | (compressed[i + 1] << 8);
        i += 2;

        let matchLen = token & 0x0F;
        if (matchLen === 15) {
            let b;
            while (i < compressed.length && (b = compressed[i++]) === 255) {
                matchLen += 255;
            }
            matchLen += b;
        }
        matchLen += 4;

        let matchPos = o - offset;
        for (let m = 0; m < matchLen; m++) {
            out[o++] = out[matchPos++];
        }
    }
    return out;
}

function decodeWireBytes(bytes) {
    if (!bytes || bytes.length === 0) return bytes;
    const flag = bytes[0];
    if (flag === 0x00) { // FLAG_RAW (uncompressed protobuf bytes follow)
        return bytes.subarray(1);
    } else if (flag === 0x01) { // FLAG_LZ4 (4-byte BE length + LZ4 compressed block)
        if (bytes.length < 5) return bytes;
        const originalLength = ((bytes[1] << 24) >>> 0) + (bytes[2] << 16) + (bytes[3] << 8) + bytes[4];
        const compressed = bytes.subarray(5);
        return decompressLZ4Block(compressed, originalLength);
    }
    return bytes; // Fallback if unflagged
}

// Receive Protobuf Messages
function handleBinaryMessage(bytes) {
    try {
        const payloadBytes = decodeWireBytes(bytes);
        const decoded = state.GameMessage.decode(payloadBytes);
        const type = decoded.type;

        logConsole(`[RECV] ${getTypeName(type)} (${bytes.length} bytes)`, 'recv');

        if (decoded.roomStateSnapshot) {
            handleSnapshot(decoded.roomStateSnapshot);
        } else if (decoded.questionStarted) {
            handleQuestionStarted(decoded.questionStarted);
        } else if (decoded.answerAck) {
            showAnswerAck(decoded.answerAck);
        } else if (decoded.studentJoined) {
            logConsole(`Student Joined: ${decoded.studentJoined.displayName}`, 'info');
        } else if (decoded.gameOver) {
            handleGameOver(decoded.gameOver);
        } else if (decoded.connectionDegraded) {
            handleConnectionDegraded(decoded.connectionDegraded);
        }
    } catch (e) {
        logConsole('Decode message error: ' + e.message, 'error');
    }
}

function handleSnapshot(snapshot) {
    if (snapshot.players && snapshot.players.length > 0) {
        state.players = snapshot.players;
        updateRoster(snapshot.players);
        updateLeaderboard(snapshot.players);
    }
}

function handleQuestionStarted(qs) {
    if (autoAdvanceTimeout) clearTimeout(autoAdvanceTimeout);
    state.currentQuestionId = qs.questionId;
    showView(elements.viewQuestion);

    const TOTAL_QUESTIONS = 10;
    elements.questionStep.textContent = `Câu hỏi #${qs.stepIndex || 1} / ${TOTAL_QUESTIONS}`;
    elements.questionPrompt.textContent = qs.prompt || "Chọn đáp án đúng nhất:";

    // Choices
    const choices = qs.choices && qs.choices.length >= 4 ? qs.choices : [
        { answerId: 'a', text: 'Đáp án A' },
        { answerId: 'b', text: 'Đáp án B' },
        { answerId: 'c', text: 'Đáp án C' },
        { answerId: 'd', text: 'Đáp án D' }
    ];

    elements.textChoiceA.textContent = choices[0]?.text || 'Đáp án A';
    elements.textChoiceB.textContent = choices[1]?.text || 'Đáp án B';
    elements.textChoiceC.textContent = choices[2]?.text || 'Đáp án C';
    elements.textChoiceD.textContent = choices[3]?.text || 'Đáp án D';

    // Reset button states
    [elements.btnChoiceA, elements.btnChoiceB, elements.btnChoiceC, elements.btnChoiceD].forEach(btn => {
        btn.disabled = false;
        btn.classList.remove('selected');
    });

    elements.answerFeedback.classList.add('hidden');

    // Timer countdown
    startTimer(qs.durationMs || 15000);
}

function showAnswerAck(ack) {
    elements.answerFeedback.classList.remove('hidden');
    
    if (ack.accepted) {
        const points = ack.awardedPoints || 0;
        if (points > 0) {
            elements.feedbackCard.className = 'feedback-card correct';
            elements.feedbackIcon.textContent = '🎉';
            elements.feedbackTitle.textContent = `CHÍNH XÁC! +${points} ĐIỂM`;
        } else {
            elements.feedbackCard.className = 'feedback-card incorrect';
            elements.feedbackIcon.textContent = '❌';
            elements.feedbackTitle.textContent = 'CHƯA CHÍNH XÁC (0 ĐIỂM)';
        }
        elements.feedbackDetail.textContent = `Thời gian phản hồi: ${ack.responseTimeMs || 0}ms | Tổng điểm: ${ack.totalScore || state.myScore} | ⏳ Tự động chuyển câu tiếp theo...`;
        
        if (ack.totalScore !== undefined) {
            state.myScore = ack.totalScore;
            elements.myScoreBadge.textContent = `Điểm của bạn: ${state.myScore}`;
        }
    } else {
        const reasonText = getRejectReasonName(ack.rejectReason);
        elements.feedbackCard.className = 'feedback-card incorrect';
        elements.feedbackIcon.textContent = '⚠️';
        elements.feedbackTitle.textContent = 'PHẢN HỒI TỪ JAVA ENGINE';
        elements.feedbackDetail.textContent = `Lý do BE từ chối: ${reasonText} | ⏳ Tự động chuyển câu tiếp theo...`;
    }

    if (autoAdvanceTimeout) clearTimeout(autoAdvanceTimeout);
    autoAdvanceTimeout = setTimeout(() => {
        triggerMockQuestion();
    }, 3000);
}

function handleGameOver(go) {
    if (autoAdvanceTimeout) clearTimeout(autoAdvanceTimeout);
    showView(elements.viewGameOver);
    if (elements.gameOverReason) {
        elements.gameOverReason.textContent = go.reason || 'Trò chơi đã kết thúc!';
    }

    const standings = go.finalStandings || state.players;
    const podiumContainer = elements.podiumContainer || document.getElementById('podiumContainer');
    if (podiumContainer) {
        podiumContainer.innerHTML = '';

        const sortedStandings = [...standings].sort((a, b) => (b.score || 0) - (a.score || 0));

        sortedStandings.slice(0, 3).forEach((p, idx) => {
            const div = document.createElement('div');
            div.className = `podium-step step-${idx + 1}`;
            div.innerHTML = `
                <div>${idx === 0 ? '🥇' : idx === 1 ? '🥈' : '🥉'}</div>
                <div>${p.displayName || p.display_name || p.studentId}</div>
                <div style="font-size:12px;opacity:0.8">${p.score || 0} điểm</div>
            `;
            podiumContainer.appendChild(div);
        });
    }
}

function handleConnectionDegraded(cd) {
    elements.degradedBanner.classList.remove('hidden');
    elements.degradedMsg.textContent = cd.message || 'Kết nối bị suy giảm, giữ nguyên trang web...';
}

// UI State Render Helpers
function showView(viewEl) {
    [elements.viewJoin, elements.viewLobby, elements.viewQuestion, elements.viewGameOver].forEach(v => {
        v.classList.add('hidden');
    });
    viewEl.classList.remove('hidden');
}

function updateStatus(status, text) {
    elements.connectionStatus.className = `status-indicator ${status}`;
    elements.statusText.textContent = text;
}

function updateRoster(players) {
    elements.playerCount.textContent = players.length;
    elements.rosterGrid.innerHTML = '';

    players.forEach(p => {
        const li = document.createElement('li');
        li.className = 'roster-pill';
        li.textContent = p.displayName || p.display_name || p.studentId;
        elements.rosterGrid.appendChild(li);
    });
}

function updateLeaderboard(players) {
    // Sort players by score desc
    const sorted = [...players].sort((a, b) => (b.score || 0) - (a.score || 0));
    elements.leaderboardList.innerHTML = '';

    sorted.forEach((p, idx) => {
        const li = document.createElement('li');
        const isMe = (p.studentId || p.student_id) === state.studentId;
        li.className = `leaderboard-item ${isMe ? 'me' : ''}`;

        const rankClass = idx === 0 ? 'rank-1' : idx === 1 ? 'rank-2' : idx === 2 ? 'rank-3' : '';

        li.innerHTML = `
            <div>
                <span class="rank-badge ${rankClass}">${idx + 1}</span>
                <span>${p.displayName || p.display_name || p.studentId}</span>
            </div>
            <span class="player-score">${p.score || 0}</span>
        `;
        elements.leaderboardList.appendChild(li);

        if (isMe) {
            state.myScore = p.score || 0;
            elements.myScoreBadge.textContent = `Điểm của bạn: ${state.myScore}`;
        }
    });
}

function startTimer(durationMs) {
    if (state.timerInterval) clearInterval(state.timerInterval);

    const startTime = Date.now();
    const endTime = startTime + durationMs;

    state.timerInterval = setInterval(() => {
        const now = Date.now();
        const remaining = Math.max(0, endTime - now);
        const percent = (remaining / durationMs) * 100;

        elements.timerBar.style.width = `${percent}%`;
        elements.timerText.textContent = `${Math.ceil(remaining / 1000)}s`;

        if (remaining <= 0) {
            clearInterval(state.timerInterval);
            if (!elements.btnChoiceA.disabled) {
                // Time's up
                [elements.btnChoiceA, elements.btnChoiceB, elements.btnChoiceC, elements.btnChoiceD].forEach(btn => {
                    btn.disabled = true;
                });
                showAnswerAck({
                    accepted: true,
                    awardedPoints: 0,
                    totalScore: state.myScore,
                    responseTimeMs: durationMs
                });
            }
        }
    }, 100);
}

let mockStepIndex = 0;

function triggerMockQuestion() {
    if (mockStepIndex >= MOCK_QUESTIONS_10.length) {
        logConsole('[Mock] Đã hoàn thành 10/10 câu hỏi! Chuyển sang Kết thúc Game...', 'info');
        handleGameOver({
            reason: 'Chúc mừng bạn đã hoàn thành xuất sắc 10/10 câu hỏi!',
            finalStandings: state.players
        });
        mockStepIndex = 0; // Reset for next game
        return;
    }

    const q = MOCK_QUESTIONS_10[mockStepIndex];
    mockStepIndex++;
    handleQuestionStarted(q);
    logConsole(`[Mock] Đã phát câu hỏi ${q.stepIndex}/10: "${q.prompt.substring(0, 25)}..."`, 'info');
}

function getTypeName(typeInt) {
    const types = {
        1: 'JOIN_ROOM', 2: 'SUBMIT_ANSWER', 4: 'TEACHER_COMMAND',
        20: 'ROOM_STATE_SNAPSHOT', 21: 'ANSWER_ACK', 22: 'QUESTION_STARTED',
        23: 'STUDENT_JOINED', 24: 'GAME_OVER', 25: 'CONNECTION_DEGRADED'
    };
    return types[typeInt] || `TYPE_${typeInt}`;
}

function getRejectReasonName(reasonInt) {
    const reasons = {
        0: 'Chưa xác định (UNSPECIFIED)',
        1: 'Không có lỗi (NONE)',
        2: 'Quá thời gian quy định (PAST_DEADLINE)',
        3: 'Trùng lặp sequence (DUPLICATE_SEQUENCE)',
        4: 'Câu hỏi chưa được đăng ký trên Java Engine (UNKNOWN_QUESTION)',
        5: 'Sai giai đoạn trò chơi (WRONG_PHASE)',
        6: 'Vượt quá giới hạn tần suất (RATE_LIMIT_EXCEEDED)'
    };
    return reasons[reasonInt] || `Mã lỗi ${reasonInt}`;
}
