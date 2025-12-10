# Backend Code Tree & Domain Map

## 1. Repository Tree ( `|` / `L--` 스타일 )
```
apps/backend
|
|-- pom.xml ................ Maven 프로젝트 정의
|-- Dockerfile ............. 멀티스테이지 Spring Boot 이미지 빌드
|-- docker-compose.yaml .... API + Mongo 개별 기동 구성
|-- docker-compose.o11y.yaml 관측(프로메테우스/그라파나) 포함 구성
|-- Makefile ............... 빌드·테스트·배포 헬퍼 타깃
|-- app-control.sh ......... 로컬/서버에서 start|stop|status 자동화
|-- README.md/DEPLOY.md .... 개발·배포 가이드
|-- monitoring/ ............ 프로메테우스, 대시보드 등 모니터링 설정
|-- docs/
|   L-- backend-code-tree.md (현재 문서)
|-- src/
|   |
|   |-- main/
|   |   |
|   |   |-- java/com/ktb/chatapp
|   |   |   |
|   |   |   |-- annotation ............. RateLimit 애너테이션
|   |   |   |-- config ................. Spring/Socket/Swagger 설정
|   |   |   |-- controller ............. REST API (Auth/Room/User 등)
|   |   |   |-- dto ................... 요청/응답/공통 DTO
|   |   |   |-- event ................. Spring ApplicationEvent 정의
|   |   |   |-- exception ............. 글로벌 예외/세션 예외
|   |   |   |-- model ................. Mongo 도메인 모델
|   |   |   |-- repository ............ Spring Data 리포지토리
|   |   |   |-- security .............. JWT, 토큰 리졸버
|   |   |   |-- service ............... 비즈니스 로직 + 파일/세션/레이트리밋
|   |   |   |-- websocket/socketio .... 실시간 핸들러·AI 스트림
|   |   |   |-- util .................. 파일/암호화/금칙어 유틸
|   |   |   L-- validation ............ Bean Validation 애너테이션 & 검증기
|   |   L-- resources/
|   |       |-- application.properties .... 포트/JWT/Mongo 설정
|   |       |-- fake_banned_words_10k.txt . 금칙어 샘플 데이터
|   |       L-- static/ ................... 정적 리소스 루트
|   L-- test/ (미사용, 기본 구조)
|
|-- target/ ................. Maven 빌드 산출물
L-- uploads/ ................ LocalFileService 기본 저장소 (gitignore 대상)
```

---

## 2. 도메인별 코드 묶음

### 2.1 플랫폼 & 부트스트랩
- `ChatAppApplication.java` — Spring Boot 엔트리 포인트.
- `config/` — JWT, Mongo, Security, Socket.IO, OpenAPI, WebMvc, RateLimit interceptor 등 인프라 설정을 한 곳에 모음.
- `annotation/RateLimit.java` + `config/RateLimitInterceptor.java` — HTTP 엔드포인트 레이트 리밋 주입을 위한 메타데이터/인터셉터 페어.

### 2.2 인증 · 사용자 계정
- `controller/AuthController.java` — 회원가입/로그인/토큰 검증·갱신/로그아웃.
- `controller/UserController.java` — 내 프로필 조회·수정, 프로필 이미지 업로드/삭제, 회원 탈퇴.
- `service/UserService.java` — 사용자 도메인 비즈니스 로직 (프로필, 사진, 삭제).
- `service/JwtService.java`, `security/CustomBearerTokenResolver.java`, `security/SessionAwareJwtAuthenticationConverter.java` — JWT 발급·검증과 세션 연동된 인증 전략.
- `service/SessionService.java`, `service/session/*`, `model/Session.java`, `repository/SessionRepository.java` — 단일 세션 정책과 TTL 유지.

### 2.3 채팅방 & 메시지 도메인
- `controller/RoomController.java` — 방 목록/생성/참여/헬스체크 REST API.
- `service/RoomService.java` — 페이징·검색·통계·이벤트 발행 로직.
- `controller/MessageController.java` — REST 메시지 엔드포인트(미구현 안내)로 Socket 사용 유도.
- `model/Room.java`, `model/Message.java`, `model/MessageType.java`, `model/User.java` — 핵심 도메인 모델; Message에는 읽음 상태와 메타데이터 포함.
- `repository/RoomRepository.java`, `repository/MessageRepository.java`, `repository/UserRepository.java` — Mongo 기반 CRUD + 커스텀 쿼리.
- `dto/RoomsResponse.java`, `dto/RoomResponse.java`, `dto/Page*`, `dto/Message*` — 방·메시지 REST/Socket 공통 데이터 포맷.

### 2.4 파일 & 업로드
- `controller/FileController.java` — 업로드/다운로드/뷰/삭제 API, 인증 사용자 검증.
- `service/FileService.java` + `service/LocalFileService.java` — 파일 보안 검증, 안전 파일명 생성, 경로 탈출 방지, 메시지/방 권한 체크.
- `repository/FileRepository.java`, `model/File.java`, `dto/FileResponse.java`, `service/FileUploadResult.java` — 파일 메타데이터 저장 및 응답 모델.
- `uploads/` — 실제 파일 저장소 (프로필/채팅 파일).

### 2.5 레이트 리밋 & 보안 부가 기능
- `service/RateLimitService.java`, `service/ratelimit/*`, `model/RateLimit.java`, `repository/RateLimitRepository.java` — Mongo 기반 요청 빈도 제어, 멀티 호스트 환경 대비.
- `util/BannedWordChecker.java`, `config/BannedWordConfig.java`, `resources/fake_banned_words_10k.txt` — 금칙어 필터링.
- `util/EncryptionUtil.java`, `model/User#setEmail` — 이메일 암호화 저장.
- `validation/*` — 이름/이메일/비밀번호 커스텀 Bean Validation 애너테이션 및 구현체.
- `exception/GlobalExceptionHandler.java`, `controller/CustomErrorController.java`, `exception/SessionExpiredException.java` — 표준화된 오류 응답과 세션 만료 처리.

### 2.6 실시간 WebSocket · AI 연동
- `config/SocketIOConfig.java`, `security/CustomBearerTokenResolver.java` — Socket.IO 서버와 토큰 검사 설정.
- `websocket/socketio/handler/*` — ConnectionLogin, RoomJoin/Leave, Message(파일/읽음/반응) 처리.
- `websocket/socketio/SocketIOEvents.java` — 클라이언트/서버 이벤트 상수 정의.
- `websocket/socketio/SocketIOEventListener.java` — Spring 이벤트 → Socket.IO 브로드캐스트 브리지.
- `websocket/socketio/ai/*` — Spring AI 기반 멘션 처리(`AiService`), 청크 이벤트(`AiStreamHandler`, `ChunkData`), 스트리밍 세션 모델.
- `event/*` — 룸/세션/AI 스트리밍 등 도메인 이벤트.
- `dto/ActiveStreamResponse.java`, `dto/MessageReaction*`, `dto/JoinRoom*` — WebSocket 전용/공용 DTO.

### 2.7 인프라/운영 유틸
- `controller/HealthController.java`, `controller/ApiInfoController.java` — 헬스체크와 API 디렉토리 노출.
- `controller/StatusResponse.java`, `dto/StandardResponse.java`, `dto/ErrorResponse.java` — 공통 응답 포맷.
- `controller/FileController.java#handleFileError`, `controller/AuthController.java#getBindingError` — 반복되는 에러 포맷 헬퍼.
- `docs/`, `monitoring/` — 운영/관측 가이드, 네트워킹 다이어그램 등.

---

## 3. 패키지별 주요 클래스 역할

### 3.1 `controller`
- `ApiInfoController` — 배포된 API 맵을 JSON으로 노출.
- `AuthController` — 회원가입/로그인/토큰 검증·갱신/로그아웃·세션 종료.
- `CustomErrorController` — `/error` 응답을 JSON 포맷으로 통일하고 dev 프로파일에서 stack 노출.
- `FileController` — 파일 업로드·다운로드·뷰·삭제, 권한 및 파일명 검증.
- `HealthController` — `/api/health` 헬스체크(상태, 타임스탬프, 프로파일).
- `MessageController` — REST 메시지 엔드포인트가 미구현임을 알리고 Socket 사용 유도.
- `RoomController` — 방 목록·생성·참여·헬스체크·통계, RateLimit 적용.
- `UserController` — 내 프로필 조회·수정, 이미지 업로드/삭제, 회원 탈퇴.
- `StatusResponse`, `UserApiResponse`, `UserUpdateResponse` — 컨트롤러 전용 래퍼 응답 DTO.

### 3.2 `config`
- `BannedWordConfig` — 금칙어 파일 로딩, `BannedWordChecker` 빈 등록.
- `JwtConfig` — JwtEncoder/JwtDecoder, 만료 토큰 디코더, 커스텀 Validator.
- `MongoConfig` — MongoTemplate, 인덱스 준비.
- `OpenApiConfig` — Swagger/OpenAPI 문서 및 공통 응답 스키마.
- `RateLimitInterceptor` — @RateLimit 애너테이션 처리.
- `RequestLoggingFilter` — HTTP 요청 메타데이터 로그.
- `SecurityConfig` — Spring Security 리소스 서버, CORS, 세션/헤더 정책.
- `SocketIOConfig` — SocketIOServer, 인증 리졸버, 메모리 스토어 설정.
- `WebMvcConfig` — RateLimitInterceptor 등록, 정적 리소스 핸들링.
- `JwtConfig#expiredTokenDecoder` bean 등 세부 부가 설정 클래스 포함.

### 3.3 `annotation`
- `RateLimit` — 메서드 레벨 RateLimit 메타데이터 정의.

### 3.4 `dto`
- **인증/사용자**: `AuthUserDto`, `LoginRequest`, `LoginResponse`, `RegisterRequest`, `TokenVerifyResponse`, `TokenRefreshResponse`, `UserResponse`, `UpdateProfileRequest`, `UserApiResponse`, `UserUpdateResponse`, `ProfileImageResponse`.
- **방/메시지 REST**: `CreateRoomRequest`, `JoinRoomRequest`, `JoinRoomSuccessResponse`, `RoomResponse`, `RoomsResponse`, `PageRequest`, `PageMetadata`, `ChatMessageRequest`, `FetchMessagesRequest`, `FetchMessagesResponse`, `MessageResponse`, `MessageContent`, `MessagesReadResponse`, `MessageReactionRequest/Response`, `MarkAsReadRequest`, `ActiveStreamResponse`.
- **공통 응답**: `StandardResponse`, `ErrorResponse`, `ValidationError`, `ApiErrorCode`, `StatusResponse`.
- **파일**: `FileResponse`.
- 기타: `TokenVerifyResponse`, `TokenRefreshResponse`, `HealthResponse`, `RoomResponse`, `RoomResponse`.

### 3.5 `event`
- `RoomCreatedEvent`, `RoomUpdatedEvent` — 방 변동 사항을 Socket.IO로 브로드캐스트.
- `SessionEndedEvent` — 강제 로그아웃 통지.
- `AiMessageStartEvent`, `AiMessageChunkEvent`, `AiMessageCompleteEvent`, `AiMessageSavedEvent`, `AiMessageErrorEvent` — AI 스트리밍 lifecycle.

### 3.6 `exception`
- `GlobalExceptionHandler` — REST 전역 예외 처리, Validation/Business 에러 분기.
- `SessionExpiredException` — 세션 만료 상황에서 throw.

### 3.7 `model`
- `User` — 이름/이메일(암호화)/비밀번호/프로필 이미지/타임스탬프.
- `Room` — 방 메타데이터, 참가자 목록, 비밀번호 정보.
- `Message` — 메시지 본문, 타입, 파일/AI/읽음 상태, 메타데이터.
- `MessageType` — text/file/ai/system 등 타입 enum.
- `File` — 파일 경로/크기/사용자/업로드 시각.
- `Session` — userId, sessionId, TTL, lastActivity.
- `RateLimit` — clientId별 요청 횟수와 만료 시각.
- `AiType` — AI 페르소나 enum(wayneAI, consultingAI 등).

### 3.8 `repository`
- `UserRepository`, `RoomRepository`, `MessageRepository`, `FileRepository`, `SessionRepository`, `RateLimitRepository` — MongoCollection별 CRUD 및 커스텀 쿼리.

### 3.9 `security`
- `CustomBearerTokenResolver` — x-auth-token → query → Authorization 순으로 토큰 추출.
- `SessionAwareJwtAuthenticationConverter` — JWT에서 userId/sessionId를 ValidationResult와 함께 Authentication으로 변환.

### 3.10 `service`
- **공통/지원**: `JwtService`, `UserDetailsServiceImpl`, `MessageReadStatusService`, `FileService`(interface), `FileUploadResult`.
- **사용자·파일**: `UserService` — 프로필 관리 및 이미지 업로드/삭제; `LocalFileService` — FileService 구현체, 저장/다운로드/삭제.
- **세션**: `SessionService`, `SessionData`, `SessionMetadata`, `SessionCreationResult`, `SessionValidationResult`, `service/session/SessionStore`, `service/session/SessionMongoStore`.
- **레이트리밋**: `RateLimitService`, `RateLimitCheckResult`, `service/ratelimit/RateLimitStore`, `RateLimitMongoStore`.
- **채팅방**: `RoomService` — 방 CRUD·헬스체크·이벤트 발행.
- **AI/Socket 연동**: `websocket/socketio/ai/AiService` — Spring AI 호출, 이벤트 발행; `AiStreamHandler`, `ChunkData`.

### 3.11 `util`
- `BannedWordChecker` — 금칙어 탐지.
- `EncryptionUtil` — 이메일 암호화/복호화.
- `FileUtil` — 파일 검증, 안전 파일명, 경로 검증.

### 3.12 `validation`
- 애너테이션: `ValidEmail`, `ValidName`, `ValidPassword`.
- 구현체: `EmailValidator`, `NameValidator`, `PasswordValidator`.

### 3.13 `websocket/socketio`
- **핵심 객체**: `SocketIOEvents`(이벤트 상수), `SocketUser`, `ConnectedUsers`, `UserRooms`, `ChatDataStore`, `LocalChatDataStore`, `SocketIOEventListener`.
- **핸들러**: `handler/ConnectionLoginHandler`, `RoomJoinHandler`, `RoomLeaveHandler`, `MessageFetchHandler`, `MessageLoader`, `MessageResponseMapper`, `ChatMessageHandler`, `MessageReadHandler`, `MessageReactionHandler`, `StreamingSession`.
- **AI 연동**: `ai/AiService`, `AiStreamHandler`, `ChunkData`.
- **Auth**: `socketio/AuthTokenListenerImpl` — 핸드셰이크 시 토큰 검증, 사용자 로드.

---

## 3. 사용 가이드
1. **엔드포인트 찾기** — 원하는 REST 기능은 `controller/` 하위와 동일 이름의 `service/` 에서 비즈니스 로직을 찾을 수 있습니다.
2. **실시간 흐름** — Socket.IO 이벤트는 `websocket/socketio/handler`에서 시작해 `AiService`나 `RoomService` 같은 도메인 서비스로 이어집니다.
3. **도메인 변경** — 모델/리포지토리 수정 후 DTO와 서비스 계층을 함께 검토하십시오. 이벤트가 존재하는 도메인은 `event/` → `SocketIOEventListener` 경로도 확인해야 합니다.
